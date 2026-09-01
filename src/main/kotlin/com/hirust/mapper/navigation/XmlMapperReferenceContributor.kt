package com.hirust.mapper.navigation

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext

/**
 * XML 侧引用贡献者(language=XML,该通道已真机验证生效):
 * - `<mapper namespace="...">` 的 namespace 属性值 → Rust DAO(#[dao] 属性处)
 * - `<select|insert|update|delete id="...">` 的 id 属性值 → Rust 方法(fn 名处)
 * - `<include refid="...">` 的 refid 属性值 → `<sql id>` 片段定义(v1.2.4)
 *
 * Ctrl+Click 与 Go to Declaration 均由此生效;引用可解析时 Ctrl+悬停
 * 由平台渲染原生超链接样式(下划线 + 手型光标)。
 */
class XmlMapperReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(XmlAttributeValue::class.java),
            XmlMapperReferenceProvider()
        )
    }
}

class XmlMapperReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val attrValue = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
        val attr = attrValue.parent as? XmlAttribute ?: return PsiReference.EMPTY_ARRAY
        val tag = attr.parent as? XmlTag ?: return PsiReference.EMPTY_ARRAY
        val value = attrValue.value ?: return PsiReference.EMPTY_ARRAY
        val interesting = (tag.name == "mapper" && attr.name == "namespace") ||
                (tag.name in XmlMapperParser.STATEMENT_TAGS && attr.name == "id") ||
                (tag.name == "include" && attr.name == "refid")
        if (!interesting) return PsiReference.EMPTY_ARRAY

        val ref = when {
            tag.name == "mapper" && attr.name == "namespace" && value.isNotEmpty() ->
                XmlNamespaceToDaoReference(attrValue)
            tag.name in XmlMapperParser.STATEMENT_TAGS && attr.name == "id" && value.isNotEmpty() ->
                XmlStatementIdToMethodReference(attrValue)
            tag.name == "include" && attr.name == "refid" && value.isNotEmpty() ->
                XmlIncludeRefidToSqlReference(attrValue)
            else -> null
        } ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(ref)
    }
}

/** namespace 属性值 → Rust DAO */
class XmlNamespaceToDaoReference(element: XmlAttributeValue) :
    PsiReferenceBase<XmlAttributeValue>(element) {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(XmlNamespaceToDaoReference::class.java)

    override fun resolve(): PsiElement? {
        val ns = element.value ?: return null
        val project = element.project
        val loc = RustDaoIndex.getInstance(project).findDaoByNamespace(ns) ?: return null
        return NavigationUtil.findElement(loc.file, project, loc.dao.attrOffset)
    }

    override fun getVariants(): Array<LookupElement> = emptyArray()
}

/** 语句 id 属性值 → Rust 方法 */
class XmlStatementIdToMethodReference(element: XmlAttributeValue) :
    PsiReferenceBase<XmlAttributeValue>(element) {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(XmlStatementIdToMethodReference::class.java)

    override fun resolve(): PsiElement? {
        val id = element.value ?: return null
        val project = element.project
        val attr = element.parent as? XmlAttribute ?: return null
        val tag = attr.parent as? XmlTag ?: return null
        val vFile = element.containingFile.virtualFile ?: return null

        val info = XmlNamespaceIndex.getInstance(project).getMapperInfo(vFile) ?: return null
        val loc = RustDaoIndex.getInstance(project).findMethod(info.namespace, id, tag.name) ?: return null
        return NavigationUtil.findElement(loc.file, project, loc.method.fnOffset)
    }

    override fun getVariants(): Array<LookupElement> = emptyArray()
}

/**
 * `<include refid="...">` 的 refid 属性值 → `<sql id="...">` 片段定义(v1.2.4)。
 *
 * 查找策略见 [XmlNamespaceIndex.findSqlFragment]:当前文件优先,
 * 带命名空间前缀(`<ns>.<id>` / `<ns>::<id>`)时跨文件解析;
 * 索引未就绪/未收录时兜底直读当前文件解析(同文件场景零索引依赖);
 * 目标不存在时 resolve 返回 null(容错:不跳转、不报错)。
 *
 * 落点:`<sql id="...">` 的 id 属性值元素(XmlAttributeValue)——
 * 与 Rust→XML 语句跳转的落点形态一致,平台对其导航可靠。
 */
class XmlIncludeRefidToSqlReference(element: XmlAttributeValue) :
    PsiReferenceBase<XmlAttributeValue>(element) {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(XmlIncludeRefidToSqlReference::class.java)

    override fun resolve(): PsiElement? {
        val refid = element.value ?: return null
        if (refid.isEmpty()) return null
        val project = element.project
        val vFile = element.containingFile.virtualFile ?: return null

        // 通道1:索引查找(同文件优先 + 命名空间前缀跨文件)
        val loc = XmlNamespaceIndex.getInstance(project).findSqlFragment(refid, vFile)
        if (loc != null) {
            return sqlTargetElement(loc.file, project, loc.fragment.idAttrOffset)
        }

        // 通道2:容错兜底 —— 直接解析当前文件(索引未就绪/未收录时仍可同文件跳转)
        val frag = readCurrentFragments(vFile)?.firstOrNull { it.id == refid }
        if (frag != null) {
            return sqlTargetElement(vFile, project, frag.idAttrOffset)
        }

        log.info("[hirust-mapper-navigator] include refid unresolved: \"$refid\" in ${vFile.path}")
        return null
    }

    /** 读当前文件并解析 sql 片段(ReadAction 包裹;失败返回 null) */
    private fun readCurrentFragments(vFile: com.intellij.openapi.vfs.VirtualFile): List<SqlFragmentInfo>? =
        try {
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .runReadAction<String?> { NavigationUtil.loadTextDocumentAligned(vFile) }
                ?.let { XmlMapperParser.parse(it) }?.sqlFragments
        } catch (_: Exception) {
            null
        }

    /** 落点元素:id 属性值(XmlAttributeValue,优先)或其叶子 token */
    private fun sqlTargetElement(
        file: com.intellij.openapi.vfs.VirtualFile,
        project: com.intellij.openapi.project.Project,
        offset: Int
    ): PsiElement? {
        val leaf = NavigationUtil.findElement(file, project, offset) ?: return null
        return leaf.parent as? XmlAttributeValue ?: leaf
    }

    override fun getVariants(): Array<LookupElement> = emptyArray()
}
