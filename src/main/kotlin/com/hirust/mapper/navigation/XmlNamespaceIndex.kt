package com.hirust.mapper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.*
import com.intellij.psi.PsiManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * XML namespace 索引服务。
 *
 * 扫描由 with_mapper_paths 配置指定的 XML 文件，
 * 解析每个 XML 的 <mapper namespace="..."> 属性，
 * 建立 namespace 字符串 → VirtualFile 的双向映射。
 *
 * 同时维护 stem → VirtualFile 的映射（去除 _dao/_service 等后缀的模块名），
 * 用于 namespace 字符串值跳转到 XML 文件。
 *
 * 缓存策略：项目级单例，通过 VirtualFileChangeListener 监听 XML 变更自动刷新。
 */
class XmlNamespaceIndex(private val project: Project) {

    private val log = Logger.getInstance(XmlNamespaceIndex::class.java)

    /**
     * namespace → XML VirtualFile 映射
     * key: XML 中 <mapper namespace="crate::app::dao::privilege_project_dao">
     * value: 对应的 XML VirtualFile
     */
    private val namespaceToFile = ConcurrentHashMap<String, VirtualFile>()

    /**
     * stem → XML VirtualFile 映射
     * key: 模块名去除后缀后的名称，如 "privilege_project"
     * value: 对应的 XML VirtualFile
     */
    private val stemToFile = ConcurrentHashMap<String, VirtualFile>()

    /**
     * 所有已索引的 XML 文件列表
     */
    private val indexedFiles = CopyOnWriteArrayList<VirtualFile>()

    /** 是否已执行过首次索引 */
    @Volatile
    private var initialized = false

    /**
     * 重建完整索引。
     * 1. 从 MapperPathsConfig 获取 glob 模式
     * 2. 遍历项目目录查找匹配的 XML 文件
     * 3. 解析每个 XML 的 namespace 属性
     */
    fun rebuildIndex() {
        log.info("[hirust-mapper-navigator] Rebuilding XML namespace index...")

        val oldFiles = indexedFiles.toSet()
        val newFiles = collectXmlFiles()

        namespaceToFile.clear()
        stemToFile.clear()
        indexedFiles.clear()

        for (xmlFile in newFiles) {
            indexFile(xmlFile)
        }

        initialized = true
        log.info(
            "[hirust-mapper-navigator] Index rebuilt: ${indexedFiles.size} XML files, " +
                    "${namespaceToFile.size} namespaces mapped"
        )
    }

    /**
     * 根据 with_mapper_paths 中的 glob 模式收集所有匹配的 XML 文件
     */
    private fun collectXmlFiles(): List<VirtualFile> {
        val config = MapperPathsConfig.getInstance(project)
        val patterns = config.patterns

        if (patterns.isEmpty()) {
            // 如果没有配置，使用默认路径作为兜底
            log.warn("[hirust-mapper-navigator] No with_mapper_paths found, using default: resources/mapper/")
            return collectFromDirectory(project.baseDir, "resources/mapper")
        }

        val allFiles = mutableListOf<VirtualFile>()
        for (pattern in patterns) {
            val files = resolveGlobPattern(pattern)
            allFiles.addAll(files)
        }
        return allFiles.distinctBy { it.path }
    }

    /**
     * 解析 glob 模式，收集匹配的 XML 文件
     *
     * 支持的模式:
     *   "resources/mapper/**/*.xml"  — 递归搜索
     *   "resources/mapper/*.xml"     — 仅当前目录
     */
    private fun resolveGlobPattern(pattern: String): List<VirtualFile> {
        val baseDir = project.baseDir ?: return emptyList()

        // 提取基础目录和文件匹配模式
        // "resources/mapper/**/*.xml" → baseDir="resources/mapper", recursive=true
        // "resources/mapper/*.xml"    → baseDir="resources/mapper", recursive=false
        val dirPath = pattern.substringBeforeLast("/")
        val filePattern = pattern.substringAfterLast("/")
        val recursive = filePattern.startsWith("**")

        val dir = baseDir.findFileByRelativePath(dirPath) ?: run {
            log.warn("[hirust-mapper-navigator] Directory not found: $dirPath (pattern: $pattern)")
            return emptyList()
        }

        return collectFromDirectory(dir, filePattern = null, recursive = recursive)
    }

    /**
     * 从指定目录递归/非递归收集 XML 文件
     */
    private fun collectFromDirectory(
        dir: VirtualFile,
        pathSegment: String? = null,
        filePattern: String? = null,
        recursive: Boolean = true
    ): List<VirtualFile> {
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

    /**
     * 索引单个 XML 文件
     */
    private fun indexFile(xmlFile: VirtualFile) {
        try {
            val content = VfsUtil.loadText(xmlFile)
            val namespace = extractNamespace(content) ?: run {
                log.debug("[hirust-mapper-navigator] No namespace found in ${xmlFile.path}")
                return
            }

            indexedFiles.add(xmlFile)
            namespaceToFile[namespace] = xmlFile

            // 生成 stem 映射: 去除后缀的模块名
            val stem = extractStem(namespace)
            if (stem != null) {
                stemToFile[stem] = xmlFile
            }
        } catch (e: Exception) {
            log.warn("[hirust-mapper-navigator] Failed to index ${xmlFile.path}: ${e.message}")
        }
    }

    /**
     * 从 XML 内容中提取 <mapper namespace="..."> 属性值
     */
    internal fun extractNamespace(xmlContent: String): String? {
        val regex = Regex("""<mapper\s+[^>]*namespace\s*=\s*"([^"]+)"""")
        return regex.find(xmlContent)?.groupValues?.get(1)
    }

    /**
     * 从 namespace 路径中提取 stem（去除 _dao/_service/_repo/_mapper 等后缀的模块名）
     *
     * "crate::app::dao::privilege_project_dao" → "privilege_project"
     * "crate::app::dao::privilege_notify_dao"   → "privilege_notify"
     * "crate::app::service::user_service"       → "user"
     */
    internal fun extractStem(namespace: String): String? {
        val lastSegment = namespace.substringAfterLast("::")
        if (lastSegment.isEmpty()) return null

        // 按优先级去除后缀
        val suffixes = listOf(
            "_dao", "_service", "_repo", "_repository",
            "_mapper", "_accessor"
        )
        for (suffix in suffixes) {
            if (lastSegment.endsWith(suffix) && lastSegment.length > suffix.length) {
                return lastSegment.removeSuffix(suffix)
            }
        }
        return lastSegment
    }

    /**
     * 确保索引已初始化（懒加载）
     */
    fun ensureInitialized() {
        if (!initialized) {
            rebuildIndex()
        }
    }

    // ==================== 查询 API ====================

    /**
     * 根据 namespace 字符串精确匹配 XML 文件
     */
    fun getXmlFileByNamespace(namespace: String): VirtualFile? {
        ensureInitialized()
        return namespaceToFile[namespace]
    }

    /**
     * 根据 stem 匹配 XML 文件（去除后缀的模块名）
     */
    fun getXmlFileByStem(stem: String): VirtualFile? {
        ensureInitialized()
        return stemToFile[stem]
    }

    /**
     * 智能查找: 先精确匹配 namespace，再匹配 stem，最后匹配原始模块名
     */
    fun findXmlFile(namespace: String): VirtualFile? {
        ensureInitialized()

        // 1. 精确匹配
        namespaceToFile[namespace]?.let { return it }

        // 2. 通过 stem 匹配
        val stem = extractStem(namespace) ?: return null
        stemToFile[stem]?.let { return it }

        // 3. 通过完整模块名匹配（不含路径前缀）
        val lastSegment = namespace.substringAfterLast("::")
        stemToFile[lastSegment]?.let { return it }

        return null
    }

    /**
     * 获取所有已索引的 namespace
     */
    fun getAllNamespaces(): Set<String> {
        ensureInitialized()
        return namespaceToFile.keys.toSet()
    }

    /**
     * 获取所有已索引的 XML 文件
     */
    fun getIndexedFiles(): List<VirtualFile> {
        ensureInitialized()
        return indexedFiles.toList()
    }

    /**
     * 当单个 XML 文件变更时增量刷新索引
     */
    fun refreshFile(xmlFile: VirtualFile) {
        // 先移除旧映射
        val oldEntries = namespaceToFile.entries
            .filter { it.value == xmlFile }
            .map { it.key }
        oldEntries.forEach { namespaceToFile.remove(it) }
        stemToFile.entries.removeAll { it.value == xmlFile }
        indexedFiles.remove(xmlFile)

        // 如果文件还存在，重新索引
        if (xmlFile.isValid && xmlFile.exists()) {
            indexFile(xmlFile)
        }
    }

    companion object {
        fun getInstance(project: Project): XmlNamespaceIndex =
            project.getService(XmlNamespaceIndex::class.java)
    }
}
