package com.hirust.mapper.navigation

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.impl.source.resolve.ResolveCache
import org.rust.lang.core.psi.RsLitExpr

/**
 * namespace 字符串到 XML 文件的引用。
 *
 * 当用户在 #[dao(namespace = "crate::app::dao::privilege_project_dao")] 中
 * Ctrl+Click namespace 字符串值时，跳转到对应的 XML 文件。
 *
 * PSI 位置: RsLitExpr 节点（字符串字面量 "crate::app::dao::..."）
 *
 * 同时提供自动补全支持：输入 namespace 字符串时提示可用的 namespace。
 */
class NamespaceToXmlReference(element: PsiElement) :
    PsiReferenceBase<PsiElement>(element, getTextRange(element)) {

    private val log = Logger.getInstance(NamespaceToXmlReference::class.java)

    /** 缓存的 namespace 字符串值 */
    private val namespaceText: String? = extractNamespaceText(element)

    override fun resolve(): PsiElement? {
        val ns = namespaceText ?: return null
        if (!NamespacePathResolver.isValidNamespace(ns)) return null

        val xmlFile = NamespacePathResolver.resolve(element.project, ns) ?: return null

        return com.intellij.psi.PsiManager.getInstance(element.project).findFile(xmlFile)
    }

    override fun getVariants(): Array<LookupElement> {
        val index = XmlNamespaceIndex.getInstance(element.project)
        return index.getAllNamespaces()
            .map { ns ->
                val xmlFile = index.getXmlFileByNamespace(ns)
                val filePath = xmlFile?.path ?: "unknown"
                LookupElementBuilder.create(ns)
                    .withTypeText(filePath.substringAfterLast("/"))
                    .withTailText(" (${filePath})", true)
            }
            .toTypedArray()
    }

    override fun handleElementRename(newElementName: String): PsiElement {
        // 支持重命名: 修改 namespace 字符串值
        // 这会同步更新 XML 文件的 namespace 属性（如果实现）
        return super.handleElementRename(newElementName)
    }

    companion object {
        /**
         * 从 RsLitExpr 中提取字符串值（去除引号）
         */
        fun extractNamespaceText(element: PsiElement): String? {
            val text = element.text
            if (text.startsWith("\"") && text.endsWith("\"")) {
                return text.substring(1, text.length - 1)
            }
            return null
        }

        /**
         * 计算引用的文本范围（仅高亮字符串内容部分，不包括引号）
         */
        fun getTextRange(element: PsiElement): TextRange {
            val text = element.text
            // 跳过开头和结尾的引号，只标记字符串内容
            if (text.startsWith("\"") && text.endsWith("\"") && text.length >= 2) {
                val offsetInElement = element.textRange.startOffset
                return TextRange(offsetInElement + 1, offsetInElement + text.length - 1)
            }
            return element.textRange
        }

        /**
         * 判断 PsiElement 是否适合创建 NamespaceToXmlReference
         *
         * 条件：
         * 1. 必须是 RsLitExpr（字符串字面量）
         * 2. 父节点必须是 #[dao(namespace = "...")] 中的键值对
         * 3. 字符串值必须包含 "::"（看起来像 Rust 路径）
         */
        fun canCreateFor(element: PsiElement): Boolean {
            if (element !is RsLitExpr) return false

            val text = element.text
            if (!text.startsWith("\"") || !text.endsWith("\"")) return false
            val value = text.substring(1, text.length - 1)
            if (!value.contains("::")) return false

            // 检查是否在 dao 属性的 namespace 参数中
            val parent = element.parent?.parent
            // 粗略检查：祖先链中应包含 "namespace" 标识符
            return hasNamespaceAncestor(element)
        }

        /**
         * 检查元素的祖先链中是否包含 "namespace" 标识符
         */
        private fun hasNamespaceAncestor(element: PsiElement): Boolean {
            var current = element.parent
            var depth = 0
            while (current != null && depth < 10) {
                if (current.textContains(':') && current.text.contains("namespace")) {
                    // 更精确地检查是否是 "namespace" 键名
                    val children = current.children
                    for (child in children) {
                        if (child.text == "namespace") return true
                    }
                }
                // 也直接检查兄弟节点
                for (sibling in current.children) {
                    if (sibling.text == "namespace") return true
                }
                current = current.parent
                depth++
            }
            // 兜底：检查父级文本中是否包含 namespace 关键字
            val parentText = element.parent?.parent?.text ?: return false
            return parentText.contains("namespace")
        }
    }
}
