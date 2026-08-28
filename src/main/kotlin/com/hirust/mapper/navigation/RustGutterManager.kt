package com.hirust.mapper.navigation

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * Rust 侧 gutter 图标(程序化注册通道)。
 *
 * 背景:`codeInsight.lineMarkerProvider` 以 language="ANY"/"RUST" 注册在 RustRover 2026.2
 * 中不会被咨询(language="XML" 正常)。故改在 .rs 编辑器打开时,通过
 * `MarkupModel.addLineHighlighter` + `RangeHighlighter.setGutterIconRenderer`
 * 注册可点击图标 —— 完全绕开语言扩展点,仅使用公共 API。
 *
 * 注册方式:projectListeners 中的 [FileEditorManagerListener](与已验证生效的
 * BulkFileListener 同一注册体系);文件打开/切换时绘制。
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
        // P2:文件与文档均未变化时跳过重绘(切换标签不再反复增删 highlighter)
        val stamp = editor.document.modificationStamp
        if (editor.getUserData(PAINTED_STAMP_KEY) == stamp &&
            editor.getUserData(CURRENT_FILE_KEY) == vFile
        ) return

        val daos = try {
            // getParsed 内部按 Document 坐标(\n 归一化)解析,
            // 因此这里用 document 行号寻址时偏移天然对齐
            RustDaoIndex.getInstance(project).getParsed(vFile) ?: return
        } catch (e: Exception) {
            log.warn("[hirust-mapper-navigator] Gutter parse failed: ${e.message}")
            return
        }

        val document = editor.document
        val markup = editor.markupModel
        // 清掉旧标记(在 isEmpty 判断之前:删光 #[dao] 后图标不能残留)
        val old = editor.getUserData(RENDERED_KEY)
        if (old != null) {
            old.forEach { markup.removeHighlighter(it) }
        }
        val oldUnderlines = editor.getUserData(UNDERLINE_KEY)
        if (oldUnderlines != null) {
            oldUnderlines.forEach { markup.removeHighlighter(it) }
        }

        if (daos.isEmpty()) {
            editor.putUserData(RENDERED_KEY, mutableListOf())
            editor.putUserData(LINK_RANGES_KEY, mutableListOf())
            editor.putUserData(CURRENT_FILE_KEY, vFile)
            editor.putUserData(PAINTED_STAMP_KEY, stamp)
            return
        }

        // 单次解析:dao→XML 文件、method→XML 语句;gutter 图标与悬停区间复用同一结果
        val xmlIndex = XmlNamespaceIndex.getInstance(project)
        val daoXml = HashMap<DaoInfo, VirtualFile?>()
        val methodStmt = HashMap<MethodInfo, XmlNamespaceIndex.XmlStatementLocation?>()
        for (dao in daos) {
            daoXml[dao] = xmlIndex.findXmlFile(dao.namespace)
            for (m in dao.methods) {
                methodStmt[m] = xmlIndex.findStatement(dao.namespace, m.id, m.stmtTag)
            }
        }

        // 收集可跳转区间(namespace/id 字面量 + impl 名/fn 名/宏名),
        // 供 Ctrl+悬停动态绘制超链接样式;仅收录可解析项(能力与视觉提示一致)
        val linkRanges = mutableListOf<com.intellij.openapi.util.TextRange>()
        fun addLinkRange(start: Int, end: Int) {
            val s = start.coerceIn(0, document.textLength)
            val e = end.coerceIn(s, document.textLength)
            if (e > s) linkRanges += com.intellij.openapi.util.TextRange(s, e)
        }
        for (dao in daos) {
            if (daoXml[dao] != null) {
                if (dao.nsLiteralOffset >= 0 && dao.namespace.isNotEmpty()) {
                    addLinkRange(dao.nsLiteralOffset, dao.nsLiteralOffset + dao.namespace.length)
                }
                if (dao.implNameOffset >= 0 && dao.implName.isNotEmpty()) {
                    addLinkRange(dao.implNameOffset, dao.implNameOffset + dao.implName.length)
                }
            }
            for (m in dao.methods) {
                if (methodStmt[m] != null) {
                    if (m.idLiteralOffset >= 0 && m.id.isNotEmpty()) {
                        addLinkRange(m.idLiteralOffset, m.idLiteralOffset + m.id.length)
                    }
                    addLinkRange(m.fnOffset, m.fnOffset + m.fnName.length)
                    addLinkRange(m.macroNameOffset, m.macroNameOffset + m.macroName.length)
                }
            }
        }
        editor.putUserData(LINK_RANGES_KEY, linkRanges)
        attachHover(editor)

        val created = mutableListOf<RangeHighlighter>()
        fun addMarker(lineOffset: Int, tooltip: String, nav: () -> Unit) {
            val line = document.getLineNumber(lineOffset.coerceIn(0, document.textLength - 1))
            val rh = markup.addLineHighlighter(
                line,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                TextAttributes()
            ) ?: return
            rh.gutterIconRenderer = NavIconRenderer(Icons.TO_XML, tooltip, nav)
            created.add(rh)
        }

        for (dao in daos) {
            // impl 行图标 → XML <mapper>
            val xmlFile = daoXml[dao] ?: continue
            addMarker(dao.implOffset, "跳转到 XML mapper: ${xmlFile.name}") {
                val target = xmlIndex.getMapperInfo(xmlFile)?.mapperTagOffset ?: 0
                OpenFileDescriptor(project, xmlFile, target).navigate(true)
            }
            // 方法宏行图标 → XML 语句(偏移已按 Document 坐标归一化)
            for (m in dao.methods) {
                val stmt = methodStmt[m] ?: continue
                addMarker(m.macroOffset, "跳转到 XML <${stmt.statement.tag} id=\"${stmt.statement.id}\">") {
                    OpenFileDescriptor(
                        project, stmt.file,
                        stmt.statement.idAttrOffset.takeIf { it >= 0 } ?: stmt.statement.tagOffset
                    ).navigate(true)
                }
            }
        }
        editor.putUserData(RENDERED_KEY, created)
        editor.putUserData(CURRENT_FILE_KEY, vFile)
        editor.putUserData(PAINTED_STAMP_KEY, stamp)
        attachDocumentListener(editor)
        log.debug("[hirust-mapper-navigator] Gutter painted: ${created.size} markers in ${vFile.name}")
    }

    /**
     * P3:文档变更时重绘图标与悬停下划线(此前需切换标签才刷新)。
     * 每编辑器仅挂一次监听;重绘经 invokeLater 回到 EDT。
     */
    private fun attachDocumentListener(editor: Editor) {
        if (editor.getUserData(DOC_LISTENER_ATTACHED_KEY) == true) return
        editor.putUserData(DOC_LISTENER_ATTACHED_KEY, true)
        editor.document.addDocumentListener(object :
            com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                val vf = editor.getUserData(CURRENT_FILE_KEY) ?: return
                ApplicationManager.getApplication().invokeLater({
                    if (!editor.isDisposed) install(editor, vf)
                }, project.disposed)
            }
        })
    }

    // ------------------------------------------------------------------
    // Ctrl+悬停动态超链接(复刻原生引用交互:按住 Ctrl 才出现下划线 + 手型光标)
    // ------------------------------------------------------------------

    private fun attachHover(editor: Editor) {
        if (editor.getUserData(HOVER_ATTACHED_KEY) == true) return
        editor.putUserData(HOVER_ATTACHED_KEY, true)
        editor.addEditorMouseMotionListener(object :
            com.intellij.openapi.editor.event.EditorMouseMotionListener {
            override fun mouseMoved(e: com.intellij.openapi.editor.event.EditorMouseEvent) =
                handleHover(e)

            override fun mouseDragged(e: com.intellij.openapi.editor.event.EditorMouseEvent) {}
        })
    }

    private fun handleHover(e: com.intellij.openapi.editor.event.EditorMouseEvent) {
        val editor = e.editor
        val ctrlDown = (e.mouseEvent.modifiersEx and java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0
        val ranges = editor.getUserData(LINK_RANGES_KEY)
        if (!ctrlDown || ranges.isNullOrEmpty()) {
            clearHover(editor)
            return
        }
        val offset = e.offset
        if (offset < 0) {
            clearHover(editor)
            return
        }
        val hit = ranges.firstOrNull { offset >= it.startOffset && offset < it.endOffset }
        if (hit == null) {
            clearHover(editor)
            return
        }
        val current = editor.getUserData(HOVER_HL_KEY)
        if (current?.first == hit) return // 已高亮同一区间
        clearHover(editor)
        // 平台原生超链接样式(与 XML→Rust 引用悬停一致)
        val attrs = editor.colorsScheme.getAttributes(
            com.intellij.openapi.editor.colors.EditorColors.REFERENCE_HYPERLINK_COLOR
        )
        // 双端越界防护:文档编辑后、重绘前的窗口期可能命中过期区间
        val textLen = editor.document.textLength
        val hlStart = hit.startOffset.coerceIn(0, textLen)
        val hlEnd = hit.endOffset.coerceIn(hlStart, textLen)
        val hl = editor.markupModel.addRangeHighlighter(
            hlStart, hlEnd,
            HighlighterLayer.HYPERLINK, attrs,
            com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE
        )
        editor.putUserData(HOVER_HL_KEY, hit to hl)
        // 手型光标:EditorEx 不在可编译 API 上,直接设置 contentComponent 的光标,
        // 并记录原光标以便恢复
        val comp = editor.contentComponent
        if (editor.getUserData(PREV_CURSOR_KEY) == null) {
            editor.putUserData(PREV_CURSOR_KEY, comp.cursor)
        }
        comp.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    }

    private fun clearHover(editor: Editor) {
        editor.getUserData(HOVER_HL_KEY)?.second?.let {
            try {
                editor.markupModel.removeHighlighter(it)
            } catch (_: Exception) {
            }
        }
        editor.putUserData(HOVER_HL_KEY, null)
        editor.getUserData(PREV_CURSOR_KEY)?.let { prev ->
            try {
                editor.contentComponent.cursor = prev
            } catch (_: Exception) {
            }
        }
        editor.putUserData(PREV_CURSOR_KEY, null)
    }

    /** 带点击跳转动作的 gutter 图标渲染器(仅使用公共 API) */
    private class NavIconRenderer(
        private val icon: Icon,
        private val tooltip: String,
        nav: () -> Unit
    ) : GutterIconRenderer() {

        private val action = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                nav()
            }
        }

        override fun getIcon(): Icon = icon
        override fun getTooltipText(): String? = tooltip
        override fun getClickAction(): AnAction? = action
        override fun equals(other: Any?): Boolean = other === this
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    companion object {
        private val RENDERED_KEY = Key.create<MutableList<RangeHighlighter>>(
            "hirust.mapper.navigator.gutter.markers"
        )
        private val UNDERLINE_KEY = Key.create<MutableList<RangeHighlighter>>(
            "hirust.mapper.navigator.link.underlines"
        )
        private val LINK_RANGES_KEY = Key.create<MutableList<com.intellij.openapi.util.TextRange>>(
            "hirust.mapper.navigator.link.ranges"
        )
        private val HOVER_HL_KEY =
            Key.create<Pair<com.intellij.openapi.util.TextRange, RangeHighlighter>>(
                "hirust.mapper.navigator.hover.hl"
            )
        private val HOVER_ATTACHED_KEY = Key.create<Boolean>(
            "hirust.mapper.navigator.hover.attached"
        )
        private val PREV_CURSOR_KEY = Key.create<java.awt.Cursor>(
            "hirust.mapper.navigator.hover.prevCursor"
        )
        private val PAINTED_STAMP_KEY = Key.create<Long>(
            "hirust.mapper.navigator.painted.stamp"
        )
        private val CURRENT_FILE_KEY = Key.create<VirtualFile>(
            "hirust.mapper.navigator.current.file"
        )
        private val DOC_LISTENER_ATTACHED_KEY = Key.create<Boolean>(
            "hirust.mapper.navigator.doc.listener.attached"
        )
    }
}
