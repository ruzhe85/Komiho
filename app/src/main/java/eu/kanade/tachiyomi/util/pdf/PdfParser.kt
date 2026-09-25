package eu.kanade.tachiyomi.util.pdf

import android.util.Log
import java.io.RandomAccessFile
import java.util.zip.Inflater

/**
 * 轻量 PDF 解析器（方案 A：自写提取 + 系统 PdfRenderer 兜底）。
 *
 * 设计目标：从本地 PDF 中抽取「内嵌图原始字节流」——彩色扫描漫画主流是 DCTDecode(JPEG)，
 * 直通原始字节即可被现有解码+增强管线（Lanczos3 / AI / NPU）无损处理，网点细节不被重采样抹掉。
 * 非 DCT（CCITT/JPX/Flate/矢量）由 [PdfRenderFallback] 用系统 PdfRenderer 渲染兜底。
 *
 * 兼容性教训（来自解析 Spike）：
 *  - 现代 PDF 用 xref 流 + 对象流(ObjStm) 压缩存储，必须支持；
 *  - 字典可能无空格（`/Type/Pages` 合法），且 name 值（`/Type /Pages` 中 Pages 是值）必须按值捕获；
 *  - 损坏 xref 时回退全文件扫描 `obj…endobj`。
 *
 * 内存说明：骨架阶段整文件读入 ByteArray；超大 PDF 的随机读取优化留待补齐步。
 */
class PdfParser(path: String) {

    private val data: ByteArray = RandomAccessFile(path, "r").use { raf ->
        val len = raf.length()
        val buf = ByteArray(len.toInt())
        raf.readFully(buf)
        buf
    }

    /** objnum -> 对象在 data 中的起始偏移（"N G obj" 起点） */
    private val objStart = mutableMapOf<Int, Int>()
    /** objnum -> 在对象流(ObjStm)内的原始字节（压缩存储对象） */
    private val objStmInner = mutableMapOf<Int, ByteArray>()
    /** objnum -> 该对象是否来自对象流 */
    private val fromObjStm = mutableSetOf<Int>()

    private var rootRef: String? = null
    private var encryptRef: String? = null
    private val pageList = mutableListOf<Int>()

    val pageCount: Int get() = pageList.size
    fun isEncrypted(): Boolean = encryptRef != null

    init {
        parse()
    }

    // ---------- 入口 ----------

    private fun parse() {
        val sx = findStartXref()
        if (sx < 0) {
            scanWholeFile()
        } else {
            parseXref(sx)
        }
        resolveRoot()
        collectPages()
    }

    private fun findStartXref(): Int {
        // 最后一个 startxref 之后是 EOF 偏移
        var idx = data.lastIndexOfBytes("startxref".toByteArray(ISO))
        if (idx < 0) return -1
        val len = (data.size - idx).coerceAtMost(64)
        val tail = String(data, idx, len, ISO)
        val m = Regex("""startxref\s+(\d+)""").find(tail) ?: return -1
        return m.groupValues[1].toIntOrNull() ?: -1
    }

    private fun parseXref(offset: Int) {
        if (data.startsWith("xref".toByteArray(ISO), offset)) {
            parseXrefTable(offset)
        } else {
            parseXrefStream(offset)
        }
        // 跟随 /Prev 链式解析（混合参照 PDF）
        var prev = trailerPrev
        while (prev != null) {
            val p = prev
            prev = null
            val savedRoot = rootRef
            val savedEnc = encryptRef
            if (data.startsWith("xref".toByteArray(ISO), p)) {
                parseXrefTable(p)
            } else {
                parseXrefStream(p)
            }
            if (rootRef == null) rootRef = savedRoot
            if (encryptRef == null) encryptRef = savedEnc
            prev = trailerPrev
        }
    }

    private var trailerPrev: Int? = null

    private fun parseXrefTable(offset: Int) {
        var i = offset
        // 跳过 "xref"
        i = skipLine(i)
        while (i < data.size) {
            val line = readLine(i)
            val hdr = line.trim().splitWs()
            if (hdr.size == 2 && hdr[0].all { it.isDigit() } && hdr[1].all { it.isDigit() }) {
                val start = hdr[0].toInt()
                val count = hdr[1].toInt()
                i = lineEnd(i)
                repeat(count) {
                    val entry = readLine(i)
                    val parts = entry.trim().splitWs()
                    if (parts.size >= 3 && parts[2] == "n") {
                        objStart[start + it] = parts[0].toInt()
                    }
                    i = lineEnd(i)
                }
                break
            }
            if (line.trim().startsWith("trailer")) {
                val td = parseTrailerAt(i)
                applyTrailer(td)
                return
            }
            i = lineEnd(i)
        }
    }

    private fun parseXrefStream(offset: Int) {
        val objStartPos = data.lastIndexOfBytes("obj".toByteArray(ISO), offset + 16)
        if (objStartPos < 0) return
        val bytes = objectBytesAt(objStartPos)
        val dict = parseDict(bytes, firstDictStart(bytes), dictEnd(bytes)) ?: return
        val w = dict["/W"]?.let { parseIntArray(it) } ?: return
        if (w.isEmpty()) return
        val size = dict["/Size"]?.firstInt() ?: return
        val index = dict["/Index"]?.let { parseIndexed(it) }
            ?: listOf(0 to size)
        val raw = streamBytesOf(bytes) ?: return
        val dec = tryInflate(raw) ?: return
        var p = 0
        val es = w.sum()
        var ii = 0
        while (ii < index.size) {
            val start = index[ii].first
            val count = index[ii].second
            repeat(count) { k ->
                var f0 = 0
                var f1 = 0
                var f2 = 0
                for (wi in w.indices) {
                    val v = if (w[wi] > 0) {
                        var acc = 0
                        for (b in 0 until w[wi]) { acc = (acc shl 8) or (dec[p].toInt() and 0xFF); p++ }
                        acc
                    } else 0
                    when (wi) {
                        0 -> f0 = v
                        1 -> f1 = v
                        2 -> f2 = v
                    }
                }
                when (f0) {
                    1 -> objStart[start + k] = f1
                    2 -> loadObjStm(f1)
                }
            }
            ii += 2
        }
        applyTrailer(dict)
    }

    private fun loadObjStm(stmObjNum: Int) {
        if (fromObjStm.contains(stmObjNum) && objStmInner.containsKey(stmObjNum)) return
        val bytes = objStart[stmObjNum]?.let { objectBytesAt(it) } ?: return
        val dict = parseDict(bytes, firstDictStart(bytes), dictEnd(bytes)) ?: return
        val raw = streamBytesOf(bytes) ?: return
        val inner = tryInflate(raw) ?: return
        val n = dict["/N"]?.firstInt() ?: return
        val first = dict["/First"]?.firstInt() ?: return
        val nums = Regex("""\d+""").findAll(String(inner, 0, first, ISO)).map { it.value.toInt() }.toList()
        val pairs = nums.chunked(2)
        for ((onum, off) in pairs) {
            val segStart = first + off
            val e = inner.indexOfBytes("endobj".toByteArray(ISO), segStart)
            val seg = if (e >= 0) inner.copyOfRange(segStart, e) else inner.copyOfRange(segStart, inner.size)
            objStmInner[onum] = seg
            fromObjStm.add(onum)
        }
    }

    private fun applyTrailer(dict: Map<String, ByteArray>) {
        dict["/Root"]?.let { rootRef = String(it, ISO) }
        dict["/Encrypt"]?.let { encryptRef = String(it, ISO) }
        trailerPrev = dict["/Prev"]?.firstInt()
    }

    private fun parseTrailerAt(i: Int): Map<String, ByteArray> {
        val td = data.indexOfBytes("<<".toByteArray(ISO), i)
        if (td < 0) return emptyMap()
        val te = data.indexOfBytes(">>".toByteArray(ISO), td)
        return parseDict(data, td, te + 2) ?: emptyMap()
    }

    private fun resolveRoot() {
        if (rootRef == null) return
        val rootNum = deref(rootRef!!) ?: return
        val dict = dictOf(rootNum) ?: return
        pagesRootRef = dict["/Pages"]?.let { String(it, ISO) }
    }

    private var pagesRootRef: String? = null

    private fun collectPages() {
        val ref = pagesRootRef ?: return
        val num = deref(ref) ?: return
        walkPages(num, pageList)
    }

    private fun walkPages(objNum: Int, out: MutableList<Int>) {
        val dict = dictOf(objNum) ?: return
        when (val t = dict["/Type"]?.let { String(it, ISO) }) {
            "/Pages" -> {
                val kids = dict["/Kids"]?.let { findRefs(it) } ?: emptyList()
                for (k in kids) walkPages(k, out)
            }
            "/Page" -> out.add(objNum)
        }
    }

    // ---------- 对外查询 ----------

    /** 某页的最佳内嵌图（面积最大）。无图（矢量）返回 null 交由渲染兜底。 */
    fun bestImageForPage(pageIndex: Int): PdfImage? {
        if (pageIndex !in pageList.indices) return null
        val pageNum = pageList[pageIndex]
        val pageDict = dictOf(pageNum) ?: return null
        val resRaw = pageDict["/Resources"]
        val resDict = if (resRaw != null) {
            val s = String(resRaw, ISO)
            if (s.trim().startsWith("/")) {
                deref(s)?.let { dictOf(it) }
            } else {
                // 内联字典：在页面对象内就地解析 /XObject
                null
            }
        } else null

        val xobjRaw = resDict?.get("/XObject")
            ?: pageDict["/XObject"] // 内联 Resources 退化情况（极少）
            ?: return null
        val xobjStr = String(xobjRaw, ISO)
        // XObject 可能是 "<< ... >>" 内联，或 "N G R" 引用
        val xdict = if (xobjStr.trim().startsWith("<<")) {
            val ds = xobjRaw.indexOf('<'.code.toByte()); val de = xobjRaw.indexOf('>'.code.toByte())
            parseDict(xobjRaw, ds, de + 2)
        } else {
            deref(xobjStr)?.let { dictOf(it) }
        } ?: return null

        var best: PdfImage? = null
        for ((_, refBytes) in xdict) {
            val ref = String(refBytes, ISO)
            if (!ref.contains("R")) continue
            val num = deref(ref) ?: continue
            val idict = dictOf(num) ?: continue
            if (idict["/Subtype"]?.let { String(it, ISO) } != "/Image") continue
            val w = idict["/Width"]?.firstInt() ?: 0
            val h = idict["/Height"]?.firstInt() ?: 0
            val filter = idict["/Filter"]?.let { String(it, ISO) }?.trim()
            val img = PdfImage(num, w, h, normalizeFilter(filter))
            if (best == null || w * h > best.width * best.height) best = img
        }
        return best
    }

    private fun normalizeFilter(f: String?): String? {
        if (f == null) return null
        // 可能含多滤镜（数组）或带方括号；取第一个
        val cleaned = f.trim().removeSurrounding("[", "]").trim()
        val first = cleaned.splitWs().firstOrNull() ?: cleaned
        return first
    }

    /** 抽取内嵌图的原始流字节（DCTDecode 即 JPEG，可交解码器直解）。 */
    fun rawStreamBytes(objNum: Int): ByteArray? {
        val bytes = objStart[objNum]?.let { objectBytesAt(it) }
            ?: objStmInner[objNum]
            ?: return null
        return streamBytesOf(bytes)
    }

    fun pageMediaBox(pageIndex: Int): Pair<Int, Int>? {
        if (pageIndex !in pageList.indices) return null
        val pageNum = pageList[pageIndex]
        val dict = dictOf(pageNum) ?: return null
        val mb = dict["/MediaBox"]?.let { parseRect(it) }
        return mb
    }

    // ---------- 对象模型 ----------

    private fun objectBytesAt(offset: Int): ByteArray {
        val e = data.indexOfBytes("endobj".toByteArray(ISO), offset)
        val end = if (e >= 0) e else data.size
        return data.copyOfRange(offset, end)
    }

    private fun dictOf(objNum: Int): Map<String, ByteArray>? {
        val bytes = if (fromObjStm.contains(objNum)) {
            objStmInner[objNum] ?: return null
        } else {
            objStart[objNum]?.let { objectBytesAt(it) } ?: return null
        }
        val ds = bytes.indexOf('<'.code.toByte())
        if (ds < 0 || bytes.getOrElse(ds + 1) { 0.toByte() } != '<'.code.toByte()) return null
        val de = bytes.indexOf('>'.code.toByte())
        val de2 = bytes.indexOfByte('>'.code.toByte(), de + 1)
        return parseDict(bytes, ds, if (de2 > de) de2 + 2 else bytes.size)
    }

    // ---------- 字典/字节工具 ----------

    private fun firstDictStart(bytes: ByteArray): Int {
        val i = bytes.indexOf('<'.code.toByte())
        return if (i >= 0 && bytes.getOrElse(i + 1) { 0.toByte() } == '<'.code.toByte()) i else 0
    }

    private fun dictEnd(bytes: ByteArray): Int {
        val i = bytes.indexOf('<'.code.toByte())
        if (i < 0) return bytes.size
        var depth = 0
        var j = i
        while (j < bytes.size - 1) {
            if (bytes[j] == '<'.code.toByte() && bytes[j + 1] == '<'.code.toByte()) depth++
            else if (bytes[j] == '>'.code.toByte() && bytes[j + 1] == '>'.code.toByte()) {
                depth--
                if (depth == 0) return j + 2
            }
            j++
        }
        return bytes.size
    }

    /** 健壮字典扫描：支持无空格字典、name 值、嵌套字典/数组/字符串。 */
    private fun parseDict(bytes: ByteArray, start: Int, end: Int): Map<String, ByteArray>? {
        var i = start
        // 跳过前导空白与 <<
        while (i < end && bytes[i] in WHITESPACE) i++
        if (i < end - 1 && bytes[i] == '<'.code.toByte() && bytes[i + 1] == '<'.code.toByte()) i += 2

        val out = mutableMapOf<String, ByteArray>()
        while (i < end) {
            i = skipWs(bytes, i, end)
            if (i >= end) break
            if (bytes[i] == '>'.code.toByte() && bytes.getOrElse(i + 1) { 0.toByte() } == '>'.code.toByte()) break
            if (bytes[i] != '/'.code.toByte()) { i++; continue }
            val kStart = i
            i++
            while (i < end && bytes[i] !in VALUE_STOP) i++
            val key = String(bytes, kStart, i - kStart, ISO)
            i = skipWs(bytes, i, end)
            if (i >= end) break
            when {
                bytes[i] == '<'.code.toByte() && bytes.getOrElse(i + 1) { 0.toByte() } == '<'.code.toByte() -> {
                    var depth = 1
                    var k = i + 2
                    while (k < end && depth > 0) {
                        if (bytes[k] == '<'.code.toByte() && bytes.getOrElse(k + 1) { 0.toByte() } == '<'.code.toByte()) depth++
                        else if (bytes[k] == '>'.code.toByte() && bytes.getOrElse(k + 1) { 0.toByte() } == '>'.code.toByte()) depth--
                        k++
                    }
                    out[key] = bytes.copyOfRange(i, k)
                    i = k
                }
                bytes[i] == '('.code.toByte() -> {
                    var depth = 1
                    var k = i + 1
                    while (k < end && depth > 0) {
                        if (bytes[k] == '\\'.code.toByte()) { k += 2; continue }
                        if (bytes[k] == '('.code.toByte()) depth++
                        else if (bytes[k] == ')'.code.toByte()) depth--
                        k++
                    }
                    out[key] = bytes.copyOfRange(i, k)
                    i = k
                }
                bytes[i] == '['.code.toByte() -> {
                    var depth = 1
                    var k = i + 1
                    while (k < end && depth > 0) {
                        if (bytes[k] == '['.code.toByte()) depth++
                        else if (bytes[k] == ']'.code.toByte()) depth--
                        k++
                    }
                    out[key] = bytes.copyOfRange(i, k)
                    i = k
                }
                else -> {
                    val parts = mutableListOf<String>()
                    while (i < end) {
                        i = skipWs(bytes, i, end)
                        if (i >= end) break
                        if (bytes[i] == '>'.code.toByte() && bytes.getOrElse(i + 1) { 0.toByte() } == '>'.code.toByte()) break
                        if (bytes[i] == '/'.code.toByte() && parts.isNotEmpty()) break
                        val j = readAtom(bytes, i, end)
                        parts.add(String(bytes, i, j - i, ISO))
                        i = j
                        if (parts.last().startsWith("/")) break
                        if (parts.size >= 3) break
                    }
                    out[key] = parts.joinToString(" ").toByteArray(ISO)
                }
            }
        }
        return out
    }

    /** 抽取对象流字节（"stream" 之后到 "endstream" 之前）。 */
    private fun streamBytesOf(bytes: ByteArray): ByteArray? {
        val s = bytes.indexOfBytes("stream".toByteArray(ISO))
        if (s < 0) return null
        var p = s + 6
        if (bytes.getOrElse(p) { 0.toByte() } == '\r'.code.toByte()) p++
        if (bytes.getOrElse(p) { 0.toByte() } == '\n'.code.toByte()) p++
        val e = bytes.indexOfBytes("endstream".toByteArray(ISO), p)
        return if (e >= p) bytes.copyOfRange(p, e) else null
    }

    private fun tryInflate(raw: ByteArray): ByteArray? = try {
        val inf = Inflater(false)
        inf.setInput(raw)
        val buf = ByteArray(raw.size * 4 + 1024)
        var n = inf.inflate(buf)
        if (!inf.finished()) {
            // 还不够：扩容重试
            val big = ByteArray(raw.size * 16 + 8192)
            inf.reset(); inf.setInput(raw); n = inf.inflate(big)
            big.copyOfRange(0, n)
        } else {
            buf.copyOfRange(0, n)
        }
    } catch (e: Exception) {
        Log.w(TAG, "inflate failed: ${e.message}")
        null
    }

    // ---------- 全文件扫描兜底 ----------

    private fun scanWholeFile() {
        val re = Regex("""(\d+)\s+(\d+)\s+obj""").findAll(String(data, ISO))
        for (m in re) {
            val num = m.groupValues[1].toInt()
            // 定位 "obj" 起点
            val at = data.indexOfBytes(m.value.toByteArray(ISO))
            if (at >= 0) objStart[num] = at
        }
        // 末一个 trailer 的 Root
        val tl = Regex("""trailer\s*<<(.*?)>>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(String(data, ISO)).lastOrNull()
        tl?.let { applyTrailer(parseInlineDict(it.groupValues[1].toByteArray(ISO))) }
        if (rootRef == null) {
            val r = Regex("""/Root\s+(\d+\s+\d+\s+R)""").find(String(data, ISO))
            rootRef = r?.groupValues?.getOrNull(1)
        }
    }

    private fun parseInlineDict(b: ByteArray): Map<String, ByteArray> {
        // 简易单行字典（trailer 通常简单）：复用 parseDict 从首 << 起
        val ds = b.indexOf('<'.code.toByte())
        if (ds < 0) return emptyMap()
        return parseDict(b, ds, b.size) ?: emptyMap()
    }

    // ---------- 小工具 ----------

    private fun deref(ref: String): Int? {
        val m = Regex("""(\d+)\s+\d+\s+R""").find(ref) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun findRefs(b: ByteArray): List<Int> {
        return Regex("""(\d+)\s+\d+\s+R""").findAll(String(b, ISO))
            .mapNotNull { deref(it.value) }.toList()
    }

    private fun skipWs(b: ByteArray, i: Int, end: Int): Int {
        var x = i
        while (x < end && b[x] in WHITESPACE) x++
        return x
    }

    private fun readAtom(b: ByteArray, i: Int, end: Int): Int {
        var j = i
        while (j < end && b[j] !in ATOM_STOP) j++
        return j
    }

    private fun readLine(i: Int): String {
        val e = data.indexOfByte('\n'.code.toByte(), i)
        return if (e < 0) String(data, i, data.size - i, ISO) else String(data, i, e - i, ISO)
    }

    private fun lineEnd(i: Int): Int {
        val e = data.indexOfByte('\n'.code.toByte(), i)
        return if (e < 0) data.size else e + 1
    }

    private fun skipLine(i: Int): Int = lineEnd(i)

    /** 按空白切分字符串（替代 ByteArray 上不存在的 String.split）。 */
    private fun String.splitWs(): List<String> =
        this.split(Regex("""\s+""")).filter { it.isNotEmpty() }

    private fun parseIntArray(b: ByteArray): IntArray {
        return Regex("""-?\d+""").findAll(String(b, ISO)).map { it.value.toInt() }.toList().toIntArray()
    }

    private fun parseIndexed(b: ByteArray): List<Pair<Int, Int>> {
        val nums = Regex("""\d+""").findAll(String(b, ISO)).map { it.value.toInt() }.toList()
        return nums.chunked(2).map { it[0] to it[1] }
    }

    private fun parseRect(b: ByteArray): Pair<Int, Int>? {
        val nums = Regex("""-?\d+(?:\.\d+)?""").findAll(String(b, ISO)).map { it.value.toDouble() }.toList()
        if (nums.size < 4) return null
        val w = (nums[2] - nums[0]).toInt().coerceAtLeast(1)
        val h = (nums[3] - nums[1]).toInt().coerceAtLeast(1)
        return w to h
    }

    private val WHITESPACE = byteArrayOf(' '.code.toByte(), '\t'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), 0x0c, 0x00)
    private val VALUE_STOP = byteArrayOf(' '.code.toByte(), '\t'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '/'.code.toByte(), '<'.code.toByte(), '>'.code.toByte(), '['.code.toByte(), ']'.code.toByte(), '('.code.toByte(), ')'.code.toByte())
    private val ATOM_STOP = byteArrayOf(' '.code.toByte(), '\t'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '/'.code.toByte(), '<'.code.toByte(), '>'.code.toByte(), '['.code.toByte(), ']'.code.toByte(), '('.code.toByte(), ')'.code.toByte())

    private val ISO = Charsets.ISO_8859_1

    private fun ByteArray.firstInt(): Int? {
        return Regex("""-?\d+""").find(String(this, ISO))?.value?.toIntOrNull()
    }

    companion object {
        private const val TAG = "KomihoPdfParser"
    }
}

/** 在字节数组中查找子串（独立命名，避免遮蔽标准库 ByteArray.indexOf(Byte)）。 */
private fun ByteArray.indexOfBytes(sub: ByteArray, from: Int = 0): Int {
    if (sub.isEmpty()) return from.coerceAtMost(size)
    val hi = (size - sub.size).coerceAtLeast(from)
    var i = from.coerceAtLeast(0)
    while (i <= hi) {
        var ok = true
        for (j in sub.indices) {
            if (this[i + j] != sub[j]) { ok = false; break }
        }
        if (ok) return i
        i++
    }
    return -1
}

/** 从 from 起查找单个字节（标准库 ByteArray.indexOf(Byte) 不支持 fromIndex）。 */
private fun ByteArray.indexOfByte(element: Byte, from: Int): Int {
    for (i in from.coerceAtLeast(0) until size) {
        if (this[i] == element) return i
    }
    return -1
}

/** 从 from 位置向前查找子串最后一次出现。 */
private fun ByteArray.lastIndexOfBytes(sub: ByteArray, from: Int = size): Int {
    if (sub.isEmpty()) return from.coerceAtMost(size)
    val start = (from - sub.size + 1).coerceAtLeast(0)
    val hi = (size - sub.size).coerceAtLeast(0)
    var i = start.coerceAtMost(hi)
    while (i >= 0) {
        var ok = true
        for (j in sub.indices) {
            if (this[i + j] != sub[j]) { ok = false; break }
        }
        if (ok) return i
        i--
    }
    return -1
}

/** 判断从 offset 起是否以 sub 开头。 */
private fun ByteArray.startsWith(sub: ByteArray, offset: Int = 0): Boolean {
    if (offset < 0 || offset + sub.size > size) return false
    for (j in sub.indices) if (this[offset + j] != sub[j]) return false
    return true
}

data class PdfImage(
    val objNum: Int,
    val width: Int,
    val height: Int,
    val filter: String?,
)
