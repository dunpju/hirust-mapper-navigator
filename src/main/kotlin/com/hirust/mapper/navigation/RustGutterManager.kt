package com.hirust.mapper.navigation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile

/**
 * Rust 侧 gutter 图标(程序化注册通道)。
 *
 * 背景:`codeInsight.lineMarkerProvider` 以 language="ANY"/"RUST" 注册在 RustRover 2026.2
 * 中不会被咨询(language="XML" 正常)。故改在 .rs 编辑器打开时,直接通过
 * `editor.gutter.addLineMarkerInfo` 注册图标 —— 完全绕开语言扩展点。
 *
 * 注册方式:projectListeners 中的 [FileEditorManagerListener](与已验证生效的
 * BulkFileListener 同一注册体系),并在文档变更时重绘。
 */
class RustGutterManager(private val project: Project) : FileEditorManagerListener {

    private val log = Logger.getInstance(RustGutterManager::class.java)

    override fun fileOpened(source: com.intellij.openapi.fileEditor.FileEditorManager, file: VirtualFile) {
        val editor = source.selectedTextEditor ?: return
        install(editor, file)
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        // 文件切换后当前编辑器也需要图标
        val editor = event.manager.selectedTextEditor ?: return
        val vFile = event.newFile ?: return
        install(editor, vFile)
    }

    private fun install(editor: Editor, vFile: VirtualFile) {
        if (vFile.extension != "rs") return
        val app = ApplicationManager.getApplication()
        app.invokeLater({
            if (project.isDisposed || editor.isDisposed) return@invokeLater
            app.runReadAction { paintGutter(editor, vFile) }
        }, project.disposed)
    }

    private fun paintGutter(editor: Editor, vFile: VirtualFile) {
        val daos = try {
            RustDaoIndex.getInstance(project).getParsed(vFile) ?: return
        } catch (e: Exception) {
            log.warn("[hirust-mapper-navigator] Gutter parse failed: ${e.message}")
            return
        }
        if (daos.isEmpty()) return

        val document = editor.document
        val markup = editor.markupModel
        // 清掉旧标记后重绘(防重复)
        val old = editor.getUserData(RENDERED_KEY)
        if (old != null) {
            old.forEach { markup.removeHighlighter(it) }
        }
        val created = mutableListOf<com.intellij.openapi.editor.markup.RangeHighlighter>()

        fun addMarker(lineOffset: Int, tooltip: String, nav: () -> Unit) {
            val line = document.getLineNumber(lineOffset.coerceIn(0, document.textLength - 1))
            val rh = markup.addLineHighlight(
                line,
                com.intellij.openapi.editor.markup.HighlighterLayer.ADDITIONAL_SYNTAX,
                com.intellij.openapi.editor.markup.TextAttributes()
            )
            rh.gutterIconRenderer = NavIconRenderer(Icons.TO_XML, tooltip, nav)
            created.add(rh)
        }

        val xmlIndex = XmlNamespaceIndex.getInstance(project)
        for (dao in daos) {
            // impl 行图标 → XML <mapper>
            val xmlFile = xmlIndex.findXmlFile(dao.namespace) ?: continue
            addMarker(dao.implOffset, "跳转到 XML mapper: ${xmlFile.name}") {
                val target = xmlIndex.getMapperInfo(xmlFile)?.mapperTagOffset ?: 0
                OpenFileDescriptor(project, xmlFile, target).navigate(true)
            }
            // 方法行图标 → XML 语句
            for (m in dao.methods) {
                val stmt = xmlIndex.findStatement(dao.namespace, m.id, m.stmtTag) ?: continue
                addMarker(m.macroOffset, "跳转到 XML <${stmt.statement.tag} id=\"${stmt.statement.id}\">") {
                    OpenFileDescriptor(
                        project, stmt.file,
                        stmt.statement.idAttrOffset.takeIf { it >= 0 } ?: stmt.statement.tagOffset
                    ).navigate(true)
                }
            }
        }
        editor.putUserData(RENDERED_KEY, created)
        log.info("[hirust-mapper-navigator] Gutter painted: ${created.size} markers in ${vFile.name}")
    }

    /** 带点击跳转动作的 gutter 图标渲染器(仅使用公共 API) */
    private class NavIconRenderer(
        private val icon: javax.swing.Icon,
        private val tooltip: String,
        nav: () -> Unit
    ) : GutterIconRenderer() {

        private val action = object : com.intellij.openapi.actionSystem.AnAction() {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                nav()
            }
        }

        override fun getIcon(): javax.swing.Icon = icon
        override fun getTooltipText(): String? = tooltip
        override fun getClickAction(): com.intellij.openapi.actionSystem.AnAction? = action
        override fun equals(other: Any?): Boolean = other === this
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    companion object {
        private val RENDERED_KEY = Key.create<MutableList<com.intellij.openapi.editor.markup.RangeHighlighter>>(
            "hirust.mapper.navigator.gutter.markers"
        )
    }
}
