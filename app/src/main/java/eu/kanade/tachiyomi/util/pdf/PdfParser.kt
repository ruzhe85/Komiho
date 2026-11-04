package eu.kanade.tachiyomi.util.pdf

import android.util.Log
import eu.kanade.tachiyomi.diagnostic.DiagLog
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
 * 内存说明：**不整份读入内存**。所有访问都经 [PdfSource]（mmap + 分块扫描），
 * 常驻内存只与「当前访问的对象/流」有关，与文件总大小无关。
 */
class PdfParser(path: String) {

    /**
     * Komiho (2026-11-04): 改成**按需随机读取**（mmap，堆外）。
     *
     * 以前这里是 `File.readBytes()`：整份文件进堆并整章常驻 —— 352MB 的画集就是 352MB 常驻，
     * 再叠加渲染位图必然 OOM，而且 2GB 的扫描本根本没有那么多堆可用。现在内存只随「当前访问
     * 的对象/流」增长，与文件总大小无关。
     */
    private val src = PdfSource(path)

    /** objnum -> 对象在文件中的起始偏移（"N G obj" 起点） */
    private val objStart = mutableMapOf<Int, Int>()
    /** objnum -> 在对象流(ObjStm)内的原始字节（压缩存储对象） */
    private val objStmInner = mutableMapOf<Int, ByteArray>()
    /** objnum -> 该对象是否来自对象流 */
    private val fromObjStm = mutableSetOf<Int>()

    private var rootRef: String? = null
    private var encryptRef: String? = null
    private val pageList = mutableListOf<Int>()

    /** 页对象号 → 该页生效的 /Resources 原始值（页字典优先，缺省取父 /Pages 继承的）。 */
    private val pageResources = mutableMapOf<Int, ByteArray>()

    val pageCount: Int get() = pageList.size
    fun isEncrypted(): Boolean = encryptRef != null

    /**
     * Komiho: `/Encrypt` 字典的 `/Filter` —— `Standard` 是密码型加密；`Adobe.PubSec` 之类是
     * 证书型 DRM（要证书私钥，没有可输入的密码）。读不到返回 null，调用方按密码型处理（旧行为）。
     *
     * 刻意做成延迟解析：trailer 是在 xref 阶段逐个应用的，那时对象表可能还没建全（对象流里的
     * 对象尚未展开）；本函数只在解析完成后被调用，此时 deref 才可靠。
     */
    fun encryptFilter(): String? {
        val ref = encryptRef ?: return null
        val num = deref(ref) ?: return null
        val dict = dictOf(num) ?: return null
        val raw = dict["/Filter"] ?: return null
        return String(raw, ISO).trim().trimStart('/').trim().ifBlank { null }
    }

    /** 解析是否成功完成（失败时降级到系统渲染兜底，而非抛异常）。 */
    var parseOk = true
        private set

    // Komiho (2026-11-04): 解析入口 `init` 必须放在**类体最后**（见文件末尾）。
    //
    // Kotlin 按声明顺序初始化：属性初始化器与 `init` 块混排执行。这里原先写在类中部，而
    // `ISO` / `WHITESPACE` / `loadedObjStm` 等 val 声明在类尾 —— 于是 parse() 执行时它们还是
    // null，`findStartXref` 直接抛 NullPointerException（message 为 null），对外表现就是
    // `PDF probe: parser=false pages=0`，所有 PDF 都降级成整本渲染。

    // ---------- 入口 ----------

    private fun parse() {
        DiagLog.d(TAG, "parse begin: size=${src.size}")
        val sx = runCatching { findStartXref() }
            .onFailure { DiagLog.w(TAG, "findStartXref failed: ${it.message}") }
            .getOrDefault(-1)
        DiagLog.d(TAG, "startxref=$sx")
        if (sx >= 0) {
            runCatching { parseXref(sx) }
                .onFailure { DiagLog.w(TAG, "xref parse failed: ${it.message}") }
        }

        // 第一轮：直接用 xref 结果解析页树。任何异常都只记日志，不中断。
        resolveAndCollect()

        // Komiho (2026-11-03): 拿不到任何页面就**整表扫描重建**，再用重建结果解析一遍。
        //
        // 典型来源是用工具「解锁 / 精简」过的 PDF：xref 流被写成 `/Length 0`（或偏移全错），
        // 于是 xref 里的对象偏移全是垃圾 —— 拿它解析页树要么抛越界、要么 0 页，PDFium 那边更是
        // 直接报「需要密码」，而文件里既没有 /Encrypt、页面和 JPEG 也全都完好。
        // 判据用「有没有解析出页面」而不是「objStart 空不空」：垃圾 xref 同样会让 objStart 非空，
        // 之前就是因为这个判据太弱，重建根本没被触发（日志里连一行都没有）。
        // 便宜的一步先试：xref 已经读出对象表、只缺 /Root（xref 流文档常见：/Root 在 xref 流字典里，
        // 而字典解析偶尔拿不到）。扫一遍 /Root 再试即可，不必整本重建对象表 ——
        // 205MB 的扫描本正是这种情况（`objs=869, root=null`），整本重建反而是最贵的一步。
        if (pageList.isEmpty() && objStart.isNotEmpty() && rootRef == null) {
            val recovered = scanLastRef("/Root")
            if (recovered != null) {
                rootRef = recovered
                DiagLog.d(TAG, "root recovered by scan: $rootRef, retry pages")
                resolveAndCollect()
            }
        }

        // 页树没走全（或一页都没有）也要重建：205MB 那本的 xref 只读出 869 个对象，
        // 遍历到缺失的分支就静默停下 —— 日志里 `pages=3`、实际 166 页，就是这种情况。
        if (pageList.isEmpty() || pageTreeIncomplete) {
            DiagLog.w(
                TAG,
                "page tree incomplete (pages=${pageList.size}/$expectedPageCount, objs=${objStart.size}), rebuild by scanning",
            )
            objStart.clear()
            objStmInner.clear()
            fromObjStm.clear()
            loadedObjStm.clear()
            pageList.clear()
            pageResources.clear()
            pagesRootRef = null
            trailerPrev = null
            pageTreeIncomplete = false
            expectedPageCount = 0
            runCatching { scanWholeFile() }
                .onFailure { DiagLog.w(TAG, "scan rebuild failed: ${it.message}") }
            DiagLog.d(TAG, "scan rebuild: objs=${objStart.size} root=$rootRef, retry pages")
            resolveAndCollect()
        }

        // 仍不完整：放宽扫描（不再要求行首）**只补缺失对象**，不覆盖已有偏移，再试一次。
        // 有些 PDF 的对象头不在行首（跟在 `>>` 或 `stream...endstream` 之后），严格模式会漏掉。
        if (pageTreeIncomplete) {
            DiagLog.w(
                TAG,
                "still incomplete (pages=${pageList.size}/$expectedPageCount), relaxed scan to fill missing objects",
            )
            indexObjectOffsets(requireLineStart = false, onlyMissing = true)
            pageList.clear()
            pageResources.clear()
            pagesRootRef = null
            pageTreeIncomplete = false
            expectedPageCount = 0
            resolveAndCollect()
            DiagLog.d(TAG, "relaxed scan: objs=${objStart.size} pages=${pageList.size}/$expectedPageCount")
        }

        if (pageList.isEmpty() || pageTreeIncomplete) {
            // 拿不到完整页树：交给上层回落系统渲染（页数才对，只是慢；不是「加密」，别弹密码框）。
            parseOk = false
            DiagLog.w(
                TAG,
                "PDF parse: pages=${pageList.size}/$expectedPageCount incomplete after rebuild, fallback to render",
            )
        } else {
            DiagLog.d(TAG, "PDF parse ok: pages=${pageList.size} objs=${objStart.size}")
        }
    }

    /** 解析页树；xref 结果里有垃圾偏移时 `objectBytesAt` 会抛越界，这里吞掉并记日志。 */
    private fun resolveAndCollect() {
        runCatching {
            resolveRoot()
            collectPages()
        }.onFailure { DiagLog.w(TAG, "collect pages failed: ${it.message}") }
    }

    private fun findStartXref(): Int {
        // 最后一个 startxref 之后是 EOF 偏移
        var idx = src.lastIndexOf("startxref".toByteArray(ISO))
        if (idx < 0) return -1
        val len = (src.size - idx).coerceAtMost(64)
        val tail = String(src.read(idx, len), ISO)
        val m = Regex("""startxref\s+(\d+)""").find(tail) ?: return -1
        return m.groupValues[1].toIntOrNull() ?: -1
    }

    private fun parseXref(offset: Int) {
        if (src.startsWith("xref".toByteArray(ISO), offset)) {
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
            if (src.startsWith("xref".toByteArray(ISO), p)) {
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
        while (i < src.size) {
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
                // 继续处理后续 subsection（如 "0 1612" 之外的分段）
                continue
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
        val objStartPos = src.lastIndexOf("obj".toByteArray(ISO), offset + 16)
        if (objStartPos < 0) return
        val bytes = objectBytesAt(objStartPos) ?: return
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
                        for (b in 0 until w[wi]) {
                            // 流被截断/长度为 0 时直接放弃，别靠越界异常（那会把整条 xref 链断掉）。
                            if (p >= dec.size) return
                            acc = (acc shl 8) or (dec[p].toInt() and 0xFF)
                            p++
                        }
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

    /**
     * 已展开的对象流（按**流对象号**记账）。
     *
     * Komiho (2026-11-03): 之前这里用 `fromObjStm.contains(stmObjNum)` 当幂等判据，但那集合里装的是
     * 「流内的对象号」，几乎不会包含流自己 —— 于是一份对象全塞在对象流里的现代 PDF，会被
     * **每个对象都重新 inflate 一遍整条流**（xref 里 N 条 type-2 项 = N 次解压），页数/对象数越多
     * 越慢，打开一本书就能吃掉好几秒。这里按流对象号单独记一笔，一条流只解一次。
     */
    private val loadedObjStm = mutableSetOf<Int>()

    private fun loadObjStm(stmObjNum: Int) {
        if (stmObjNum in loadedObjStm) return
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
        // 只在真正展开成功后记账：中途 return（偏移未知等）时下次还能重试。
        loadedObjStm.add(stmObjNum)
    }

    private fun applyTrailer(dict: Map<String, ByteArray>) {
        dict["/Root"]?.let { rootRef = String(it, ISO) }
        dict["/Encrypt"]?.let { encryptRef = String(it, ISO) }
        trailerPrev = dict["/Prev"]?.firstInt()
    }

    private fun parseTrailerAt(i: Int): Map<String, ByteArray> {
        val td = src.indexOf(DICT_OPEN, i)
        if (td < 0) return emptyMap()
        // 用嵌套感知的 dictEnd，而不是 indexOf(">>")：trailer 里可能出现内联字典
        // （`/Encrypt << ... >>`、`/ID [...]` 等），简单找第一个 ">>" 会截断。
        val slice = src.read(td, 64 * 1024)
        if (slice.isEmpty()) return emptyMap()
        return parseDict(slice, 0, dictEnd(slice)) ?: emptyMap()
    }

    private fun resolveRoot() {
        if (rootRef == null) return
        val rootNum = deref(rootRef!!) ?: return
        val dict = dictOf(rootNum) ?: return
        pagesRootRef = dict["/Pages"]?.let { String(it, ISO) }
    }

    private var pagesRootRef: String? = null

    /** 根 `/Pages` 声明的总页数（`/Count`），用来判断页树是否遍历完整。 */
    private var expectedPageCount: Int = 0

    /**
     * 页树是否「不完整」—— 遍历时遇到解不出的对象，或结果少于 `/Count`。
     *
     * Komiho (2026-11-04): 205MB 那本 xref 只读出 869 个对象，页树引用到其中缺失的对象时
     * `dictOf` 返回 null，遍历会**静默停下**：日志显示 `pages=3`，实际有 166 页。
     * 现在把这种情况显式记下来，交给上层重建对象表后重试。
     */
    private var pageTreeIncomplete: Boolean = false

    private fun collectPages() {
        val ref = pagesRootRef ?: return
        val num = deref(ref) ?: return
        dictOf(num)?.get("/Count")?.firstInt()?.let { if (it in 1..1_000_000) expectedPageCount = it }
        walkPages(num, pageList)
        if (expectedPageCount > 0 && pageList.size < expectedPageCount) {
            pageTreeIncomplete = true
        }
    }

    /**
     * Komiho (2026-11-03): 顺带把**可继承的 `/Resources`** 传下去。
     *
     * `/Resources` 是页树的可继承属性：大量 PDF（WPS/Word 导出、部分 img2pdf 输出）把资源挂在
     * 父 `/Pages` 上，页字典里根本没有这个键。以前只读页字典 → 拿不到 `/XObject` → 整本回落
     * 渲染，明明是整页 JPEG 也拆不出来。
     */
    private fun walkPages(
        objNum: Int,
        out: MutableList<Int>,
        inheritedResources: ByteArray? = null,
        depth: Int = 0,
    ) {
        // 页树理论上只有几层；循环引用（损坏文件）会把这里变成无限递归 → StackOverflow。
        if (depth > MAX_PAGE_TREE_DEPTH) {
            pageTreeIncomplete = true
            return
        }
        val dict = dictOf(objNum) ?: run {
            // 对象表里没有这个分支 → 页树残缺，交给上层重建后重试。
            pageTreeIncomplete = true
            return
        }
        val resources = dict["/Resources"] ?: inheritedResources
        when (val t = dict["/Type"]?.let { String(it, ISO) }) {
            "/Pages" -> {
                val kids = dict["/Kids"]?.let { findRefs(it) } ?: emptyList()
                for (k in kids) walkPages(k, out, resources, depth + 1)
            }
            "/Page" -> {
                out.add(objNum)
                resources?.let { pageResources[objNum] = it }
            }
        }
    }

    // ---------- 对外查询 ----------

    /** 某页的最佳内嵌图（面积最大）。无图（矢量）返回 null 交由渲染兜底。 */
    fun bestImageForPage(pageIndex: Int): PdfImage? {
        if (pageIndex !in pageList.indices) return null
        val pageNum = pageList[pageIndex]
        val pageDict = dictOf(pageNum) ?: return null
        // Komiho: /Resources 可继承 —— 页字典没有时用父 /Pages 上继承下来的那份。
        val resRaw = pageDict["/Resources"] ?: pageResources[pageNum]
        if (resRaw == null) {
            // 退化情况：XObject 直接挂在页字典上（极少见）
            val direct = pageDict["/XObject"] ?: return null
            val xdict = resolveDict(direct) ?: return null
            return bestImageInXObjectDict(xdict, mutableSetOf(), 0, ownerRes = null)
        }
        val resDict = resolveDict(resRaw) ?: return null
        return bestImageInResources(resDict, visited = mutableSetOf(), depth = 0)
    }

    private fun bestImageInResources(
        resDict: Map<String, ByteArray>,
        visited: MutableSet<Int>,
        depth: Int,
    ): PdfImage? {
        if (depth > MAX_XOBJECT_DEPTH) return null
        val xobjRaw = resDict["/XObject"] ?: return null
        val xdict = resolveDict(xobjRaw) ?: return null
        return bestImageInXObjectDict(xdict, visited, depth, ownerRes = resDict)
    }

    /**
     * 在一个 `/XObject` 名字表里挑面积最大的图。
     *
     * Komiho (2026-11-03): `/Subtype /Form` 会递归进去 —— 「整页一张 JPEG」的 PDF 很多会把图包在
     * Form XObject 里（`/Fm0 → /Resources → /XObject → /Im0`），只查页级资源就会漏掉，
     * 于是明明能直通也退回渲染。
     */
    private fun bestImageInXObjectDict(
        xdict: Map<String, ByteArray>,
        visited: MutableSet<Int>,
        depth: Int,
        /** 当前这一层的资源字典：Form 自己没带 /Resources 时沿用它。 */
        ownerRes: Map<String, ByteArray>?,
    ): PdfImage? {
        if (depth > MAX_XOBJECT_DEPTH) return null

        var best: PdfImage? = null
        for ((_, refBytes) in xdict) {
            val ref = String(refBytes, ISO)
            if (!ref.contains("R")) continue
            val num = deref(ref) ?: continue
            // 同一对象只解析一次，防 Form XObject 互相引用的环。
            if (!visited.add(num)) continue
            val idict = dictOf(num) ?: continue
            when (String(idict["/Subtype"] ?: continue, ISO)) {
                "/Image" -> {
                    val w = idict["/Width"]?.firstInt() ?: 0
                    val h = idict["/Height"]?.firstInt() ?: 0
                    val filter = idict["/Filter"]?.let { String(it, ISO) }?.trim()
                    val img = PdfImage(num, w, h, normalizeFilter(filter))
                    val current = best
                    if (current == null || w * h > current.width * current.height) best = img
                }
                "/Form" -> {
                    val formRes = idict["/Resources"]?.let { resolveDict(it) }
                        ?: ownerRes
                        ?: continue
                    val nested = bestImageInResources(formRes, visited, depth + 1) ?: continue
                    val current = best
                    if (current == null || nested.width * nested.height > current.width * current.height) {
                        best = nested
                    }
                }
            }
        }
        return best
    }

    /** 把「内联字典 `<<…>>`」或「`N G R` 引用」解析成字典；两种都不是就返回 null。 */
    private fun resolveDict(raw: ByteArray): Map<String, ByteArray>? {
        val s = String(raw, ISO).trim()
        return if (s.startsWith("<<")) {
            parseDict(raw, raw.indexOf('<'.code.toByte()), dictEnd(raw))
        } else {
            deref(s)?.let { dictOf(it) }
        }
    }

    /** Form XObject 递归上限（正常文档一层就到底，三层足够容错）。 */
    private val MAX_XOBJECT_DEPTH = 3

    private fun normalizeFilter(f: String?): String? {
        if (f == null) return null
        // 可能含多滤镜（数组）或带方括号；取第一个
        val cleaned = f.trim().removeSurrounding("[", "]").trim()
        val first = cleaned.splitWs().firstOrNull() ?: cleaned
        // PDF name 对象带前导斜杠（如 /DCTDecode），取裸名以匹配 openStream 的比较。
        return if (first.startsWith("/")) first.substring(1) else first
    }

    /** 抽取内嵌图的原始流字节（DCTDecode 即 JPEG，可交解码器直解）。 */
    /**
     * 取对象流的原始字节。
     *
     * Komiho (2026-11-04): 改成「先读对象头定位 `/Length`，再精确读那一段」—— 图像流动辄几 MB，
     * 以前是先 `objectBytesAt`（扫 endobj）+ `streamBytesOf`（再找 endstream）把整段复制两遍，
     * 现在只多读一个 64KB 的头部窗口。`/Length` 不是直接数字时退回旧路径。
     */
    fun rawStreamBytes(objNum: Int): ByteArray? {
        objStmInner[objNum]?.let { return streamBytesOf(it) }
        val start = objStart[objNum] ?: return null
        val head = src.read(start, OBJECT_HEAD_BYTES)
        if (head.isEmpty()) return null
        val streamIdx = head.indexOfBytes(STREAM_KEY)
        if (streamIdx < 0) {
            return objectBytesAt(start)?.let { streamBytesOf(it) }
        }
        var p = streamIdx + STREAM_KEY.size
        if (head.getOrElse(p) { 0 } == '\r'.code.toByte()) p++
        if (head.getOrElse(p) { 0 } == '\n'.code.toByte()) p++
        // 注意 `/Length` 可能是间接引用（`/Length 12 0 R`）：正则必须排除这种，否则会把
        // 对象号 12 当成长度，读出 12 字节的“图片”直接解不出来。
        val length = Regex("""/Length\s+(\d+)(?!\s+\d+\s+R)""")
            .find(String(head, 0, streamIdx, ISO))
            ?.groupValues?.get(1)?.toIntOrNull()
        if (length != null && length > 0 && start + p + length <= src.size) {
            // 校验：数据末尾紧跟 endstream 才认为 /Length 可信（有些工具写错 /Length）。
            val tail = src.read(start + p + length, 16)
            if (tail.indexOfBytes(ENDSTREAM_KEY) >= 0) {
                return src.read(start + p, length)
            }
        }
        return objectBytesAt(start)?.let { streamBytesOf(it) }
    }

    /** 只读流的前 [count] 字节（判断「流是明文 JPEG 还是密文」用，不必把整页图读进来）。 */
    fun rawStreamHead(objNum: Int, count: Int = 16): ByteArray? {
        objStmInner[objNum]?.let { bytes ->
            val stream = streamBytesOf(bytes) ?: return null
            return stream.copyOfRange(0, minOf(count, stream.size))
        }
        val start = objStart[objNum] ?: return null
        val head = src.read(start, OBJECT_HEAD_BYTES)
        if (head.isEmpty()) return null
        val streamIdx = head.indexOfBytes(STREAM_KEY)
        if (streamIdx < 0) return null
        var p = streamIdx + STREAM_KEY.size
        if (head.getOrElse(p) { 0 } == '\r'.code.toByte()) p++
        if (head.getOrElse(p) { 0 } == '\n'.code.toByte()) p++
        return src.read(start + p, count)
    }

    fun pageMediaBox(pageIndex: Int): Pair<Int, Int>? {
        if (pageIndex !in pageList.indices) return null
        val pageNum = pageList[pageIndex]
        val dict = dictOf(pageNum) ?: return null
        val mb = dict["/MediaBox"]?.let { parseRect(it) }
        return mb
    }

    // ---------- 对象模型 ----------

    /** 取对象字节。偏移非法（xref 是垃圾时会出现）返回 null，绝不抛越界给上层。 */
    /**
     * 读一个对象的字节（到 `endobj` 为止）。
     *
     * Komiho (2026-11-04): 先读 **32KB** 窗口找 `endobj`，找不到再 ×4 扩张。以前直接用
     * `indexOf("endobj")`，每次都要读 4MB 块 —— 打开时页树每页至少查三次对象字典，
     * 536 页就是 1600 次 × 4MB 的无谓 I/O。
     */
    private fun objectBytesAt(offset: Int): ByteArray? {
        if (offset < 0 || offset >= src.size) return null
        var window = 32 * 1024
        while (true) {
            val bytes = src.read(offset, window)
            if (bytes.isEmpty()) return null
            val e = bytes.indexOfBytes(ENDOBJ_KEY)
            if (e >= 0) return bytes.copyOfRange(0, e)
            if (offset + bytes.size >= src.size) return bytes
            if (window >= MAX_OBJECT_WINDOW) {
                // 超大对象（含大图流）：退回「找 endobj + 全段读」。
                val idx = src.indexOf(ENDOBJ_KEY, offset)
                val end = if (idx >= 0) idx else src.size
                if (end <= offset) return null
                return runCatching { src.read(offset, end - offset) }.getOrNull()
            }
            window *= 4
        }
    }

    private fun dictOf(objNum: Int): Map<String, ByteArray>? {
        val bytes = if (fromObjStm.contains(objNum)) {
            objStmInner[objNum] ?: return null
        } else {
            objStart[objNum]?.let { objectBytesAt(it) } ?: return null
        }
        val ds = firstDictStart(bytes)
        if (ds < 0) return null
        // 必须用嵌套感知的 dictEnd：Catalog/Pages 等常含嵌套 <<>>，
        // 不能用 indexOf('>')（会在第一个 >> 处截断，丢失 /Pages 等键）。
        val de = dictEnd(bytes)
        return parseDict(bytes, ds, de)
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

    private val O_CHAR = 'o'.code.toByte()
    private val B_CHAR = 'b'.code.toByte()
    private val J_CHAR = 'j'.code.toByte()
    private val R_CHAR = 'R'.code.toByte()
    /** 扫描块大小与块间重叠：重叠要盖住「N G obj」这串头（≤ 32 字节，取 64 有富余）。 */
    private val SCAN_CHUNK = 4 * 1024 * 1024
    private val SCAN_OVERLAP = 64

    /** 读对象头用的小窗口：字典 + `stream` 关键字基本都在这个范围内。 */
    private val OBJECT_HEAD_BYTES = 64 * 1024
    private val STREAM_KEY = "stream".toByteArray(Charsets.ISO_8859_1)
    private val ENDOBJ_KEY = "endobj".toByteArray(Charsets.ISO_8859_1)
    private val ENDSTREAM_KEY = "endstream".toByteArray(Charsets.ISO_8859_1)
    /** `objectBytesAt` 的窗口扩张上限：到这儿还找不到 endobj 就退回全段扫描。 */
    private val MAX_OBJECT_WINDOW = 8 * 1024 * 1024

    /** 页树最大递归深度（正常文档 2–3 层；防损坏文件循环引用）。 */
    private val MAX_PAGE_TREE_DEPTH = 64

    private val TRAILER_KEY = "trailer".toByteArray(Charsets.ISO_8859_1)
    private val DICT_OPEN = "<<".toByteArray(Charsets.ISO_8859_1)

    /**
     * Komiho (2026-11-04): 改成**纯字节扫描**，不再把整份文件转成 String。
     *
     * 之前 `String(data, ISO)` + 正则有两个硬伤（新日志里都能看到）：
     * - 内存翻倍：UTF-16 字符串是 2 字节/字符，205MB 的 PDF 要分配 431MB，直接
     *   `Failed to allocate a 431100608 byte allocation ... until OOM`，大文件重建必然失败；
     * - 慢：16MB 的文件跑一次正则要 3 秒（`PDF probe ... 耗时=3109ms`）。
     *
     * 改成按字节线性扫后内存是 O(1)，16MB 应回到百毫秒级。
     */
    private fun scanWholeFile() {
        indexObjectOffsets()
        applyTrailerFromScan()
        if (rootRef == null) {
            // 没有 trailer 关键字（xref 流文档就是这样）：/Root 在 xref 流字典里，取最后一个。
            rootRef = scanLastRef("/Root")
        }
    }

    /**
     * 扫 `N G obj` 记录对象偏移。
     *
     * @param requireLineStart true = 只认「换行/文件首」之后的对象头（第一批首选，抗二进制流假匹配）；
     *                         false = 宽松模式，任何位置的对象头都收。
     * @param onlyMissing true = 只补对象表里**没有**的号，绝不覆盖已有偏移（宽松补漏用）。
     */
    private fun indexObjectOffsets(requireLineStart: Boolean = true, onlyMissing: Boolean = false) {
        var base = 0
        while (base < src.size) {
            // 分块读 + 块间重叠：既保证跨块的对象头不会被切断，也避免逐字节调 src.get（那会把
            // 300MB 变成 3 亿次映射访问，慢到不可用）。
            val block = src.read(base, SCAN_CHUNK + SCAN_OVERLAP)
            if (block.isEmpty()) break
            var i = 0
            val last = block.size - 3
            while (i <= last) {
                if (block[i] != O_CHAR || block[i + 1] != B_CHAR || block[i + 2] != J_CHAR) {
                    i++
                    continue
                }
                parseObjectHeaderBefore(block, i, requireLineStart)?.let { (num, localStart) ->
                    val abs = base + localStart
                    if (!onlyMissing || !objStart.containsKey(num)) objStart[num] = abs
                }
                i += 3
            }
            if (block.size <= SCAN_OVERLAP) break
            base += block.size - SCAN_OVERLAP
        }
    }

    /**
     * 从 `obj` 关键字往前解析 `对象号 代数`，返回 (对象号, 对象号起始偏移)。
     *
     * 只接受「行首」出现的对象头（前一字节是换行或文件开头）：二进制流里恰好出现的同样字节串
     * 不是对象头，认了会把真偏移覆盖掉，页树就又解析不出来了。
     */
    private fun parseObjectHeaderBefore(
        block: ByteArray,
        keywordIdx: Int,
        requireLineStart: Boolean = true,
    ): Pair<Int, Int>? {
        var p = keywordIdx - 1
        if (p < 0 || !isWsByte(block[p])) return null
        while (p >= 0 && isWsByte(block[p])) p--
        val genEnd = p
        while (p >= 0 && isDigitByte(block[p])) p--
        if (p == genEnd) return null // 没读到代数
        if (p < 0 || !isWsByte(block[p])) return null
        while (p >= 0 && isWsByte(block[p])) p--
        val numEnd = p
        while (p >= 0 && isDigitByte(block[p])) p--
        if (p == numEnd) return null // 没读到对象号
        val numStart = p + 1
        if (requireLineStart && p >= 0) {
            val prev = block[p]
            if (prev != '\n'.code.toByte() && prev != '\r'.code.toByte()) return null
        }
        val num = String(block, numStart, numEnd - numStart + 1, ISO).toIntOrNull() ?: return null
        return num to numStart
    }

    /** 取最后一个 `trailer << … >>` 里的键（老式 xref 表文档才有）。 */
    private fun applyTrailerFromScan() {
        var lastDictStart = -1
        var from = 0
        while (true) {
            val idx = src.indexOf(TRAILER_KEY, from)
            if (idx < 0) break
            val ds = src.indexOf(DICT_OPEN, idx)
            if (ds >= 0 && ds - idx < 32) lastDictStart = ds
            from = idx + TRAILER_KEY.size
        }
        if (lastDictStart < 0) return
        val sub = src.read(lastDictStart, minOf(src.size, lastDictStart + 64 * 1024) - lastDictStart)
        val dict = parseDict(sub, 0, dictEnd(sub)) ?: return
        applyTrailer(dict)
    }

    /** 找 `keyword` 之后出现的**最后一个** `N G R` 引用。 */
    private fun scanLastRef(keyword: String): String? {
        val kw = keyword.toByteArray(ISO)
        var result: String? = null
        var from = 0
        while (true) {
            val idx = src.indexOf(kw, from)
            if (idx < 0) break
            readRefAfter(idx + kw.size)?.let { result = it }
            from = idx + kw.size
        }
        return result
    }

    /** 从 [start] 起读 `N G R`，成功返回 "N G R"。 */
    private fun readRefAfter(start: Int): String? {
        var p = start
        while (p < src.size && isWsByte(src.get(p))) p++
        val n1Start = p
        while (p < src.size && isDigitByte(src.get(p))) p++
        if (p == n1Start) return null
        val n1 = String(src.read(n1Start, p - n1Start), ISO)
        while (p < src.size && isWsByte(src.get(p))) p++
        val n2Start = p
        while (p < src.size && isDigitByte(src.get(p))) p++
        if (p == n2Start) return null
        val n2 = String(src.read(n2Start, p - n2Start), ISO)
        while (p < src.size && isWsByte(src.get(p))) p++
        if (p >= src.size || src.get(p) != R_CHAR) return null
        return "$n1 $n2 R"
    }

    private fun isWsByte(b: Byte): Boolean = b in WHITESPACE

    private fun isDigitByte(b: Byte): Boolean = b >= 0x30 && b <= 0x39

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

    /**
     * 读取一个「原子」：以 ATOM_STOP 为界。
     * 关键：以 `/` 开头的 name 值（如 `/Pages`，它是 `/Type` 的值）必须从 `/` 之后读完整段，
     * 否则只读到一个 `/` 会让 `/Type` 的值变成空/单斜杠，页树键名判定全部错乱。
     */
    private fun readAtom(b: ByteArray, i: Int, end: Int): Int {
        if (b[i] == '/'.code.toByte()) {
            var j = i + 1
            while (j < end && b[j] !in ATOM_STOP) j++
            return j
        }
        var j = i
        while (j < end && b[j] !in ATOM_STOP) j++
        return j
    }

    private fun readLine(i: Int): String {
        val e = lineEnd(i)
        return String(src.read(i, e - i), ISO)
    }

    /**
     * 行尾定位：兼容 `\n`、`\r`、`\r\n` 混合。真实 PDF 常混用 CRLF/LF，
     * 只认 `\n` 会导致 xref 表整段解析失败、objStart 为空、pageCount=0。
     */
    private fun lineEnd(i: Int): Int {
        var j = i
        while (j < src.size) {
            val c = src.get(j)
            if (c == '\n'.code.toByte() || c == '\r'.code.toByte()) {
                // \r\n 连发：跳过两个字节
                if (c == '\r'.code.toByte() && j + 1 < src.size && src.get(j + 1) == '\n'.code.toByte()) {
                    return j + 2
                }
                return j + 1
            }
            j++
        }
        return src.size
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

    /** 关闭底层文件句柄（mmap / file descriptor）—— 阅读器回收章节时调用，避免句柄泄漏。 */
    fun close() {
        runCatching { src.close() }
    }

    /**
     * 解析入口 —— **必须留在类体最后**。
     *
     * Kotlin 的属性初始化器与 init 块按书写顺序执行；`ISO`、`WHITESPACE`、`VALUE_STOP`、
     * `ATOM_STOP`、`loadedObjStm` 等 val 都声明在上面，只有把 init 放在它们之后，
     * parse() 里用到它们时才不会是 null。
     */
    init {
        try {
            parse()
        } catch (e: Exception) {
            // 走 DiagLog：这一步以前是 android.util.Log，失败原因根本不出现在导出日志里，
            // 「parser=false pages=0」永远看不到到底抛了什么。
            DiagLog.e(TAG, "parse failed: ${e.message}", e)
            parseOk = false
        }
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
