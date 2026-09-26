package eu.kanade.tachiyomi.util.storage

import mihon.core.common.archive.ArchiveHandle
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Wrapper over ZipFile to load files in epub format.
 *
 * Komiho: 参数类型是窄接口 [ArchiveHandle] 而非具体 ArchiveReader ——
 * 本地来源传 ArchiveReader，WebDAV / SMB 远程归档传 RemoteZipReader / CachingArchiveHandle。
 * 本类只用到 getInputStream，两条路径共用同一实现。
 *
 * 解析健壮性对齐 Koharia 的 EpubReader（2026-09）：
 *  - spine 直接引用 image/* 条目的图片型 EPUB（漫画/条漫/Divina）也能出页；
 *  - href 走 URLDecoder 解码（%20 / 中文 / 特殊字符不再拼错路径导致 NPE）；
 *  - 单个条目缺失时跳过该页而非整体崩溃。
 */
class EpubFile(private val reader: ArchiveHandle) : Closeable by reader {

    /**
     * Path separator used by this epub.
     */
    private val pathSeparator = getPathSeparator()

    /**
     * Returns an input stream for reading the contents of the specified zip file entry.
     */
    fun getInputStream(entryName: String): InputStream? {
        return reader.getInputStream(entryName)
    }

    /**
     * Returns the path of all the images found in the epub file.
     */
    fun getImagesFromPages(): List<String> {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val pages = getPagesFromDocument(doc)
        return getImagesFromPages(pages, ref)
    }

    /**
     * Returns the path to the package document.
     */
    fun getPackageHref(): String {
        val meta = getInputStream(resolveZipPath("META-INF", "container.xml"))
        if (meta != null) {
            val metaDoc = meta.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
            val path = metaDoc.getElementsByTag("rootfile").first()?.attr("full-path")
            if (path != null) {
                return path
            }
        }
        return resolveZipPath("OEBPS", "content.opf")
    }

    /**
     * Returns the package document where all the files are listed.
     */
    fun getPackageDocument(ref: String): Document {
        return getInputStream(ref)!!.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
    }

    /**
     * Returns all the pages from the epub, in spine reading order.
     * 与 Mihon 原版不同：spine 直接引用 image/* 条目的「图片型 EPUB」也纳入，
     * 否则这类书会被过滤成 0 页 → 上层报「无图片」→ 回退文件浏览器。
     */
    private fun getPagesFromDocument(document: Document): List<ManifestItem> {
        val manifest = getManifestFromDocument(document)
        return document.select("*|spine > *|itemref")
            .mapNotNull { itemRef -> manifest[itemRef.attr("idref")] }
    }

    private fun getManifestFromDocument(document: Document): Map<String, ManifestItem> {
        return document.select("*|manifest > *|item")
            .associate { item ->
                item.attr("id") to ManifestItem(
                    href = item.attr("href"),
                    mediaType = item.attr("media-type"),
                    properties = item.attr("properties"),
                )
            }
    }

    /**
     * Returns all the images contained in every page from the epub.
     * 保留宽松行为（收集页面里所有 img / svg:image，不去重到「必须恰好 1 张」），
     * 以不回归现有能正常打开的 EPUB；同时新增 image/* spine 直引页与 href 解码。
     */
    private fun getImagesFromPages(pages: List<ManifestItem>, packageHref: String): List<String> {
        if (pages.isEmpty()) return emptyList()
        val basePath = getParentDirectory(packageHref)
        val result = ArrayList<String>(pages.size)
        pages.forEach { page ->
            val entryPath = resolveZipPath(basePath, decodePathHref(page.href))
            // 图片型 EPUB：spine 项本身就是一整页图，直接采用。
            if (page.mediaType.startsWith("image/", ignoreCase = true)) {
                result += entryPath
                return@forEach
            }

            val document = getInputStream(entryPath)?.use { Jsoup.parse(it, null, "") } ?: return@forEach
            val imageBasePath = getParentDirectory(entryPath)
            val imagePaths = buildList {
                document.allElements.forEach {
                    when (it.tagName()) {
                        "img" -> it.attr("src").ifBlank { null }?.let(::add)
                        "image" -> it.attr("xlink:href").ifBlank { it.attr("href") }.ifBlank { null }?.let(::add)
                    }
                }
            }
                .map { resolveZipPath(imageBasePath, decodePathHref(it)) }
                .distinct()
            result += imagePaths
        }
        return result.distinct()
    }

    /** 解码百分号转义，且不让字面 '+' 被当成空格（EPUB href 常见）。 */
    private fun decodePathHref(href: String): String {
        return URLDecoder.decode(href.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }

    /**
     * Returns the path separator used by the epub file.
     */
    private fun getPathSeparator(): String {
        val meta = getInputStream("META-INF\\container.xml")
        return if (meta != null) {
            meta.close()
            "\\"
        } else {
            "/"
        }
    }

    /**
     * Resolves a zip path from base and relative components and a path separator.
     */
    private fun resolveZipPath(basePath: String, relativePath: String): String {
        if (relativePath.startsWith(pathSeparator)) {
            // Path is absolute, so return as-is.
            return relativePath
        }

        var fixedBasePath = basePath.replace(pathSeparator, File.separator)
        if (!fixedBasePath.startsWith(File.separator)) {
            fixedBasePath = "${File.separator}$fixedBasePath"
        }

        val fixedRelativePath = relativePath.replace(pathSeparator, File.separator)
        val resolvedPath = File(fixedBasePath, fixedRelativePath).canonicalPath
        return resolvedPath.replace(File.separator, pathSeparator).substring(1)
    }

    /**
     * Gets the parent directory of a path.
     */
    private fun getParentDirectory(path: String): String {
        val separatorIndex = path.lastIndexOf(pathSeparator)
        return if (separatorIndex >= 0) {
            path.substring(0, separatorIndex)
        } else {
            ""
        }
    }

    private data class ManifestItem(
        val href: String,
        val mediaType: String,
        val properties: String = "",
    )
}
