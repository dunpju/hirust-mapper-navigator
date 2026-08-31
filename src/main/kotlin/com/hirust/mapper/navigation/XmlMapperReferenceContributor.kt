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
 * 目标不存在时 resolve 返回 null(容错:不跳转、不下划线、不报错)。
 */
class XmlIncludeRefidToSqlReference(element: XmlAttributeValue) :
    PsiReferenceBase<XmlAttributeValue>(element) {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(XmlIncludeRefidToSqlReference::class.java)

    override fun resolve(): PsiElement? {
        val refid = element.value ?: return null
        val project = element.project
        val vFile = element.containingFile.virtualFile ?: return null
        val loc = XmlNamespaceIndex.getInstance(project).findSqlFragment(refid, vFile) ?: return null
        // 落点:片段定义本身(<sql 标签起始)
        return NavigationUtil.findElement(loc.file, project, loc.fragment.tagOffset)
    }

    override fun getVariants(): Array<LookupElement> = emptyArray()
}
