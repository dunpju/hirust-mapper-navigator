package com.hirust.mapper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.util.ProcessingContext
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*

/**
 * RustRover 引用贡献者 — 插件入口点。
 *
 * 在 Rust PSI 树中扫描所有 #[...] 外部属性（outer attribute），
 * 识别 hirust-mapper 的自定义宏名称和 namespace 参数值，
 * 分发给对应的 Reference：
 *
 *   - #[mapper_query] / #[dao] 等 → MacroDefinitionReference（跳转到 proc macro 定义）
 *   - namespace 字符串值 → NamespaceToXmlReference（跳转到 XML 文件）
 *
 * 注册方式: 在 plugin.xml 中通过 <referenceContributor language="Rust"> 注册，
 * IntelliJ 会自动在匹配的 PSI 元素上调用 getReferencesByElement()。
 */
class MacroAttributeReferenceContributor : PsiReferenceContributor() {

    private val log = Logger.getInstance(MacroAttributeReferenceContributor::class.java)

    /**
     * PSI 元素引用注册器。
     *
     * 对两类 PSI 元素创建自定义引用:
     * 1. RsMetaItem — 属性宏标识符节点（如 mapper_query, dao）
     * 2. RsLitExpr — 字符串字面量节点（如 namespace 的值）
     */
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        // ==========================================
        // 1. 属性宏名称引用: #[mapper_query] / #[dao]
        // ==========================================
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(RsMetaItem::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext
                ): Array<PsiReference> {
                    return createMetaItemReferences(element as RsMetaItem)
                }
            }
        )

        // ==========================================
        // 2. Namespace 字符串引用: "crate::app::dao::..."
        // ==========================================
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(RsLitExpr::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext
                ): Array<PsiReference> {
                    return createLitExprReferences(element as RsLitExpr)
                }
            }
        )
    }

    /**
     * 为 RsMetaItem 创建引用。
     *
     * 匹配条件:
     *   - 宏名称属于已知的 mapper 系列 (mapper_query, dao, mapper_insert 等)
     *   - 必须是外部属性 #[...] 的一部分
     *
     * PSI 上下文示例:
     *   #[mapper_query]
     *     ^^^^^^^^^^^^^
     *   #[dao(namespace = "crate::app::dao::privilege_project_dao")]
     *     ^^^
     *   #[mapper_query(id = "list")]
     *     ^^^^^^^^^^^^^
     */
    private fun createMetaItemReferences(metaItem: RsMetaItem): Array<PsiReference> {
        // 获取宏名称
        val macroName = metaItem.path?.referenceName ?: return noReferences()

        // 仅处理已知的 mapper 宏
        if (!MacroDefinitionReference.isMapperMacro(macroName)) {
            return noReferences()
        }

        // 确认是外部属性的一部分
        val containingAttr = metaItem.parent as? RsOuterAttr
        if (containingAttr == null) {
            // 也检查是否在 item 的 attribute 列表中
            val parent = metaItem.parent?.parent
            if (parent !is RsOuterAttr && parent !is RsAttr) {
                return noReferences()
            }
        }

        log.debug("[hirust-mapper-navigator] Macro reference created for: $macroName")

        // 在宏名称标识符上创建引用
        val ident = metaItem.path?.lastSegment ?: metaItem.identifier
        if (ident != null) {
            return arrayOf(MacroDefinitionReference(ident.psi ?: metaItem, macroName))
        }

        return arrayOf(MacroDefinitionReference(metaItem, macroName))
    }

    /**
     * 为 RsLitExpr 创建引用。
     *
     * 匹配条件:
     *   - 是字符串字面量 (以 " 开头和结尾)
     *   - 字符串值包含 "::" (看起来像 Rust 路径)
     *   - 位于 #[dao(namespace = "...")] 的 namespace 参数位置
     *
     * PSI 上下文示例:
     *   #[dao(namespace = "crate::app::dao::privilege_project_dao")]
     *                         ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
     *   const NAMESPACE: &str = "crate::app::dao::privilege_notify_dao";
     *                          ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
     */
    private fun createLitExprReferences(litExpr: RsLitExpr): Array<PsiReference> {
        val text = litExpr.text

        // 必须是字符串字面量
        if (!text.startsWith("\"") || !text.endsWith("\"") || text.length < 3) {
            return noReferences()
        }

        // 提取字符串值
        val value = text.substring(1, text.length - 1)

        // 必须包含 "::" 才可能是 namespace 路径
        if (!value.contains("::")) {
            return noReferences()
        }

        // 检查是否在 dao 属性或 const 声明的 namespace 上下文中
        if (!isNamespaceContext(litExpr)) {
            return noReferences()
        }

        log.debug("[hirust-mapper-navigator] Namespace-to-XML reference created for: $value")

        return arrayOf(NamespaceToXmlReference(litExpr))
    }

    /**
     * 判断字符串字面量是否在 namespace 相关的上下文中。
     *
     * 检查两种场景:
     * 1. #[dao(namespace = "...")] 中的值
     * 2. const NAMESPACE: &str = "..."; 中的值（常量定义）
     */
    private fun isNamespaceContext(litExpr: RsLitExpr): Boolean {
        // 场景1: 父级属性中包含 "namespace"
        val metaItem = findAncestorOfType<RsMetaItem>(litExpr)
        if (metaItem != null) {
            val metaName = metaItem.path?.referenceName
            // dao 属性的 namespace 参数
            if (metaName == "dao") {
                // 检查 meta item 的参数文本中是否包含 "namespace"
                val argsText = metaItem.metaItemArgs?.text ?: ""
                if (argsText.contains("namespace")) {
                    return true
                }
            }
            // 其他可能包含 namespace 的属性
            if (metaName in listOf("mapper", "hirust_mapper")) {
                val argsText = metaItem.metaItemArgs?.text ?: ""
                if (argsText.contains("namespace")) {
                    return true
                }
            }
        }

        // 场景2: const 声明中变量名包含 "namespace" 或 "NAMESPACE"
        val constItem = findAncestorOfType<RsConstant>(litExpr)
        if (constItem != null) {
            val constName = constItem.name
            if (constName.equals("NAMESPACE", ignoreCase = true) ||
                constName.contains("namespace", ignoreCase = true)
            ) {
                return true
            }
        }

        // 场景3: 函数调用参数中传递了 namespace 值
        // 例如 session.select_list::<T>(NAMESPACE, "query_id", &params)
        // 这里对任意包含 "::" 的字符串都尝试跳转（宽松策略）
        val fnCall = findAncestorOfType<RsCallExpr>(litExpr)
        if (fnCall != null) {
            // 函数调用中的字符串参数 — 如果包含 :: 就尝试
            return true
        }

        return false
    }

    /**
     * 向上查找指定类型的祖先 PSI 节点
     */
    private inline fun <reified T : PsiElement> findAncestorOfType(element: PsiElement): T? {
        var current: PsiElement? = element.parent
        val maxDepth = 15
        var depth = 0
        while (current != null && depth < maxDepth) {
            if (current is T) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun noReferences(): Array<PsiReference> = PsiReference.EMPTY_ARRAY

    companion object {
        private const val TAG = "[hirust-mapper-navigator]"
    }
}
