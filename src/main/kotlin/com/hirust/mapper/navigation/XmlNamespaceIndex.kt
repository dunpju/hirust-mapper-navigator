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

    /** `<sql id>` 片段定位结果:文件 + 片段 + 所属 namespace */
    data class SqlFragmentLocation(
        val file: VirtualFile,
        val fragment: SqlFragmentInfo,
        val namespace: String
    )

    /** `<include refid>` 引用定位结果:文件 + 引用 + 所属 namespace */
    data class IncludeLocation(
        val file: VirtualFile,
        val include: IncludeInfo,
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
            // 未保存文档兜底:索引基于磁盘,编辑器中的修改(如语句生成插入)保存前不可见
            val info = currentMapperInfo(file) ?: continue
            val stmt = info.statements.firstOrNull { it.id == id && (tag == null || it.tag == tag) }
                ?: info.statements.firstOrNull { it.id == id }
                ?: continue
            return XmlStatementLocation(file, stmt, info.namespace)
        }
        return null
    }

    /**
     * 获取文件的 mapper 解析信息:优先未保存的打开文档(含最新编辑),
     * 与磁盘一致时直接用缓存索引。
     */
    private fun currentMapperInfo(file: VirtualFile): MapperInfo? {
        val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
        val doc = fdm.getDocument(file)
        if (doc != null && fdm.isFileModified(file)) {
            return try {
                XmlMapperParser.parse(doc.text)
            } catch (_: Exception) {
                mapperInfoByFile[file]
            }
        }
        return mapperInfoByFile[file]
    }

    /**
     * 按 `<include refid="...">` 的 refid 值查找 `<sql id>` 片段定义。
     *
     * 匹配策略(与 MyBatis 语义一致,找不到返回 null —— 容错,不误跳):
     * 1. **当前文件优先**:refid 无前缀,查本文件的 `<sql id="refid">`
     * 2. **命名空间前缀**:refid 形如 `<ns>.<id>` 或 `<ns>::<id>`(跨 mapper 引用),
     *    命中该 namespace 的 XML 文件后按 id 查找;多个 namespace 可匹配时取最长前缀
     */
    fun findSqlFragment(refid: String, currentFile: VirtualFile): SqlFragmentLocation? {
        ensureInitialized()
        if (refid.isEmpty()) return null

        // 策略1:当前文件(文件内局部 id)
        getMapperInfo(currentFile)?.let { info ->
            info.sqlFragments.firstOrNull { it.id == refid }?.let {
                return SqlFragmentLocation(currentFile, it, info.namespace)
            }
        }

        // 策略2:命名空间前缀(最长前缀优先,避免一个 namespace 是另一个前缀时截胡)
        val candidates = namespaceToFile.keys
            .filter { ns -> refid.startsWith("$ns.") || refid.startsWith("$ns::") }
            .sortedByDescending { it.length }
        for (ns in candidates) {
            val file = namespaceToFile[ns] ?: continue
            val id = if (refid.startsWith("$ns.")) {
                refid.removePrefix("$ns.")
            } else {
                refid.removePrefix("$ns::")
            }
            val info = mapperInfoByFile[file] ?: continue
            info.sqlFragments.firstOrNull { it.id == id }?.let {
                return SqlFragmentLocation(file, it, ns)
            }
        }
        return null
    }

    /**
     * 反向查找:引用了指定 `<sql id>` 片段的全部 `<include refid>` 位置
     * (sql id 反向跳转,多处引用时由平台弹出目标列表)。
     *
     * 匹配语义与 [findSqlFragment] 对称:
     * - 同文件的无前缀 refid(`refid == id`)
     * - 任意文件带本片段 namespace 前缀的 refid(`ns.id` / `ns::id`)
     *
     * 排序:同文件在前,各文件内按出现顺序。
     * 索引未收录当前文件时兜底直读解析(同文件场景零索引依赖)。
     */
    fun findIncludesOf(sqlId: String, definitionFile: VirtualFile): List<IncludeLocation> {
        ensureInitialized()
        if (sqlId.isEmpty()) return emptyList()
        val defNs = mapperInfoByFile[definitionFile]?.namespace

        val result = mutableListOf<IncludeLocation>()
        fun IncludeInfo.matches(file: VirtualFile): Boolean =
            (refid == sqlId && file == definitionFile) ||
                    (defNs != null && (refid == "$defNs.$sqlId" || refid == "$defNs::$sqlId"))

        for (file in indexedFiles) {
            val info = mapperInfoByFile[file] ?: continue
            info.includes.filter { it.matches(file) }.forEach {
                result += IncludeLocation(file, it, info.namespace)
            }
        }

        // 兜底:索引未就绪/未收录当前文件时,直接解析当前文件(同文件 + 本 namespace 前缀)
        if (result.isEmpty() && mapperInfoByFile[definitionFile] == null) {
            val info = try {
                ApplicationManager.getApplication().runReadAction<String?> {
                    NavigationUtil.loadTextDocumentAligned(definitionFile)
                }?.let { XmlMapperParser.parse(it) }
            } catch (_: Exception) {
                null
            }
            val ns = info?.namespace
            info?.includes?.filter {
                it.refid == sqlId ||
                        (ns != null && (it.refid == "$ns.$sqlId" || it.refid == "$ns::$sqlId"))
            }?.forEach {
                result += IncludeLocation(definitionFile, it, ns ?: "")
            }
        }

        return result.sortedWith(
            compareBy({ it.file != definitionFile }, { it.include.tagOffset })
        )
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

    /**
     * 按 `#[dao(xml = "...")]` 的相对路径定位 XML 文件
     * (Ctrl+Click 该字面量与索引收集通道 2 共用本规则):
     * 基准依次尝试 contextFile 的 crate 根 → 项目根;
     * 均落空时回退到已索引文件的后缀匹配(路径以 rel 结尾)。
     */
    fun findXmlFileByRelativePath(rel: String, contextFile: VirtualFile): VirtualFile? {
        val normalized = rel.replace('\\', '/').trimStart('/')
        if (normalized.isEmpty()) return null

        val direct = ApplicationManager.getApplication().runReadAction<VirtualFile?> {
            val bases = listOfNotNull(
                MapperPathsConfig.crateRootPathOfUncached(contextFile),
                project.basePath
            ).distinct()
            for (base in bases) {
                val f = LocalFileSystem.getInstance().findFileByPath("$base/$normalized")
                if (f != null && f.isValid && !f.isDirectory) return@runReadAction f
            }
            null
        }
        if (direct != null) return direct

        ensureInitialized()
        val suffix = "/$normalized"
        return getIndexedFiles().firstOrNull { it.path.replace('\\', '/').endsWith(suffix) }
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
