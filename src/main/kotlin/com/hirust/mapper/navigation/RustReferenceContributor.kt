package com.hirust.mapper.navigation

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.util.ProcessingContext

/**
 * Rust 侧引用贡献者。
 *
 * 同一个类以 language=RUST 与 language=TEXT 注册两次,兼容两种运行环境:
 * - **RustRover**(Rust PSI 存在):
 *     - 字符串字面量 → 向后扫描源码文本判定 namespace / id 上下文
 *     - 宏名标识符叶子(mapper_query / dao)→ [MacroDefinitionReference]
 * - **无 Rust 插件的 IDE**(.rs 为纯文本,整个文件是单个叶子元素):
 *     - 对单叶元素按 [RustSourceParser] 的解析结果生成子区间引用
 *
 * 不依赖任何 Rust PSI 类(编译期不可见),仅做文本/结构判断。
 */
class RustReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(),
            RustReferenceProvider()
        )
    }
}

class RustReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val containingFile = element.containingFile ?: return PsiReference.EMPTY_ARRAY
        val vFile = containingFile.virtualFile ?: return PsiReference.EMPTY_ARRAY
        if (vFile.extension != "rs") return PsiReference.EMPTY_ARRAY

        return when {
            // 场景1:纯文本单叶(无 Rust 插件)—— 按解析结果生成子区间引用
            element is LeafPsiElement && element.parent is PsiFile ->
                plainTextReferences(element)

            // 场景2:宏名标识符(如 mapper_query、dao)
            isAttributeMacroNameLeaf(element) ->
                arrayOf(MacroDefinitionReference(element, element.text))

            // 场景3:字符串字面量(namespace / id)
            isStringLiteralElement(element) ->
                literalReferences(element)

            else -> PsiReference.EMPTY_ARRAY
        }
    }

    // ------------------------------------------------------------------
    // Rust PSI 形态(RustRover)
    // ------------------------------------------------------------------

    /** 字符串字面量元素:文本以引号包裹(RsLitExpr 或其叶子 token 均满足) */
    private fun isStringLiteralElement(element: PsiElement): Boolean {
        val text = element.text
        return text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")
    }

    /** 宏名标识符:文本是已知宏,且处于 #[...] 属性名的位置 */
    private fun isAttributeMacroNameLeaf(element: PsiElement): Boolean {
        if (element !is LeafPsiElement) return false
        val word = element.text
        if (word.isEmpty() || word.length > 40) return false
        if (!isKnownMacro(word)) return false
        // 向上最多 6 层,找到文本以 "#[" 开头且宏名紧随其后的祖先
        var ancestor: PsiElement? = element.parent
        var depth = 0
        while (ancestor != null && ancestor !is PsiFile && depth < 6) {
            val text = ancestor.text ?: return false
            if (text.startsWith("#[")) {
                return text.substringAfter("#[").trimStart().startsWith(word)
            }
            if (text.length > 500) return false
            ancestor = ancestor.parent
            depth++
        }
        return false
    }

    private fun isKnownMacro(word: String): Boolean =
        word == "dao" || word.startsWith("mapper_")

    /** 字面量上下文判定结果 */
    private enum class LiteralContext { NAMESPACE, XML_PATH, STATEMENT_ID }

    /**
     * 向后扫描源码文本判定字面量上下文:
     * 取字面量之前最近的 `#[`,若其间无 `]` 且以 `namespace =` / `xml =` / `id =` 结尾,
     * 且属性名分别是 dao(namespace/xml) / mapper_*(id),则命中。
     */
    private fun literalContext(fileText: String, literalStart: Int): LiteralContext? {
        val attrHash = fileText.lastIndexOf("#[", literalStart)
        if (attrHash < 0) return null
        val between = fileText.substring(attrHash, literalStart)
        if (between.contains(']')) return null
        val nameMatch = Regex("""^#\[\s*([A-Za-z_][A-Za-z0-9_]*)""").find(between) ?: return null
        val attrName = nameMatch.groupValues[1]
        return when {
            attrName == "dao" && Regex("""\bnamespace\s*=\s*$""").containsMatchIn(between) ->
                LiteralContext.NAMESPACE
            attrName == "dao" && Regex("""\bxml\s*=\s*$""").containsMatchIn(between) ->
                LiteralContext.XML_PATH
            attrName.startsWith("mapper_") && Regex("""\bid\s*=\s*$""").containsMatchIn(between) ->
                LiteralContext.STATEMENT_ID
            else -> null
        }
    }

    private fun literalReferences(element: PsiElement): Array<PsiReference> {
        val fileText = element.containingFile.text ?: return PsiReference.EMPTY_ARRAY
        val ctx = literalContext(fileText, element.textRange.startOffset) ?: return PsiReference.EMPTY_ARRAY
        return when (ctx) {
            LiteralContext.NAMESPACE ->
                arrayOf(NamespaceToXmlReference(element))
            LiteralContext.XML_PATH ->
                arrayOf(XmlPathToXmlFileReference(element, TextRange(0, element.textLength)))
            LiteralContext.STATEMENT_ID ->
                arrayOf(RustIdToXmlStatementReference(element, TextRange(0, element.textLength)))
        }
    }

    // ------------------------------------------------------------------
    // 纯文本形态(无 Rust 插件)
    // ------------------------------------------------------------------

    private fun plainTextReferences(leaf: PsiElement): Array<PsiReference> {
        val text = leaf.text ?: return PsiReference.EMPTY_ARRAY
        if (!text.contains("#[")) return PsiReference.EMPTY_ARRAY
        val base = leaf.textRange.startOffset
        val parsed = RustSourceParser.parse(text)
        if (parsed.isEmpty()) return PsiReference.EMPTY_ARRAY

        val refs = mutableListOf<PsiReference>()
        for (dao in parsed) {
            // namespace 字面量(引号内区间)
            if (dao.nsLiteralOffset >= 0 && dao.namespace.isNotEmpty()) {
                val start = dao.nsLiteralOffset - base
                refs += NamespaceToXmlReference(
                    leaf,
                    TextRange(start, start + dao.namespace.length),
                    dao.namespace
                )
            }
            // xml 路径字面量(引号内区间,v1.2.3)
            if (dao.xmlAttrOffset >= 0 && dao.xmlAttr.isNotEmpty()) {
                val start = dao.xmlAttrOffset - base
                refs += XmlPathToXmlFileReference(
                    leaf,
                    TextRange(start, start + dao.xmlAttr.length)
                )
            }
            // impl 类型名(v1.2.2:无 id 字面量的代码风格下,fn 名/类型名是主要点击入口)
            if (dao.implNameOffset >= 0 && dao.implName.isNotEmpty()) {
                val start = dao.implNameOffset - base
                refs += NamespaceToXmlReference(
                    leaf,
                    TextRange(start, start + dao.implName.length),
                    dao.namespace
                )
            }
            // dao 宏名
            refs += MacroDefinitionReference(
                leaf, "dao",
                TextRange(dao.attrNameOffset - base, dao.attrNameOffset - base + 3)
            )
            // 方法宏名 / id 字面量 / fn 名
            for (m in dao.methods) {
                refs += MacroDefinitionReference(
                    leaf, m.macroName,
                    TextRange(m.macroNameOffset - base, m.macroNameOffset - base + m.macroName.length)
                )
                if (m.idLiteralOffset >= 0 && m.id.isNotEmpty()) {
                    val start = m.idLiteralOffset - base
                    refs += RustIdToXmlStatementReference(leaf, TextRange(start, start + m.id.length))
                }
                // fn 名:id 缺省为 fn 名时是唯一入口;有 id 参数时经 findMethodAt 反查同样成立
                refs += RustIdToXmlStatementReference(
                    leaf,
                    TextRange(m.fnOffset - base, (m.fnOffset + m.fnName.length) - base)
                )
            }
        }
        return refs.toTypedArray()
    }
}

/**
 * `#[dao(xml = "...")]` 中的路径字面量 → XML mapper 文件(v1.2.3)。
 * 路径相对 crate 根解析(与运行时基准一致),回退项目根与索引后缀匹配,
 * 见 [XmlNamespaceIndex.findXmlFileByRelativePath]。
 *
 * 支持两种宿主元素(与 NamespaceToXmlReference 相同):
 * - Rust PSI 字符串字面量:rangeInElement 覆盖整个元素(文本含引号)
 * - 纯文本单叶:rangeInElement 指向引号内子区间
 */
class XmlPathToXmlFileReference(
    element: PsiElement,
    rangeInElement: TextRange
) : PsiReferenceBase<PsiElement>(element, rangeInElement) {

    override fun resolve(): PsiElement? {
        // Rust PSI 模式文本含引号,纯文本模式已是引号内路径,统一剥引号
        val path = rangeInElement.substring(element.text)
            .removePrefix("\"").removeSuffix("\"")
        if (path.isEmpty()) return null
        val project = element.project
        val vFile = element.containingFile?.virtualFile ?: return null
        val xmlIndex = XmlNamespaceIndex.getInstance(project)
        val xmlFile = xmlIndex.findXmlFileByRelativePath(path, vFile) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(xmlFile) ?: return null
        // 优先落点到 <mapper> 标签;无索引信息时退化为文件
        val info = xmlIndex.getMapperInfo(xmlFile) ?: return psiFile
        return psiFile.findElementAt(info.mapperTagOffset) ?: psiFile
    }
}

/**
 * `#[mapper_xxx(id = "...")]` 中的 id 字面量 → XML 语句 `<select id="...">`。
 * 通过 [RustDaoIndex.findMethodAt] 反查所在方法,再定位 XML 语句。
 */
class RustIdToXmlStatementReference(
    element: PsiElement,
    rangeInElement: TextRange
) : PsiReferenceBase<PsiElement>(element, rangeInElement) {

    override fun resolve(): PsiElement? {
        val project = element.project
        val vFile = element.containingFile?.virtualFile ?: return null
        val offset = element.textRange.startOffset + rangeInElement.startOffset
        val loc = RustDaoIndex.getInstance(project).findMethodAt(vFile, offset) ?: return null
        val xmlLoc = XmlNamespaceIndex.getInstance(project)
            .findStatement(loc.dao.namespace, loc.method.id, loc.method.stmtTag) ?: return null
        return NavigationUtil.findElement(xmlLoc.file, project, xmlLoc.statement.idAttrOffset)
    }
}
