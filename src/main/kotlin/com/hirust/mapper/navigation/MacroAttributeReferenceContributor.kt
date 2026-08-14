package com.hirust.mapper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.util.ProcessingContext

/**
 * RustRover 引用贡献者 — 插件入口点。
 *
 * 使用通用 PsiElement API，编译时不依赖 org.rust.lang 插件。
 * 通过 PSI 元素的文本内容和祖先链关系识别目标节点，
 * 运行时由 RustRover 的 Rust 插件提供实际 PSI 类型。
 *
 * 匹配两类 PSI 元素:
 * 1. 外部属性中的宏标识符: #[mapper_query] / #[dao]
 *    → MacroDefinitionReference
 * 2. namespace 字符串字面量: "crate::app::dao::..."
 *    → NamespaceToXmlReference
 */
class MacroAttributeReferenceContributor : PsiReferenceContributor() {

    private val log = Logger.getInstance(MacroAttributeReferenceContributor::class.java)

    /**
     * Rust 外部属性 PSI 元素类型名。
     *
     * Rust 插件在 PSI 树中将 #[...] 表示为 "RsOuterAttr" 元素，
     * 内部子节点包含 RsMetaItem（属性项）等。
     * 我们无法在编译时引用这些类型，但在运行时可以通过类名匹配。
     */
    companion object {
        const val RS_META_ITEM = "RsMetaItem"
        const val RS_LIT_EXPR = "RsLitExpr"
        const val RS_OUTER_ATTR = "RsOuterAttr"
    }

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        // ==========================================
        // 1. 属性宏名称引用: #[mapper_query] / #[dao]
        // ==========================================
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement().withName(RS_META_ITEM),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext
                ): Array<PsiReference> {
                    return createMetaItemReferences(element)
                }
            }
        )

        // ==========================================
        // 2. Namespace 字符串引用: "crate::app::dao::..."
        // ==========================================
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement().withName(RS_LIT_EXPR),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext
                ): Array<PsiReference> {
                    return createLitExprReferences(element)
                }
            }
        )
    }

    /**
     * 为属性宏标识符创建引用。
     *
     * 匹配逻辑:
     *   - 元素的第一个非空子节点的文本即为宏名称 (e.g. "mapper_query")
     *   - 宏名称必须属于已知的 mapper 系列
     *   - 祖先链中必须存在 RsOuterAttr (表示是 #[...] 外部属性)
     */
    private fun createMetaItemReferences(element: PsiElement): Array<PsiReference> {
        val macroName = extractMacroName(element) ?: return PsiReference.EMPTY_ARRAY

        if (!MacroDefinitionReference.isMapperMacro(macroName)) {
            return PsiReference.EMPTY_ARRAY
        }

        // 确认是外部属性的一部分
        if (!hasAncestorNamed(element, RS_OUTER_ATTR)) {
            return PsiReference.EMPTY_ARRAY
        }

        log.debug("[hirust-mapper-navigator] Macro reference created for: $macroName")

        // 尝试找到宏名称的标识符子节点作为锚点
        val ident = findIdentifierChild(element)
        return arrayOf(MacroDefinitionReference(ident ?: element, macroName))
    }

    /**
     * 为字符串字面量创建引用。
     *
     * 匹配逻辑:
     *   - 文本以 " 开头和结尾
     *   - 去除引号后的值包含 "::" (Rust 路径)
     *   - 祖先链中存在包含 "namespace" 的上下文
     */
    private fun createLitExprReferences(element: PsiElement): Array<PsiReference> {
        val text = element.text

        if (!text.startsWith("\"") || !text.endsWith("\"") || text.length < 3) {
            return PsiReference.EMPTY_ARRAY
        }

        val value = text.substring(1, text.length - 1)
        if (!value.contains("::")) {
            return PsiReference.EMPTY_ARRAY
        }

        if (!isNamespaceContext(element)) {
            return PsiReference.EMPTY_ARRAY
        }

        log.debug("[hirust-mapper-navigator] Namespace-to-XML reference created for: $value")
        return arrayOf(NamespaceToXmlReference(element))
    }

    /**
     * 从 RsMetaItem 元素中提取宏名称。
     *
     * RsMetaItem 的文本结构:
     *   "mapper_query"           → 宏名 = "mapper_query"
     *   "mapper_query(id = \"list\")" → 宏名 = "mapper_query"
     *   "dao(namespace = \"...\")"   → 宏名 = "dao"
     */
    private fun extractMacroName(element: PsiElement): String? {
        val text = element.text
        // 宏名是第一个 "(" 或空格之前的部分
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

    /**
     * 判断字符串字面量是否在 namespace 相关的上下文中。
     *
     * 向上遍历祖先链，检查是否有 RsMetaItem 且其文本包含 "namespace"。
     */
    private fun isNamespaceContext(element: PsiElement): Boolean {
        var current: PsiElement? = element.parent
        var depth = 0
        while (current != null && depth < 15) {
            val name = current::class.simpleName
            when (name) {
                RS_META_ITEM -> {
                    val metaText = current.text
                    // 检查 meta item 的名称是否是 dao/mapper/hirust_mapper
                    // 且参数中包含 namespace
                    if (metaText.startsWith("dao") && metaText.contains("namespace")) {
                        return true
                    }
                    if (metaText.startsWith("mapper") && metaText.contains("namespace")) {
                        return true
                    }
                }
                // const 声明: const NAMESPACE: &str = "...";
                "RsConstant" -> {
                    // 检查变量名是否包含 "namespace"
                    val firstChild = current.firstChild
                    if (firstChild != null && firstChild.text.contains("namespace", ignoreCase = true)) {
                        return true
                    }
                }
                // 函数调用参数: session.select_list(NAMESPACE, ...)
                "RsCallExpr", "RsMethodCall" -> {
                    return true
                }
            }
            current = current.parent
            depth++
        }
        return false
    }

    /**
     * 检查祖先链中是否存在指定名称类型的 PSI 节点。
     */
    private fun hasAncestorNamed(element: PsiElement, typeName: String): Boolean {
        var current: PsiElement? = element.parent
        var depth = 0
        while (current != null && depth < 10) {
            if (current::class.simpleName == typeName) return true
            current = current.parent
            depth++
        }
        return false
    }

    /**
     * 尝试找到 RsMetaItem 中的标识符子节点。
     * 标识符通常是第一个子元素，用于精确高亮和导航。
     */
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
