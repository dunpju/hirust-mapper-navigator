package com.hirust.mapper.navigation

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceBase

class NamespaceToXmlReference(element: PsiElement) :
    PsiReferenceBase<PsiElement>(element, getTextRange(element)) {

    private val log = Logger.getInstance(NamespaceToXmlReference::class.java)
    private val namespaceText: String? = extractNamespaceText(element)

    override fun resolve(): PsiElement? {
        val ns = namespaceText ?: return null
        if (!NamespacePathResolver.isValidNamespace(ns)) return null
        val xmlFile = NamespacePathResolver.resolve(element.project, ns) ?: return null
        return PsiManager.getInstance(element.project).findFile(xmlFile)
    }

    override fun getVariants(): Array<LookupElement> {
        val index = XmlNamespaceIndex.getInstance(element.project)
        return index.getAllNamespaces()
            .map { ns ->
                val xmlFile = index.getXmlFileByNamespace(ns)
                val filePath = xmlFile?.path ?: "unknown"
                LookupElementBuilder
                    .create(ns as Any)
                    .withTypeText(filePath.substringAfterLast("/"))
                    .withTailText(" ($filePath)", true)
            }
            .toTypedArray()
    }

    companion object {
        fun extractNamespaceText(element: PsiElement): String? {
            val text = element.text
            if (text.startsWith("\"") && text.endsWith("\"")) {
                return text.substring(1, text.length - 1)
            }
            return null
        }

        fun getTextRange(element: PsiElement): TextRange {
            val text = element.text
            if (text.startsWith("\"") && text.endsWith("\"") && text.length >= 2) {
                val offset = element.textRange.startOffset
                return TextRange(offset + 1, offset + text.length - 1)
            }
            return element.textRange
        }

        fun canCreateFor(element: PsiElement): Boolean {
            if (element::class.simpleName != "RsLitExpr") return false
            val text = element.text
            if (!text.startsWith("\"") || !text.endsWith("\"")) return false
            val value = text.substring(1, text.length - 1)
            if (!value.contains("::")) return false
            return hasNamespaceAncestor(element)
        }

        private fun hasNamespaceAncestor(element: PsiElement): Boolean {
            var current: PsiElement? = element.parent
            var depth = 0
            while (current != null && depth < 10) {
                if (current.text.contains("namespace")) {
                    for (child in current.children) {
                        if (child.text == "namespace") return true
                    }
                }
                current = current.parent
                depth++
            }
            val parentText = element.parent?.parent?.text ?: return false
            return parentText.contains("namespace")
        }
    }
}
