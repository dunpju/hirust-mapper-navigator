package com.hirust.mapper.navigation

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag

/**
 * mapper XML 属性值补全(专用 CompletionContributor,language="XML" 通道)。
 *
 * 背景:引用的 getVariants 在自动唤起的补全会话中不被调用
 * (scheduleAutoPopup 已调度但变体从未被查询,见 1.2.15 诊断日志),
 * 故改为专用贡献者直接供候选 —— 自动弹窗与 Ctrl+Space 均走本类:
 * - `<mapper namespace="...">` → 项目全部已索引 DAO 的 namespace
 * - `<select|insert|update|delete id="...">` → 对应 DAO 中标签类型匹配
 *   且未被本文件其他语句/片段占用的方法 id
 *
 * 输入时自动唤起由 [XmlMapperCompletionPopupHandler] 调度。
 */
class XmlMapperCompletionContributor : CompletionContributor() {

    private val log = Logger.getInstance(XmlMapperCompletionContributor::class.java)

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val pos = parameters.position
        // 补全位置可能是插入了 dummy 标识符的副本,沿父链找属性值
        val attrValue = generateSequence<PsiElement>(pos) { it.parent }
            .take(6).mapNotNull { it as? XmlAttributeValue }.firstOrNull() ?: return
        val attr = attrValue.parent as? XmlAttribute ?: return
        val tag = attr.parent as? XmlTag ?: return
        val project = pos.project

        val items: List<LookupElementBuilder> = when {
            tag.name == "mapper" && attr.name == "namespace" ->
                XmlMapperCompletionItems.namespaceItems(project)
            tag.name in XmlMapperParser.STATEMENT_TAGS && attr.name == "id" ->
                XmlMapperCompletionItems.statementIdItems(
                    project, parameters.originalFile.virtualFile, tag.name, attrValue.value ?: ""
                )
            else -> emptyList()
        }
        if (items.isNotEmpty()) {
            result.addAllElements(items)
        }
    }
}

/** mapper XML 属性值补全候选计算(补全贡献者与输入处理器共用) */
object XmlMapperCompletionItems {

    /** namespace 候选:项目全部已索引 DAO 的 namespace */
    fun namespaceItems(project: com.intellij.openapi.project.Project): List<LookupElementBuilder> {
        XmlNamespaceIndex.getInstance(project).ensureInitialized()
        return RustDaoIndex.getInstance(project).allDaos()
            .distinctBy { it.dao.namespace }
            .map { loc ->
                LookupElementBuilder.create(loc.dao.namespace)
                    .withIcon(Icons.TO_RUST)
                    .withTypeText(loc.dao.implName.ifEmpty { "dao" })
                    .withTailText("  (${loc.file.name})", true)
            }
    }

    /**
     * 语句 id 候选:当前 mapper 的 namespace 对应 DAO 中,
     * 标签类型匹配且未被本文件其他语句/片段占用的方法 id。
     * 索引未收录该文件时兜底直读解析(消除对索引时序的依赖)。
     */
    fun statementIdItems(
        project: com.intellij.openapi.project.Project,
        vFile: com.intellij.openapi.vfs.VirtualFile?,
        tagName: String,
        currentId: String
    ): List<LookupElementBuilder> {
        if (vFile == null) return emptyList()
        val indexed = XmlNamespaceIndex.getInstance(project).getMapperInfo(vFile)
        val info = indexed ?: run {
            // 兜底:直读当前文件解析 namespace 与已用 id(文件刚打开/索引未收录时)
            val content = try {
                NavigationUtil.loadTextDocumentAligned(vFile)
            } catch (_: Exception) {
                null
            } ?: return emptyList()
            XmlMapperParser.parse(content) ?: return emptyList()
        }
        val daoLoc = RustDaoIndex.getInstance(project).findDaoByNamespace(info.namespace) ?: return emptyList()
        val usedIds = (info.statements.map { it.id } + info.sqlFragments.map { it.id })
            .toSet() - currentId
        return daoLoc.dao.methods
            .filter { it.stmtTag == tagName && it.id !in usedIds }
            .map { m ->
                LookupElementBuilder.create(m.id)
                    .withIcon(Icons.TO_RUST)
                    .withTypeText("fn ${m.fnName}")
                    .withTailText("  <${m.stmtTag}>", true)
            }
    }
}
