package com.hirust.mapper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

/**
 * 监听 VirtualFile 变更事件，当 XML 文件被修改/新增/删除时
 * 自动刷新 XmlNamespaceIndex 索引。
 */
class XmlIndexRefreshListener(private val project: Project) : BulkFileListener {

    private val log = Logger.getInstance(XmlIndexRefreshListener::class.java)

    override fun after(events: List<VFileEvent>) {
        val index = XmlNamespaceIndex.getInstance(project)
        var needsRebuild = false

        for (event in events) {
            val file = event.file ?: continue

            if (isRelevantFile(file)) {
                if (event.isCreated || event.isDeleted || event.isContentChange) {
                    log.info("[hirust-mapper-navigator] XML file changed: ${file.path}")
                    index.refreshFile(file)
                }
                if (event.isPropertyChange) {
                    needsRebuild = true
                }
            }
        }

        if (needsRebuild) {
            index.rebuildIndex()
        }
    }

    private fun isRelevantFile(file: VirtualFile): Boolean {
        if (file.extension != "xml") return false
        val path = file.path.lowercase()
        return path.contains("mapper") || path.contains("resources")
    }
}
