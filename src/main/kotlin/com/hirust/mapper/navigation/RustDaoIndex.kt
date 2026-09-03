package com.hirust.mapper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Rust DAO 索引:维护 namespace → DAO 块、(namespace, id) → 方法的映射,
 * 并提供按文件偏移反查(供行标记使用)。
 *
 * 文件级解析结果按 modificationStamp 缓存;.rs 文件变更时由
 * [XmlIndexRefreshListener] 调用 [refreshFile] 失效对应条目。
 */
class RustDaoIndex(private val project: Project) {

    private val log = Logger.getInstance(RustDaoIndex::class.java)

    data class DaoLocation(val file: VirtualFile, val dao: DaoInfo)

    data class MethodLocation(val file: VirtualFile, val dao: DaoInfo, val method: MethodInfo)

    /** struct 定义定位结果(XML resultType → Rust 跳转) */
    data class TypeLocation(val file: VirtualFile, val type: RustTypeInfo)

    /** 文件级解析结果:DAO 块 + struct 类型(一次读盘两种产出) */
    private data class ParsedFile(val daos: List<DaoInfo>, val types: List<RustTypeInfo>)

    /** 文件级解析缓存:VirtualFile -> (modificationStamp, 解析结果) */
    private val fileCache = ConcurrentHashMap<VirtualFile, Pair<Long, ParsedFile>>()

    /** namespace -> DAO 位置 */
    private val namespaceIndex = ConcurrentHashMap<String, DaoLocation>()

    /** 类型名 -> struct 定义位置(resultType 跳转) */
    private val typeIndex = ConcurrentHashMap<String, TypeLocation>()

    @Volatile
    private var initialized = false

    /**
     * 懒加载入口:预热完成前用户已触发交互时,同步回退到协调扫描。
     * 正常情况下启动预热(MapperWarmUpStartup)已在后台完成,此处直接命中。
     */
    fun ensureInitialized() {
        if (!initialized) MapperScanCoordinator.getInstance(project).rebuildAll()
    }

    /** 协调扫描开始:清空 namespace/类型索引(文件级解析缓存按 modStamp 保留) */
    fun beginScan() {
        namespaceIndex.clear()
        typeIndex.clear()
    }

    /**
     * 协调扫描喂数据:用协调器已读取的内容解析并登记该文件的 DAO 与 struct 类型,
     * 避免每个索引各自读盘造成重复全项目 IO。
     */
    fun acceptFileContent(vf: VirtualFile, content: String) {
        if (!vf.isValid) return
        val stamp = vf.modificationStamp
        val parsed = parseContent(content)
        fileCache[vf] = stamp to parsed
        for (dao in parsed.daos) {
            namespaceIndex[dao.namespace] = DaoLocation(vf, dao)
        }
        for (t in parsed.types) {
            typeIndex[t.name] = TypeLocation(vf, t)
        }
    }

    /** 一次解析产出 DAO 块与 struct 类型(struct 扫描不限 #[dao] 文件:模型在普通模块) */
    private fun parseContent(content: String): ParsedFile {
        val daos = if (content.contains("#[dao")) RustSourceParser.parse(content) else emptyList()
        val types = if (content.contains("struct")) RustSourceParser.parseStructTypes(content) else emptyList()
        return ParsedFile(daos, types)
    }

    fun rebuildIndex() {
        namespaceIndex.clear()
        typeIndex.clear()
        val scope = GlobalSearchScope.projectScope(project)
        // 注意:FilenameIndex.getVirtualFilesByName 按"完整文件名"匹配(".rs" 匹配不到 main.rs),
        // 必须用 FileTypeIndex 按文件类型检索(RustRover 中是 RustFileType,无 Rust 插件时是 PlainText)
        val files = try {
            val rsFileType = FileTypeManager.getInstance().getFileTypeByExtension("rs")
            FileTypeIndex.getFiles(rsFileType, scope)
                .filter { it.extension.equals("rs", ignoreCase = true) }
        } catch (e: Exception) {
            log.warn("[hirust-mapper-navigator] FileTypeIndex query failed: ${e.message}")
            return
        }
        for (vf in files) {
            indexFile(vf)
        }
        initialized = true
        val methodCount = namespaceIndex.values.sumOf { it.dao.methods.size }
        log.info("[hirust-mapper-navigator] RustDaoIndex rebuilt: ${namespaceIndex.size} namespaces " +
                "($methodCount methods), ${typeIndex.size} structs from ${files.size} rs files; " +
                namespaceIndex.keys.joinToString(", ", limit = 5))
    }

    private fun indexFile(vf: VirtualFile) {
        if (!vf.isValid) return
        val parsed = getParsedFile(vf) ?: return
        for (dao in parsed.daos) {
            namespaceIndex[dao.namespace] = DaoLocation(vf, dao)
        }
        for (t in parsed.types) {
            typeIndex[t.name] = TypeLocation(vf, t)
        }
    }

    /** 解析并缓存单个 .rs 文件的 DAO;非 .rs 或读取失败返回 null */
    fun getParsed(vf: VirtualFile): List<DaoInfo>? = getParsedFile(vf)?.daos

    /** 解析并缓存单个 .rs 文件的完整结果(DAO + struct);非 .rs 或读取失败返回 null */
    private fun getParsedFile(vf: VirtualFile): ParsedFile? {
        if (!vf.isValid || vf.extension != "rs") return null
        val stamp = vf.modificationStamp
        fileCache[vf]?.let { (s, parsed) ->
            if (s == stamp) return parsed
        }
        val content = try {
            // 偏移必须与 Document 坐标对齐(\n 归一化),否则 CRLF 文件会逐行漂移
            NavigationUtil.loadTextDocumentAligned(vf) ?: return null
        } catch (e: Exception) {
            log.warn("[hirust-mapper-navigator] Failed to read ${vf.path}: ${e.message}")
            return null
        }
        val parsed = parseContent(content)
        fileCache[vf] = stamp to parsed
        return parsed
    }

    /**
     * 按名称查找 struct 定义(XML resultType → Rust 跳转)。
     * 支持限定名:取 `::` / `.` 分隔的末段。
     */
    fun findType(name: String): TypeLocation? {
        val simple = name.substringAfterLast("::").substringAfterLast('.')
        if (simple.isEmpty()) return null
        ensureInitialized()
        return typeIndex[simple]?.takeIf { it.file.isValid }
    }

    /**
     * 全部已登记 DAO 的裸访问器。
     * 刻意【不做 ensureInitialized】:XmlNamespaceIndex 在协调扫描(rebuildAll → rebuildIndex)
     * 中调用本方法,若在此触发 ensureInitialized 会同线程重入 @Synchronized 的 rebuildAll,
     * 造成嵌套全量重建。仅在协调流程已就绪的前提下使用。
     */
    fun allDaos(): List<DaoLocation> = namespaceIndex.values.toList()

    /** 按 namespace 查找 DAO(精确匹配优先,退化到末段匹配) */
    fun findDaoByNamespace(namespace: String): DaoLocation? {
        ensureInitialized()
        dropStaleEntries()
        namespaceIndex[namespace]?.let { return it }
        val lastSegment = namespace.substringAfterLast("::")
        if (lastSegment.isEmpty()) return null
        return namespaceIndex.values.firstOrNull {
            it.dao.namespace.substringAfterLast("::") == lastSegment && it.file.isValid
        }
    }

    /**
     * 按 namespace + 语句 id 查找方法。
     * @param tag 期望的语句标签(select/insert/update/delete),为 null 时忽略
     */
    fun findMethod(namespace: String, id: String, tag: String? = null): MethodLocation? {
        val daoLoc = findDaoByNamespace(namespace) ?: return null
        val methods = daoLoc.dao.methods
        val method = methods.firstOrNull { it.id == id && (tag == null || it.stmtTag == tag) }
            ?: methods.firstOrNull { it.id == id }
            ?: return null
        return MethodLocation(daoLoc.file, daoLoc.dao, method)
    }

    /** 查找覆盖指定偏移的 DAO 块(行标记:impl 锚点) */
    fun findDaoAt(vf: VirtualFile, offset: Int): DaoLocation? {
        val daos = getParsed(vf) ?: return null
        val dao = daos.lastOrNull { offset >= it.attrOffset && offset <= it.implOffset + it.implName.length }
            ?: return null
        return DaoLocation(vf, dao)
    }

    /** 查找覆盖指定偏移的方法(行标记/生成动作:宏行至签名闭括号均可命中) */
    fun findMethodAt(vf: VirtualFile, offset: Int): MethodLocation? {
        val daos = getParsed(vf) ?: return null
        for (dao in daos) {
            val m = dao.methods.lastOrNull {
                val end = if (it.sigEndOffset > 0) it.sigEndOffset + 1 else it.fnOffset + it.fnName.length
                offset >= it.macroOffset && offset <= end
            }
            if (m != null) return MethodLocation(vf, dao, m)
        }
        return null
    }

    /** 单文件刷新(创建/修改/重命名/删除时由文件监听器调用) */
    fun refreshFile(vf: VirtualFile) {
        fileCache.remove(vf)
        namespaceIndex.entries.removeIf { it.value.file == vf }
        typeIndex.entries.removeIf { it.value.file == vf }
        if (vf.isValid && vf.extension == "rs") {
            indexFile(vf)
        }
    }

    /** 清理已失效文件对应的索引项 */
    private fun dropStaleEntries() {
        namespaceIndex.entries.removeIf { !it.value.file.isValid }
    }

    companion object {
        fun getInstance(project: Project): RustDaoIndex =
            project.getService(RustDaoIndex::class.java)
    }
}
