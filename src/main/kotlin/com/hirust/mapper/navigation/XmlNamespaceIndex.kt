package com.hirust.mapper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * XML namespace 索引服务。
 *
 * 扫描由 with_mapper_paths 配置指定的 XML 文件，
 * 解析每个 XML 的 <mapper namespace="..."> 属性，
 * 建立 namespace 字符串到 VirtualFile 的双向映射。
 */
class XmlNamespaceIndex(private val project: Project) {

    private val log = Logger.getInstance(XmlNamespaceIndex::class.java)

    /** namespace字符串 -> XML VirtualFile */
    private val namespaceToFile = ConcurrentHashMap<String, VirtualFile>()

    /** stem（去除后缀的模块名）-> XML VirtualFile */
    private val stemToFile = ConcurrentHashMap<String, VirtualFile>()

    /** 所有已索引的 XML 文件 */
    private val indexedFiles = CopyOnWriteArrayList<VirtualFile>()

    @Volatile
    private var initialized = false

    fun rebuildIndex() {
        log.info("[hirust-mapper-navigator] Rebuilding XML namespace index...")

        namespaceToFile.clear()
        stemToFile.clear()
        indexedFiles.clear()

        val xmlFiles = collectXmlFiles()
        for (xmlFile in xmlFiles) {
            indexFile(xmlFile)
        }

        initialized = true
        log.info("[hirust-mapper-navigator] Index rebuilt: ${indexedFiles.size} XML files, " +
                "${namespaceToFile.size} namespaces mapped")
    }

    private fun collectXmlFiles(): List<VirtualFile> {
        val config = MapperPathsConfig.getInstance(project)
        val patterns = config.patterns

        if (patterns.isEmpty()) {
            log.warn("[hirust-mapper-navigator] No with_mapper_paths found, using default: resources/mapper/")
            val baseDir = project.baseDir ?: return emptyList()
            val mapperDir = baseDir.findFileByRelativePath("resources/mapper") ?: return emptyList()
            return collectFromDirectory(mapperDir)
        }

        val allFiles = mutableListOf<VirtualFile>()
        for (pattern in patterns) {
            val files = resolveGlobPattern(pattern)
            allFiles.addAll(files)
        }
        return allFiles.distinctBy { it.path }
    }

    private fun resolveGlobPattern(pattern: String): List<VirtualFile> {
        val baseDir = project.baseDir ?: return emptyList()

        val dirPath = pattern.substringBeforeLast("/")
        val dir = baseDir.findFileByRelativePath(dirPath) ?: run {
            log.warn("[hirust-mapper-navigator] Directory not found: $dirPath")
            return emptyList()
        }

        val filePattern = pattern.substringAfterLast("/")
        val recursive = filePattern.startsWith("**")

        return collectFromDirectory(dir, recursive = recursive)
    }

    private fun collectFromDirectory(dir: VirtualFile, recursive: Boolean = true): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()

        fun traverse(current: VirtualFile) {
            for (child in current.children) {
                if (child.isDirectory) {
                    if (recursive) traverse(child)
                } else if (child.extension == "xml") {
                    files.add(child)
                }
            }
        }

        traverse(dir)
        return files
    }

    private fun indexFile(xmlFile: VirtualFile) {
        try {
            val content = VfsUtil.loadText(xmlFile)
            val namespace = extractNamespace(content) ?: run {
                log.debug("[hirust-mapper-navigator] No namespace found in ${xmlFile.path}")
                return
            }

            indexedFiles.add(xmlFile)
            namespaceToFile[namespace] = xmlFile

            val stem = extractStem(namespace)
            if (stem != null) {
                stemToFile[stem] = xmlFile
            }
        } catch (e: Exception) {
            log.warn("[hirust-mapper-navigator] Failed to index ${xmlFile.path}: ${e.message}")
        }
    }

    fun extractNamespace(xmlContent: String): String? {
        val regex = Regex("""<mapper\s+[^>]*namespace\s*=\s*"([^"]+)"""")
        return regex.find(xmlContent)?.groupValues?.get(1)
    }

    fun extractStem(namespace: String): String? {
        val lastSegment = namespace.substringAfterLast("::")
        if (lastSegment.isEmpty()) return null

        val suffixes = listOf("_dao", "_service", "_repo", "_repository", "_mapper", "_accessor")
        for (suffix in suffixes) {
            if (lastSegment.endsWith(suffix) && lastSegment.length > suffix.length) {
                return lastSegment.removeSuffix(suffix)
            }
        }
        return lastSegment
    }

    fun ensureInitialized() {
        if (!initialized) {
            rebuildIndex()
        }
    }

    fun getXmlFileByNamespace(namespace: String): VirtualFile? {
        ensureInitialized()
        return namespaceToFile[namespace]
    }

    fun getXmlFileByStem(stem: String): VirtualFile? {
        ensureInitialized()
        return stemToFile[stem]
    }

    fun findXmlFile(namespace: String): VirtualFile? {
        ensureInitialized()
        namespaceToFile[namespace]?.let { return it }
        val stem = extractStem(namespace) ?: return null
        stemToFile[stem]?.let { return it }
        val lastSegment = namespace.substringAfterLast("::")
        stemToFile[lastSegment]?.let { return it }
        return null
    }

    fun getAllNamespaces(): Set<String> {
        ensureInitialized()
        return namespaceToFile.keys.toSet()
    }

    fun getIndexedFiles(): List<VirtualFile> {
        ensureInitialized()
        return indexedFiles.toList()
    }

    fun refreshFile(xmlFile: VirtualFile) {
        val oldEntries = namespaceToFile.entries
            .filter { it.value == xmlFile }
            .map { it.key }
        oldEntries.forEach { namespaceToFile.remove(it) }
        stemToFile.entries.removeAll { it.value == xmlFile }
        indexedFiles.remove(xmlFile)

        if (xmlFile.isValid && xmlFile.exists()) {
            indexFile(xmlFile)
        }
    }

    companion object {
        fun getInstance(project: Project): XmlNamespaceIndex =
            project.getService(XmlNamespaceIndex::class.java)
    }
}
