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

    /** 文件级解析缓存:VirtualFile -> (modificationStamp, daos) */
    private val fileCache = ConcurrentHashMap<VirtualFile, Pair<Long, List<DaoInfo>>>()

    /** namespace -> DAO 位置 */
    private val namespaceIndex = ConcurrentHashMap<String, DaoLocation>()

    @Volatile
    private var initialized = false

    fun ensureInitialized() {
        if (!initialized) rebuildIndex()
    }

    fun rebuildIndex() {
        namespaceIndex.clear()
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
                "($methodCount methods) from ${files.size} rs files; " +
                namespaceIndex.keys.joinToString(", ", limit = 5))
    }

    private fun indexFile(vf: VirtualFile) {
        if (!vf.isValid) return
        val daos = getParsed(vf) ?: return
        for (dao in daos) {
            namespaceIndex[dao.namespace] = DaoLocation(vf, dao)
        }
    }

    /** 解析并缓存单个 .rs 文件;非 .rs 或读取失败返回 null */
    fun getParsed(vf: VirtualFile): List<DaoInfo>? {
        if (!vf.isValid || vf.extension != "rs") return null
        val stamp = vf.modificationStamp
        fileCache[vf]?.let { (s, daos) ->
            if (s == stamp) return daos
        }
        val content = try {
            // 必须保留原始行尾(\r\n),否则 DAO/方法偏移会向下漂移
            NavigationUtil.loadTextRaw(vf) ?: return null
        } catch (e: Exception) {
            log.warn("[hirust-mapper-navigator] Failed to read ${vf.path}: ${e.message}")
            return null
        }
        val daos = if (content.contains("#[dao")) RustSourceParser.parse(content) else emptyList()
        fileCache[vf] = stamp to daos
        return daos
    }

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

    /** 查找覆盖指定偏移的方法(行标记:fn 锚点 / Rust id 字面量引用) */
    fun findMethodAt(vf: VirtualFile, offset: Int): MethodLocation? {
        val daos = getParsed(vf) ?: return null
        for (dao in daos) {
            val m = dao.methods.lastOrNull {
                offset >= it.macroOffset && offset <= it.fnOffset + it.fnName.length
            }
            if (m != null) return MethodLocation(vf, dao, m)
        }
        return null
    }

    /** 单文件刷新(创建/修改/重命名/删除时由文件监听器调用) */
    fun refreshFile(vf: VirtualFile) {
        fileCache.remove(vf)
        namespaceIndex.entries.removeIf { it.value.file == vf }
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
