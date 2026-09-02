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
                            XmlMapperCompletionItems.namespaceItems(project)
                        tag.name in XmlMapperParser.STATEMENT_TAGS && attr.name == "id" ->
                            XmlMapperCompletionItems.statementIdItems(project, psiFile.virtualFile, tag.name, valueText)
                        else -> emptyList()
                    }
                    if (items.isNotEmpty()) {
                        LookupManager.getInstance(project).showLookup(editor, items.toTypedArray(), prefix)
                    }
                }
            } catch (e: Exception) {
                LOG.warn("[hirust-mapper-navigator] TP failed: ${e.message}")
            }
        }, project.disposed)
        return Result.CONTINUE
    }

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger
            .getInstance("HirustCompletionDiag")
    }
}
