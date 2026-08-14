package com.hirust.mapper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.util.ProcessingContext

/**
 * RustRover 引用贡献者 — 插件入口点。
 *
 * plugin.xml 中已声明 language="Rust"，因此只在 Rust 文件的 PSI 元素上触发。
 * 这里使用宽泛的 PsiElement 匹配，在 provider 内部通过运行时类名
 * 筛选目标节点 (RsMetaItem / RsLitExpr)。
 */
class MacroAttributeReferenceContributor : PsiReferenceContributor() {

    private val log = Logger.getInstance(MacroAttributeReferenceContributor::class.java)

    companion object {
        private const val RS_META_ITEM = "RsMetaItem"
        private const val RS_LIT_EXPR = "RsLitExpr"
    }

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        // 匹配所有 PsiElement，由 plugin.xml 的 language="Rust" 限制到 Rust 文件
        // 在 provider 内部通过 ::class.simpleName 判断具体类型
        val elementPattern = PlatformPatterns.psiElement(PsiElement::class.java)

        registrar.registerReferenceProvider(
            elementPattern,
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext
                ): Array<PsiReference> {
                    val typeName = element::class.simpleName ?: return emptyArray()

                    return when (typeName) {
                        RS_META_ITEM -> createMetaItemReferences(element)
                        RS_LIT_EXPR -> createLitExprReferences(element)
                        else -> emptyArray()
                    }
                }
            }
        )
    }

    /**
     * RsMetaItem: #[mapper_query] / #[dao]
     * → MacroDefinitionReference
     */
    private fun createMetaItemReferences(element: PsiElement): Array<PsiReference> {
        val macroName = extractMacroName(element) ?: return emptyArray()
        if (!MacroDefinitionReference.isMapperMacro(macroName)) return emptyArray()

        // 确认是 #[...] 外部属性的一部分
        var current: PsiElement? = element.parent
        var isOuterAttr = false
        var depth = 0
        while (current != null && depth < 10) {
            if (current::class.simpleName == "RsOuterAttr") {
                isOuterAttr = true
                break
            }
            current = current.parent
            depth++
        }
        if (!isOuterAttr) return emptyArray()

        log.debug("[hirust-mapper] Macro reference: $macroName")

        val ident = findIdentifierChild(element)
        return arrayOf(MacroDefinitionReference(ident ?: element, macroName))
    }

    /**
     * RsLitExpr: "crate::app::dao::..."
     * → NamespaceToXmlReference
     */
    private fun createLitExprReferences(element: PsiElement): Array<PsiReference> {
        val text = element.text
        if (!text.startsWith("\"") || !text.endsWith("\"") || text.length < 3) return emptyArray()

        val value = text.substring(1, text.length - 1)
        if (!value.contains("::")) return emptyArray()
        if (!isNamespaceContext(element)) return emptyArray()

        log.debug("[hirust-mapper] Namespace reference: $value")
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
                    && metaText.contains("namespace")) {
                    return true
                }
            }
            if (name == "RsConstant") {
                val firstChild = current.firstChild
                if (firstChild != null && firstChild.text.contains("namespace", ignoreCase = true)) {
                    return true
                }
            }
            if (name == "RsCallExpr" || name == "RsMethodCall") {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun findIdentifierChild(element: PsiElement): PsiElement? {
        for (child in element.children) {
            val name = child::class.simpleName ?: continue
            if (name.contains("Ident") || name.contains("Path")) {
                return child.firstChild ?: child
            }
        }
        return null
    }
}
