package com.hirust.mapper.navigation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * XML namespace 索引服务。
 *
 * 扫描由 with_mapper_paths 配置指定的 XML 文件，
 * 解析每个 XML 的 <mapper namespace="..."> 属性与语句标签
 * (select/insert/update/delete 的 id)，
 * 建立 namespace 字符串到 VirtualFile 的双向映射及语句级索引。
 */
class XmlNamespaceIndex(private val project: Project) {

    private val log = Logger.getInstance(XmlNamespaceIndex::class.java)

    /** namespace字符串 -> XML VirtualFile */
    private val namespaceToFile = ConcurrentHashMap<String, VirtualFile>()

    /** stem（去除后缀的模块名）-> XML VirtualFile */
    private val stemToFile = ConcurrentHashMap<String, VirtualFile>()

    /** XML VirtualFile -> 解析出的 mapper 信息(含语句列表) */
    private val mapperInfoByFile = ConcurrentHashMap<VirtualFile, MapperInfo>()

    /** 所有已索引的 XML 文件 */
    private val indexedFiles = CopyOnWriteArrayList<VirtualFile>()

    /** 语句定位结果:文件 + 语句 + 实际匹配到的 namespace */
    data class XmlStatementLocation(
        val file: VirtualFile,
        val statement: StatementInfo,
        val namespace: String
    )

    @Volatile
    private var initialized = false

    /**
     * 懒加载入口:预热完成前用户已触发交互时,同步回退到协调扫描
     * (协调器会先刷新 with_mapper_paths 模式再重建本索引)。
     */
    fun ensureInitialized() {
        if (!initialized) MapperScanCoordinator.getInstance(project).rebuildAll()
    }

    /**
     * 重建 XML 索引。注意:with_mapper_paths 模式由 [MapperScanCoordinator]
     * 在协调扫描中先行刷新(单次 .rs 遍历),此处不再重复扫描 .rs。
     */
    fun rebuildIndex() {
        log.info("[hirust-mapper-navigator] Rebuilding XML namespace index...")

        namespaceToFile.clear()
        stemToFile.clear()
        mapperInfoByFile.clear()
        indexedFiles.clear()

        val xmlFiles = collectXmlFiles()
        for (xmlFile in xmlFiles) {
            indexFile(xmlFile)
        }

        initialized = true
        log.info("[hirust-mapper-navigator] Index rebuilt: ${indexedFiles.size} XML files, " +
                "${namespaceToFile.size} namespaces mapped")
    }

    /**
     * 收集待索引 XML,三层通道(前者命中即不再依赖后者):
     * 1. with_mapper_paths glob —— 基准依次尝试【声明文件的 crate 根】与【项目根】。
     *    v1.2.1 及之前仅按项目根解析,在 workspace 布局(crate 在项目根子目录,
     *    运行时以 crate 根为 CWD)下必落空,导致本索引为空、全部双向跳转失效。
     * 2. `#[dao(xml = "...")]` 属性精确补录 —— 逐 DAO 的相对路径定位(非 glob),
     *    在 with_mapper_paths 缺失/写错的项目里可独立引导索引。
     * 3. 回退扫描 —— 前两层总数为 0 时:项目根 resources/mapper 目录 +
     *    项目内路径段为 mapper/mappers 的 XML(旧过滤 contains("/mapper/")
     *    匹配不到复数 mappers,改为按路径段比较)。
     */
    private fun collectXmlFiles(): List<VirtualFile> {
        val patterns = MapperPathsConfig.getInstance(project).patterns

        val allFiles = ApplicationManager.getApplication().runReadAction<MutableList<VirtualFile>> {
            val files = mutableListOf<VirtualFile>()

            // 通道1:glob,基准优先级 crate 根 → 项目根,首个命中即止
            for (mp in patterns) {
                for (base in listOfNotNull(mp.baseDirPath, project.basePath)) {
                    val resolved = resolveGlobPattern(mp.pattern, base)
                    if (resolved.isNotEmpty()) {
                        files += resolved
                        break
                    }
                }
            }

            // 通道2:#[dao(xml = "...")] 精确补录,基准优先级 DAO 文件 crate 根 → 项目根
            for (loc in RustDaoIndex.getInstance(project).allDaos()) {
                val rel = loc.dao.xmlAttr
                if (rel.isEmpty()) continue
                val bases = listOfNotNull(
                    MapperPathsConfig.crateRootPathOfUncached(loc.file),
                    project.basePath
                ).distinct()
                for (base in bases) {
                    val f = LocalFileSystem.getInstance()
                        .findFileByPath("$base/${rel.replace('\\', '/')}")
                    if (f != null && f.isValid && !f.isDirectory) {
                        files += f
                        break
                    }
                }
            }
            files
        }

        if (allFiles.isEmpty()) {
            log.warn("[hirust-mapper-navigator] No XML resolved " +
                    "(${patterns.size} mapper patterns, ${if (patterns.isEmpty()) "none declared" else "all unresolved"}), " +
                    "falling back to project scan")
            allFiles += fallbackScan()
        }
        return allFiles.distinctBy { it.path }
    }

    /** 回退扫描:项目根 resources/mapper 目录 + 路径段为 mapper/mappers 的项目内 XML */
    private fun fallbackScan(): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        project.basePath?.let { base ->
            findDirByRelativePath(base, "resources/mapper")?.let { result += collectFromDirectory(it) }
        }
        try {
            ApplicationManager.getApplication().runReadAction<Unit> {
                val xmlType = FileTypeManager.getInstance().getFileTypeByExtension("xml")
                FileTypeIndex.getFiles(xmlType, GlobalSearchScope.projectScope(project))
                    .filter(::isMapperPathFile)
                    .forEach { result += it }
            }
        } catch (e: Exception) {
            log.warn("[hirust-mapper-navigator] Fallback XML scan failed: ${e.message}")
        }
        return result
    }

    /** 路径中任一目录段为 mapper/mappers(大小写不敏感);排除构建产物与 IDE 元数据 */
    private fun isMapperPathFile(f: VirtualFile): Boolean {
        val path = f.path.replace('\\', '/')
        if (path.contains("/.git/") || path.contains("/target/") ||
            path.contains("/.idea/") || path.contains("/node_modules/")
        ) return false
        return path.split('/').dropLast(1)
            .any { it.equals("mapper", ignoreCase = true) || it.equals("mappers", ignoreCase = true) }
    }

    private fun findDirByRelativePath(basePath: String, relPath: String): VirtualFile? =
        LocalFileSystem.getInstance()
            .findFileByPath((if (relPath.isEmpty()) basePath else "$basePath/$relPath").replace('\\', '/'))

    private fun resolveGlobPattern(pattern: String, basePath: String): List<VirtualFile> {
        // 统一分隔符并拆段;支持 ** 路径段(如 resources/mapper/**/*.xml)
        val segments = pattern.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return emptyList()

        val filePart = segments.last()
        val dirSegments = segments.dropLast(1)
        val recursive = dirSegments.contains("**") || filePart.startsWith("**")
        val literalDirs = dirSegments.filter { it != "**" }
        val ext = filePart.substringAfterLast('.', "").lowercase().ifEmpty { "xml" }

        val dir: VirtualFile? = if (literalDirs.isEmpty()) {
            findDirByRelativePath(basePath, "")
        } else {
            findDirByRelativePath(basePath, literalDirs.joinToString("/"))
        }
        if (dir == null) {
            log.debug("[hirust-mapper-navigator] Directory not found under base=$basePath: " +
                    "$literalDirs (pattern=$pattern)")
            return emptyList()
        }

        return collectFromDirectory(dir, recursive = recursive, ext = ext)
    }

    private fun collectFromDirectory(dir: VirtualFile, recursive: Boolean = true, ext: String = "xml"): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()

        fun traverse(current: VirtualFile) {
            for (child in current.children) {
                if (child.isDirectory) {
                    if (recursive) traverse(child)
                } else if (child.extension?.lowercase() == ext) {
                    files.add(child)
                }
            }
        }

        traverse(dir)
        return files
    }

    private fun indexFile(xmlFile: VirtualFile) {
        try {
            // 偏移必须与 Document 坐标对齐(\n 归一化),否则 CRLF 文件会逐行漂移
            val content = NavigationUtil.loadTextDocumentAligned(xmlFile) ?: return
            val info = XmlMapperParser.parse(content) ?: run {
                log.debug("[hirust-mapper-navigator] No namespace found in ${xmlFile.path}")
                return
            }

            indexedFiles.add(xmlFile)
            mapperInfoByFile[xmlFile] = info
            namespaceToFile[info.namespace] = xmlFile

            val stem = extractStem(info.namespace)
            if (stem != null) {
                stemToFile[stem] = xmlFile
            }
        } catch (e: Exception) {
            log.warn("[hirust-mapper-navigator] Failed to index ${xmlFile.path}: ${e.message}")
        }
    }

    fun extractNamespace(xmlContent: String): String? =
        XmlMapperParser.extractNamespace(xmlContent)

    /** 获取指定 XML 文件的完整解析信息(含语句列表) */
    fun getMapperInfo(file: VirtualFile): MapperInfo? {
        ensureInitialized()
        return mapperInfoByFile[file]
    }

    /** 获取指定 XML 文件的全部语句 */
    fun getStatements(file: VirtualFile): List<StatementInfo> =
        getMapperInfo(file)?.statements ?: emptyList()

    /**
     * 按 namespace + 语句 id 查找 XML 语句。
     * 匹配策略与 [NamespacePathResolver] 一致:精确 namespace → stem → 末段。
     *
     * @param tag 期望的语句标签(select/insert/update/delete),为 null 时忽略
     */
    fun findStatement(namespace: String, id: String, tag: String? = null): XmlStatementLocation? {
        ensureInitialized()
        val lastSegment = namespace.substringAfterLast("::")
        val candidates = sequence {
            namespaceToFile[namespace]?.let { yield(it) }
            extractStem(namespace)?.let { stemToFile[it] }?.let { yield(it) }
            stemToFile[lastSegment]?.let { yield(it) }
        }.distinct()

        for (file in candidates) {
            val info = mapperInfoByFile[file] ?: continue
            val stmt = info.statements.firstOrNull { it.id == id && (tag == null || it.tag == tag) }
                ?: info.statements.firstOrNull { it.id == id }
                ?: continue
            return XmlStatementLocation(file, stmt, info.namespace)
        }
        return null
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
        mapperInfoByFile.remove(xmlFile)
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
