package com.hirust.mapper.navigation

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceBase

/**
 * namespace 字符串引用:Ctrl+Click `#[dao(namespace = "...")]` 中的字符串,
 * 跳转到对应 XML 映射文件的 `<mapper>` 标签。
 *
 * 支持两种宿主元素:
 * - Rust PSI 字符串字面量(RustRover):rangeInElement 为 null,引用覆盖整个元素
 * - 纯文本单叶(无 Rust 插件的 IDE):rangeInElement 指向字面量引号内的子区间
 */
class NamespaceToXmlReference(
    element: PsiElement,
    rangeInElement: TextRange? = null,
    nsOverride: String? = null
) : PsiReferenceBase<PsiElement>(element, rangeInElement ?: TextRange(0, element.textLength)) {

    private val log = Logger.getInstance(NamespaceToXmlReference::class.java)
    private val namespaceText: String? = nsOverride ?: extractNamespaceText(element)

    override fun resolve(): PsiElement? {
        val ns = namespaceText ?: return null
        if (!NamespacePathResolver.isValidNamespace(ns)) return null
        val project = element.project
        val xmlFile = NamespacePathResolver.resolve(project, ns) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(xmlFile) ?: return null

        // 优先落点到 <mapper> 标签;无索引信息时退化为文件
        val info = XmlNamespaceIndex.getInstance(project).getMapperInfo(xmlFile)
        if (info != null) {
            return psiFile.findElementAt(info.mapperTagOffset) ?: psiFile
        }
        log.debug("[hirust-mapper-navigator] No mapper info for ${xmlFile.path}, fallback to file")
        return psiFile
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
            if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
                return text.substring(1, text.length - 1)
            }
            return null
        }
    }
}
