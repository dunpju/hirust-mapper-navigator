package com.hirust.mapper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent

class XmlIndexRefreshListener(private val project: Project) : BulkFileListener {

    private val log = Logger.getInstance(XmlIndexRefreshListener::class.java)

    override fun after(events: List<VFileEvent>) {
        val index = XmlNamespaceIndex.getInstance(project)
        var needsRebuild = false

        for (event in events) {
            val file = event.file ?: continue

            if (!isRelevantFile(file)) continue

            when (event) {
                is VFileCreateEvent -> {
                    log.info("[hirust-mapper-navigator] XML created: ${file.path}")
                    index.refreshFile(file)
                }
                is VFileDeleteEvent -> {
                    log.info("[hirust-mapper-navigator] XML deleted: ${file.path}")
                    index.refreshFile(file)
                }
                is VFileContentChangeEvent -> {
                    log.info("[hirust-mapper-navigator] XML changed: ${file.path}")
                    index.refreshFile(file)
                }
                is VFilePropertyChangeEvent -> {
                    if (event.propertyName == VirtualFile.PROP_NAME) {
                        log.info("[hirust-mapper-navigator] XML renamed: ${file.path}")
                        needsRebuild = true
                    }
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
