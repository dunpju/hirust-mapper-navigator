package com.hirust.mapper.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Rust 侧 Ctrl+Click 跳转处理器(语言无关通道)。
 *
 * 背景:`psi.referenceContributor` 以 language="RUST"/"ANY" 注册在 RustRover 2026.2
 * 中均不会被咨询(language="XML" 正常),故改用无 language 属性的
 * `gotoDeclarationHandler` 扩展点 —— 它在所有语言的文件上都生效。
 *
 * 处理 .rs 文件中的三类点击:
 * - `#[dao(namespace = "...")]` 的 namespace 字符串 → XML `<mapper>` 标签
 * - `#[mapper_*(id = "...")]` 的 id 字符串 → XML 对应语句
 */
class RustGotoDeclarationHandler : GotoDeclarationHandler {

    private val log = Logger.getInstance(RustGotoDeclarationHandler::class.java)

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        val file: PsiFile = element.containingFile ?: return null
        val vFile = file.virtualFile ?: return null
        if (vFile.extension != "rs") return null

        val fileText = file.text ?: return null
        val literal = literalAt(fileText, offset) ?: return null
        val project = element.project
        log.info("[hirust-mapper-navigator] GotoDecl in rs: literal='${literal.value.take(60)}' offset=$offset")

        // 判定字面量上下文:namespace 或 mapper 宏的 id
        val attrHash = fileText.lastIndexOf("#[", literal.start)
        if (attrHash < 0) return null
        val between = fileText.substring(attrHash, literal.start)
        if (between.contains(']')) return null
        val nameMatch = Regex("""^#\[\s*([A-Za-z_][A-Za-z0-9_]*)""").find(between) ?: return null
        val attrName = nameMatch.groupValues[1]

        return when {
            attrName == "dao" && Regex("""\bnamespace\s*=\s*"?$""").containsMatchIn(between) -> {
                val xmlIndex = XmlNamespaceIndex.getInstance(project)
                val xmlFile = xmlIndex.findXmlFile(literal.value) ?: run {
                    log.info("[hirust-mapper-navigator] GotoDecl ns->xml: no xml file for ${literal.value}")
                    return null
                }
                val info = xmlIndex.getMapperInfo(xmlFile)
                val targetOffset = info?.mapperTagOffset ?: 0
                val target = NavigationUtil.findElement(xmlFile, project, targetOffset)
                if (target == null) {
                    log.info("[hirust-mapper-navigator] GotoDecl ns->xml: findElement null " +
                            "(file=${xmlFile.path} offset=$targetOffset infoNull=${info == null})")
                    return null
                }
                log.info("[hirust-mapper-navigator] GotoDecl ns->xml TARGET " +
                        "${xmlFile.name}@$targetOffset elem=${target.javaClass.simpleName}")
                arrayOf(target)
            }
            attrName.startsWith("mapper_") && Regex("""\bid\s*=\s*"?$""").containsMatchIn(between) -> {
                val loc = RustDaoIndex.getInstance(project).findMethodAt(vFile, literal.start) ?: run {
                    log.info("[hirust-mapper-navigator] GotoDecl id->xml: no method at offset ${literal.start}")
                    return null
                }
                val xmlLoc = XmlNamespaceIndex.getInstance(project)
                    .findStatement(loc.dao.namespace, loc.method.id, loc.method.stmtTag) ?: run {
                    log.info("[hirust-mapper-navigator] GotoDecl id->xml: no statement " +
                            "(ns=${loc.dao.namespace} id=${loc.method.id} tag=${loc.method.stmtTag})")
                    return null
                }
                val targetOffset = xmlLoc.statement.idAttrOffset.takeIf { it >= 0 }
                    ?: xmlLoc.statement.tagOffset
                log.info("[hirust-mapper-navigator] DIAG goto id->xml: " +
                        "file=${xmlLoc.file.name} offset=$targetOffset " +
                        "rawLen=${NavigationUtil.loadTextRaw(xmlLoc.file)?.length}")
                val target = NavigationUtil.findElement(xmlLoc.file, project, targetOffset)
                if (target == null) {
                    log.info("[hirust-mapper-navigator] GotoDecl id->xml: findElement null " +
                            "(file=${xmlLoc.file.path} offset=$targetOffset)")
                    return null
                }
                log.info("[hirust-mapper-navigator] GotoDecl id->xml TARGET " +
                        "${xmlLoc.file.name}@$targetOffset elem=${target.javaClass.simpleName}")
                arrayOf(target)
            }
            else -> {
                log.info("[hirust-mapper-navigator] GotoDecl ignored: attr=$attrName " +
                        "between='${between.takeLast(40)}'")
                null
            }
        }
    }

    /** 定位 offset 所在的字符串字面量(引号内),返回值与起止偏移 */
    private fun literalAt(text: String, offset: Int): LiteralSpan? {
        if (offset < 0 || offset > text.length) return null
        // 向后找开引号(同一行)
        val lineStart = text.lastIndexOf('\n', offset.coerceAtMost(text.length - 1)) + 1
        var start = -1
        var i = offset.coerceAtMost(text.length - 1)
        while (i >= lineStart) {
            if (text[i] == '"') {
                // 前一个字符不是转义
                if (i == 0 || text[i - 1] != '\\') { start = i; break }
            }
            i--
        }
        if (start < 0) return null
        var end = -1
        var j = start + 1
        while (j < text.length) {
            when (text[j]) {
                '\\' -> j++
                '"' -> { end = j; break }
                '\n' -> return null
            }
            j++
        }
        if (end < 0 || end <= start + 1) return null
        val value = text.substring(start + 1, end)
        if (value.isEmpty()) return null
        return LiteralSpan(value, start + 1, end)
    }

    private data class LiteralSpan(val value: String, val start: Int, val end: Int)
}
