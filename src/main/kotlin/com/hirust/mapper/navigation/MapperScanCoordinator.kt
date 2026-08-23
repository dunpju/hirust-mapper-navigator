package com.hirust.mapper.navigation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 索引扫描协调器(P1 性能优化核心)。
 *
 * 此前 RustDaoIndex 与 MapperPathsConfig 各自全量扫描并逐个读取所有 .rs 文件,
 * 且 XmlNamespaceIndex 首次懒加载时还会再触发一轮 —— 首次交互存在双重全项目 IO。
 * 本协调器将三者的重建合并为【单次 .rs 遍历】:
 * 每个文件只读一次内容,同时提取 with_mapper_paths 模式与 DAO 信息,
 * 最后重建 XML 索引(此时 patterns 已就绪,不再重扫 .rs)。
 *
 * 线程模型:rebuildAll 由启动预热(后台线程)调用;
 * 若预热未完成用户已触发交互,各索引的 ensureInitialized 会同步回退到本方法。
 */
class MapperScanCoordinator(private val project: Project) {

    private val log = Logger.getInstance(MapperScanCoordinator::class.java)

    /** 是否已完成至少一次全量扫描 */
    @Volatile
    private var ready = false

    /** 防抖合并窗口内的重建请求是否已排队 */
    private val pendingRebuild = AtomicBoolean(false)

    val isReady: Boolean get() = ready

    /**
     * 全量重建:单次 .rs 遍历同时供给 MapperPathsConfig 与 RustDaoIndex,
     * 随后重建 XML 索引。线程安全(方法级同步),可从任意线程调用;
     * 文件读取包裹 ReadAction。
     */
    @Synchronized
    fun rebuildAll() {
        val app = ApplicationManager.getApplication()
        val rsFiles = app.runReadAction<List<VirtualFile>> {
            val rsType = FileTypeManager.getInstance().getFileTypeByExtension("rs")
            FileTypeIndex.getFiles(rsType, GlobalSearchScope.projectScope(project))
                .filter { it.extension.equals("rs", ignoreCase = true) }
        }

        val pathsConfig = MapperPathsConfig.getInstance(project)
        val daoIndex = RustDaoIndex.getInstance(project)
        pathsConfig.beginScan()
        daoIndex.beginScan()
        for (vf in rsFiles) {
            if (!vf.isValid) continue
            val content = app.runReadAction<String?> {
                NavigationUtil.loadTextDocumentAligned(vf)
            } ?: continue
            pathsConfig.acceptFileContent(content)
            daoIndex.acceptFileContent(vf, content)
        }

        XmlNamespaceIndex.getInstance(project).rebuildIndex()
        ready = true
        log.info("[hirust-mapper-navigator] Coordinated scan done: ${rsFiles.size} rs files")
    }

    /**
     * 防抖重建(P1:VFS 批量 create 事件风暴合并)。
     * 首个请求排队一个延迟任务,窗口内的后续请求合并为一次重建。
     */
    fun scheduleDebouncedRebuild() {
        if (!pendingRebuild.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                Thread.sleep(DEBOUNCE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            pendingRebuild.set(false)
            try {
                rebuildAll()
            } catch (e: Exception) {
                log.warn("[hirust-mapper-navigator] Debounced rebuild failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 1500L

        fun getInstance(project: Project): MapperScanCoordinator =
            project.getService(MapperScanCoordinator::class.java)
    }
}
