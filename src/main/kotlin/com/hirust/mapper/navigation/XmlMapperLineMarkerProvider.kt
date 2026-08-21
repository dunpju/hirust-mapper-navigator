package com.hirust.mapper.navigation

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlTokenType
import java.util.function.Supplier

/**
 * XML 侧行标记(language=XML):
 * - `<mapper>` 标签行 → 图标跳转到 Rust DAO(#[dao] 属性处)
 * - `<select|insert|update|delete>` 语句行 → 图标跳转到对应 Rust 方法
 *
 * 锚点:标签名 token(仅开始标签,避免结束标签重复标记)。
 */
class XmlMapperLineMarkerProvider : LineMarkerProvider {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(XmlMapperLineMarkerProvider::class.java)

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // 仅处理叶子上的开始标签名 token:<mapper、<select 等
        if (element !is com.intellij.psi.xml.XmlToken) return null
        if (element.tokenType != XmlTokenType.XML_NAME) return null
        val prev = element.prevSibling
        if (prev !is com.intellij.psi.xml.XmlToken || prev.tokenType != XmlTokenType.XML_START_TAG_START) return null
        val tag = element.parent as? XmlTag ?: return null
        if (element.text != tag.name) return null
        log.info("[hirust-mapper-navigator] LineMarker on <${tag.name}> in ${element.containingFile.name}")

        val project = element.project
        val vFile = element.containingFile.virtualFile ?: return null

        return when (tag.name) {
            "mapper" -> {
                val ns = tag.getAttributeValue("namespace") ?: return null
                val loc = RustDaoIndex.getInstance(project).findDaoByNamespace(ns) ?: return null
                val dao = loc.dao
                LineMarkerInfo(
                    element,
                    element.textRange,
                    Icons.TO_RUST,
                    null,
                    GutterIconNavigationHandler<PsiElement> { _, _ ->
                        NavigationUtil.openAt(project, loc.file, dao.attrOffset)
                    },
                    GutterIconRenderer.Alignment.LEFT,
                    Supplier { "跳转到 Rust DAO: ${dao.implName.ifEmpty { dao.namespace }}" }
                )
            }
            in XmlMapperParser.STATEMENT_TAGS -> {
                val id = tag.getAttributeValue("id") ?: return null
                val info = XmlNamespaceIndex.getInstance(project).getMapperInfo(vFile) ?: return null
                val loc = RustDaoIndex.getInstance(project).findMethod(info.namespace, id, tag.name) ?: return null
                val method = loc.method
                LineMarkerInfo(
                    element,
                    element.textRange,
                    Icons.TO_RUST,
                    null,
                    GutterIconNavigationHandler<PsiElement> { _, _ ->
                        NavigationUtil.openAt(project, loc.file, method.fnOffset)
                    },
                    GutterIconRenderer.Alignment.LEFT,
                    Supplier { "跳转到 Rust 方法: ${method.fnName}()" }
                )
            }
            else -> null
        }
    }
}
