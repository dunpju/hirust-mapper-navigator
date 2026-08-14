package com.hirust.mapper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.*

/**
 * 监听 VirtualFile 变更事件，当 XML 文件被修改/新增/删除时
 * 自动刷新 XmlNamespaceIndex 索引。
 *
 * 替代方案：使用 BulkFileListener 获取批量文件变更事件，性能更好。
 */
class XmlIndexRefreshListener(private val project: Project) : BulkFileListener {

    private val log = Logger.getInstance(XmlIndexRefreshListener::class.java)

    override fun after(events: List<VFileEvent>) {
        val index = XmlNamespaceIndex.getInstance(project)
        var needsRebuild = false

        for (event in events) {
            val file = event.file ?: continue

            if (isRelevantFile(file)) {
                when (event) {
                    is VFileCreateEvent -> {
                        log.info("[hirust-mapper-navigator] XML file created: ${file.path}")
                        index.refreshFile(file)
                    }
                    is VFileDeleteEvent -> {
                        log.info("[hirust-mapper-navigator] XML file deleted: ${file.path}")
                        index.refreshFile(file)
                    }
                    is VFileContentChangeEvent -> {
                        log.info("[hirust-mapper-navigator] XML file changed: ${file.path}")
                        index.refreshFile(file)
                    }
                    is VFilePropertyChangeEvent -> {
                        if (VirtualFile.PROP_NAME == event.propertyName) {
                            log.info("[hirust-mapper-navigator] XML file renamed: ${file.path}")
                            needsRebuild = true
                        }
                    }
                }
            }
        }

        if (needsRebuild) {
            index.rebuildIndex()
        }
    }

    /**
     * 判断文件是否是已索引的 XML 文件
     */
    private fun isRelevantFile(file: VirtualFile): Boolean {
        if (file.extension != "xml") return false
        // 检查是否在已索引的文件集合中，或者路径包含 mapper
        val path = file.path.lowercase()
        return path.contains("mapper") || path.contains("resources")
    }
}
