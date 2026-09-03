package com.hirust.mapper.navigation

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag

/**
 * 输入时自动弹出补全(mapper XML 侧)。
 *
 * 平台默认在 XML 属性值中不会自动唤起引用补全(需 Ctrl+Space);
 * 本处理器在用户于 `<mapper namespace="...">` 或语句 `id="..."` 属性值内
 * 输入标识符字符时,主动调度自动补全弹窗(scheduleAutoPopup),
 * 候选项仍由 [XmlNamespaceToDaoReference] / [XmlStatementIdToMethodReference]
 * 的 getVariants 提供(未使用的 namespace / 方法 id)。
 *
 * 注册:无 language 属性的 `typedHandler` 全局扩展点(避开语言扩展点
 * 在 RustRover 2026.2 不生效的问题,见 PLAN.md 踩坑记录)。
 */
class XmlMapperCompletionPopupHandler : TypedHandlerDelegate() {

    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (!c.isLetterOrDigit() && c != '_') return Result.CONTINUE
        val vFile = editor.virtualFile ?: return Result.CONTINUE
        if (vFile.extension != "xml") return Result.CONTINUE
        LOG.info("[hirust-mapper-navigator] TP charTyped '$c' in ${vFile.name}")

        // 延迟到字符落盘、PSI 提交后再判定光标上下文,并直接显示原生 lookup。
        // 说明:AutoPopupController 调度后补全会话从不咨询我们的
        // CompletionContributor(1.2.17 日志:CC invoked 零次),
        // 故改用 LookupManager.showLookup 直接显示原生补全弹窗(十年级稳定 API)。
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || editor.isDisposed) return@invokeLater
            try {
                PsiDocumentManager.getInstance(project).commitAndRunReadAction {
                    val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                        ?: return@commitAndRunReadAction
                    val offset = editor.caretModel.offset
                    val leaf = psiFile.findElementAt((offset - 1).coerceAtLeast(0))
                        ?: return@commitAndRunReadAction
                    val attrValue = leaf.parent as? XmlAttributeValue ?: return@commitAndRunReadAction
                    val attr = attrValue.parent as? XmlAttribute ?: return@commitAndRunReadAction
                    val tag = attr.parent as? XmlTag ?: return@commitAndRunReadAction

                    // 输入前缀(属性值起点到光标)
                    val valueText = attrValue.value ?: ""
                    val valueStart = attrValue.textRange.startOffset + 1
                    val prefixLen = (offset - valueStart).coerceIn(0, valueText.length)
                    val prefix = valueText.substring(0, prefixLen)

                    val items: List<LookupElement> = when {
                        tag.name == "mapper" && attr.name == "namespace" ->
                            XmlMapperCompletionItems.namespaceItems(project).map { it as LookupElement }
                        tag.name in XmlMapperParser.STATEMENT_TAGS && attr.name == "id" ->
                            XmlMapperCompletionItems.statementIdItems(
                                project, psiFile.virtualFile, tag.name, valueText
                            ).map { it as LookupElement }
                        else -> emptyList()
                    }
                    if (items.isNotEmpty()) {
                        // 延迟显示:错开平台内置自动补全的启动窗口
                        // (立即显示会被随后的内置会话清空/隐藏 —— 表现为"闪现即消失")
                        SHOW_ALARM.cancelAllRequests()
                        SHOW_ALARM.addRequest({
                            if (project.isDisposed || editor.isDisposed) return@addRequest
                            try {
                                val lookup = LookupManager.getInstance(project)
                                    .showLookup(editor, items.toTypedArray(), prefix)
                                LOG.info("[hirust-mapper-navigator] TP lookup shown " +
                                        "(active=${LookupManager.getInstance(project).activeLookup != null})")
                                // 插入后修复 PSI:补全插入与延迟弹窗竞争会导致 XML 增量重解析
                                // 区间错乱(表现为 namespace 引用区间被拉伸到后续标签),
                                // 在插入后的下一次文档变更时强制全量重解析消除错乱。
                                schedulePostInsertReparse(project, editor)
                            } catch (e: Exception) {
                                LOG.warn("[hirust-mapper-navigator] TP show failed: ${e.message}")
                            }
                        }, SHOW_DELAY_MS)
                    }
                }
            } catch (e: Exception) {
                LOG.warn("[hirust-mapper-navigator] TP failed: ${e.message}")
            }
        }, project.disposed)
        return Result.CONTINUE
    }

    /**
     * 弹窗显示后挂一次性文档监听:插入(Enter/Tab)引发的文档变更后,
     * 提交文档并强制该文件全量重解析,消除增量重解析的区间错乱。
     */
    private fun schedulePostInsertReparse(project: Project, editor: Editor) {
        val doc = editor.document
        val listener = object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                doc.removeDocumentListener(this)
                ApplicationManager.getApplication().invokeLater({
                    if (project.isDisposed || editor.isDisposed) return@invokeLater
                    try {
                        PsiDocumentManager.getInstance(project).commitDocument(doc)
                        val vf = editor.virtualFile
                        if (vf != null) {
                            com.intellij.util.FileContentUtilCore.reparseFiles(vf)
                            LOG.info("[hirust-mapper-navigator] TP post-insert reparse done: ${vf.name}")
                        }
                    } catch (e: Exception) {
                        LOG.warn("[hirust-mapper-navigator] TP reparse failed: ${e.message}")
                    }
                }, project.disposed)
            }
        }
        doc.addDocumentListener(listener)
    }

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger
            .getInstance("HirustCompletionDiag")
        private val SHOW_ALARM = com.intellij.util.Alarm()
        private const val SHOW_DELAY_MS = 250
    }
}
