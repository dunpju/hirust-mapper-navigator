package com.hirust.mapper.navigation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent

/**
 * XML 与 Rust 源码文件变更监听:
 * - XML 变更 → 刷新 [XmlNamespaceIndex](namespace 与语句索引)
 * - .rs 变更 → 刷新 [RustDaoIndex](DAO 与方法索引)
 */
class XmlIndexRefreshListener(private val project: Project) : BulkFileListener {

    private val log = Logger.getInstance(XmlIndexRefreshListener::class.java)

    override fun after(events: List<VFileEvent>) {
        val xmlIndex = XmlNamespaceIndex.getInstance(project)
        val rustIndex = RustDaoIndex.getInstance(project)
        var needsXmlRebuild = false

        for (event in events) {
            val file = event.file ?: continue
            val ext = file.extension?.lowercase()

            when (ext) {
                "rs" -> {
                    when (event) {
                        is VFileCreateEvent -> {
                            rustIndex.refreshFile(file)
                            // 新增 .rs 可能引入 with_mapper_paths 配置;
                            // 分支切换/外部刷新会批量报 create 事件,防抖合并为一次协调重建
                            MapperScanCoordinator.getInstance(project).scheduleDebouncedRebuild()
                        }
                        is VFileDeleteEvent -> rustIndex.refreshFile(file)
                        is VFileContentChangeEvent -> {
                            rustIndex.refreshFile(file)
                            // 修改现有 .rs 中的 with_mapper_paths(glob 增删)也要重建索引;
                            // 仅对内容含该配置的文件触发,普通编辑仍走单文件刷新
                            val mentionsMapperPaths = try {
                                ApplicationManager.getApplication().runReadAction<String?> {
                                    NavigationUtil.loadTextDocumentAligned(file)
                                }?.contains("with_mapper_paths") == true
                            } catch (_: Exception) {
                                false
                            }
                            if (mentionsMapperPaths) {
                                MapperScanCoordinator.getInstance(project).scheduleDebouncedRebuild()
                            }
                        }
                        is VFilePropertyChangeEvent ->
                            if (event.propertyName == VirtualFile.PROP_NAME) rustIndex.refreshFile(file)
                    }
                }
                "xml" -> {
                    if (!isRelevantXml(file)) continue
                    when (event) {
                        is VFileCreateEvent -> {
                            log.info("[hirust-mapper-navigator] XML created: ${file.path}")
                            xmlIndex.refreshFile(file)
                        }
                        is VFileDeleteEvent -> {
                            log.info("[hirust-mapper-navigator] XML deleted: ${file.path}")
                            xmlIndex.refreshFile(file)
                        }
                        is VFileContentChangeEvent -> {
                            log.info("[hirust-mapper-navigator] XML changed: ${file.path}")
                            xmlIndex.refreshFile(file)
                        }
                        is VFilePropertyChangeEvent -> {
                            if (event.propertyName == VirtualFile.PROP_NAME) {
                                log.info("[hirust-mapper-navigator] XML renamed: ${file.path}")
                                needsXmlRebuild = true
                            }
                        }
                    }
                }
            }
        }

        if (needsXmlRebuild) {
            xmlIndex.rebuildIndex()
        }
    }

    private fun isRelevantXml(file: VirtualFile): Boolean {
        val path = file.path.lowercase()
        // 排除 IDE 元数据(本插件项目目录名含 "mapper",.idea/workspace.xml 每次 UI 操作都会变更)
        // 与 Rust 构建产物目录(其中的资源副本会造成无谓索引与误匹配)
        if (path.contains("/.idea/") || path.contains("/target/")) return false
        return path.contains("mapper") || path.contains("resources")
    }
}
