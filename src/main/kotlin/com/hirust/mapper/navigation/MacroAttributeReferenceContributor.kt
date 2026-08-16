package com.hirust.mapper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.util.ProcessingContext

/**
 * RustRover 引用贡献者 — 插件入口点。
 *
 * 通过 PsiReferenceRegistrar 的 implicit language matching 注册到 Rust 文件。
 * 在 provider 内部遍历所有 PSI 元素，通过运行时类名筛选目标。
 */
class MacroAttributeReferenceContributor : PsiReferenceContributor() {

    private val log = Logger.getInstance(MacroAttributeReferenceContributor::class.java)

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        val provider = object : PsiReferenceProvider() {
            override fun getReferencesByElement(
                element: PsiElement,
                context: ProcessingContext
            ): Array<PsiReference> {
                // 只处理 Rust 文件中的元素
                val file = element.containingFile ?: return emptyArray()
                val fileExt = file.viewProvider.virtualFile.extension
                if (fileExt != "rs") return emptyArray()

                val typeName = element::class.simpleName ?: return emptyArray()

                return when (typeName) {
                    "RsMetaItem" -> createMetaItemReferences(element)
                    "RsLitExpr" -> createLitExprReferences(element)
                    // 也检查标识符: mapper_query 作为独立标识符可能是 RsIdent
                    "RsIdent" -> createIdentReferences(element)
                    else -> emptyArray()
                }
            }
        }

        // 注册到所有 PsiElement，由 provider 内部过滤
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(), provider)
    }

    /**
     * RsMetaItem: #[mapper_query] / #[dao]
     */
    private fun createMetaItemReferences(element: PsiElement): Array<PsiReference> {
        val macroName = extractMacroName(element) ?: return emptyArray()
        if (!MacroDefinitionReference.isMapperMacro(macroName)) return emptyArray()

        // 确认祖先链中有 RsOuterAttr
        var current: PsiElement? = element.parent
        var depth = 0
        while (current != null && depth < 10) {
            if (current::class.simpleName == "RsOuterAttr") break
            current = current.parent
            depth++
        }
        if (depth >= 10) return emptyArray()

        log.info("[hirust-mapper] MetaItem reference: $macroName at ${element.textRange}")
        return arrayOf(MacroDefinitionReference(element, macroName))
    }

    /**
     * RsIdent: 直接检查标识符文本是否是目标宏名
     */
    private fun createIdentReferences(element: PsiElement): Array<PsiReference> {
        val text = element.text.trim()
        if (!MacroDefinitionReference.isMapperMacro(text)) return emptyArray()

        // 检查是否在 #[...] 属性上下文中
        var current: PsiElement? = element.parent
        var depth = 0
        while (current != null && depth < 10) {
            val name = current::class.simpleName ?: ""
            if (name == "RsOuterAttr" || name == "RsAttr" || name == "RsMetaItem") {
                log.info("[hirust-mapper] Ident reference: $text at ${element.textRange}")
                return arrayOf(MacroDefinitionReference(element, text))
            }
            current = current.parent
            depth++
        }
        return emptyArray()
    }

    /**
     * RsLitExpr: namespace 字符串
     */
    private fun createLitExprReferences(element: PsiElement): Array<PsiReference> {
        val text = element.text
        if (!text.startsWith("\"") || !text.endsWith("\"") || text.length < 3) return emptyArray()

        val value = text.substring(1, text.length - 1)
        if (!value.contains("::")) return emptyArray()
        if (!isNamespaceContext(element)) return emptyArray()

        log.info("[hirust-mapper] LitExpr reference: $value at ${element.textRange}")
        return arrayOf(NamespaceToXmlReference(element))
    }

    private fun extractMacroName(element: PsiElement): String? {
        val text = element.text
        val parenIndex = text.indexOf('(')
        val spaceIndex = text.indexOf(' ')
        val endIndex = when {
            parenIndex < 0 && spaceIndex < 0 -> text.length
            parenIndex < 0 -> spaceIndex
            spaceIndex < 0 -> parenIndex
            else -> minOf(parenIndex, spaceIndex)
        }
        return text.substring(0, endIndex).trim().takeIf { it.isNotEmpty() }
    }

    private fun isNamespaceContext(element: PsiElement): Boolean {
        var current: PsiElement? = element.parent
        var depth = 0
        while (current != null && depth < 15) {
            val name = current::class.simpleName ?: ""
            if (name == "RsMetaItem") {
                val metaText = current.text
                if ((metaText.startsWith("dao") || metaText.startsWith("mapper"))
                    && metaText.contains("namespace")) return true
            }
            if (name == "RsConstant") {
                val firstChild = current.firstChild
                if (firstChild?.text?.contains("namespace", ignoreCase = true) == true) return true
            }
            if (name == "RsCallExpr" || name == "RsMethodCall") return true
            current = current.parent
            depth++
        }
        return false
    }
}
