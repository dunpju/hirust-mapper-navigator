package com.hirust.mapper.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Rust 侧 Ctrl+Click 跳转处理器(语言无关通道)。
 *
 * 背景:`psi.referenceContributor` 以 language="RUST"/"ANY" 注册在 RustRover 2026.2
 * 中均不会被咨询(language="XML" 正常),故改用无 language 属性的
 * `gotoDeclarationHandler` 扩展点 —— 它在所有语言的文件上都生效。
 *
 * 处理 .rs 文件中的点击,两条路径:
 * - **字面量路径**:点击字符串字面量
 *   - `#[dao(namespace = "...")]` 的 namespace 字符串 → XML `<mapper>` 标签
 *   - `#[dao(xml = "...")]` 的路径字符串 → XML 文件(v1.2.3,相对 crate 根解析)
 *   - `#[mapper_*(id = "...")]` 的 id 字符串 → XML 对应语句
 * - **词级路径**(v1.2.2):目标代码风格常见 `#[mapper_query]` 裸宏 / `kind` 参数,
 *   id 缺省为 fn 名,没有 id 字面量可点 —— 支持点击
 *   - fn 名 / 宏名 → XML 对应语句
 *   - impl 类型名 → XML `<mapper>` 标签
 */
class RustGotoDeclarationHandler : GotoDeclarationHandler {

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
        val project = element.project

        val literal = literalAt(fileText, offset)
        return if (literal != null) {
            literalTargets(fileText, vFile, literal, project)
        } else {
            wordTargets(fileText, vFile, offset, project)
        }
    }

    // ------------------------------------------------------------------
    // 字面量路径
    // ------------------------------------------------------------------

    private fun literalTargets(
        fileText: String,
        vFile: VirtualFile,
        literal: LiteralSpan,
        project: Project
    ): Array<PsiElement>? {
        // 判定字面量上下文:namespace 或 mapper 宏的 id
        val attrHash = fileText.lastIndexOf("#[", literal.start)
        if (attrHash < 0) return null
        val between = fileText.substring(attrHash, literal.start)
        if (between.contains(']')) return null
        val nameMatch = ATTR_NAME_REGEX.find(between) ?: return null
        val attrName = nameMatch.groupValues[1]

        return when {
            attrName == "dao" && NS_SUFFIX_REGEX.containsMatchIn(between) -> {
                val xmlIndex = XmlNamespaceIndex.getInstance(project)
                val xmlFile = xmlIndex.findXmlFile(literal.value) ?: return null
                val targetOffset = xmlIndex.getMapperInfo(xmlFile)?.mapperTagOffset ?: 0
                navigateTo(xmlFile, targetOffset, project)
            }
            attrName == "dao" && XML_SUFFIX_REGEX.containsMatchIn(between) -> {
                val xmlIndex = XmlNamespaceIndex.getInstance(project)
                // xml 属性是相对 crate 根的路径(与运行时基准一致)
                val xmlFile = xmlIndex.findXmlFileByRelativePath(literal.value, vFile) ?: return null
                val targetOffset = xmlIndex.getMapperInfo(xmlFile)?.mapperTagOffset ?: 0
                navigateTo(xmlFile, targetOffset, project)
            }
            attrName.startsWith("mapper_") && ID_SUFFIX_REGEX.containsMatchIn(between) -> {
                val loc = RustDaoIndex.getInstance(project).findMethodAt(vFile, literal.start)
                    ?: return null
                val xmlLoc = XmlNamespaceIndex.getInstance(project)
                    .findStatement(loc.dao.namespace, loc.method.id, loc.method.stmtTag)
                    ?: return null
                val targetOffset = xmlLoc.statement.idAttrOffset.takeIf { it >= 0 }
                    ?: xmlLoc.statement.tagOffset
                navigateTo(xmlLoc.file, targetOffset, project)
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // 词级路径(v1.2.2)
    // ------------------------------------------------------------------

    /**
     * 词级点击:fn 名 / 宏名 → XML 语句;impl 类型名 → `<mapper>` 标签。
     *
     * 采用【精确 span 匹配】(word 起点等于解析器记录的偏移且文本相等)而非区间包含:
     * 方法的判定区间 `[macroOffset, fnOffset+fnName.length]` 覆盖 doc 注释与
     * `pub async` 等整段,区间包含会劫持同区域内 Rust 自身的类型/符号导航;
     * span 匹配只在点击名称本身时命中,其余一律返回 null 放行。
     */
    private fun wordTargets(
        fileText: String,
        vFile: VirtualFile,
        offset: Int,
        project: Project
    ): Array<PsiElement>? {
        val word = wordAt(fileText, offset) ?: return null
        val daos = RustDaoIndex.getInstance(project).getParsed(vFile) ?: return null
        val xmlIndex = XmlNamespaceIndex.getInstance(project)

        // impl 类型名 → XML <mapper>
        for (dao in daos) {
            if (dao.implNameOffset >= 0 && word.start == dao.implNameOffset && word.text == dao.implName) {
                val xmlFile = xmlIndex.findXmlFile(dao.namespace) ?: return null
                val targetOffset = xmlIndex.getMapperInfo(xmlFile)?.mapperTagOffset ?: 0
                return navigateTo(xmlFile, targetOffset, project)
            }
        }
        // fn 名 / 宏名 → XML 语句
        for (dao in daos) {
            for (m in dao.methods) {
                val hit = (word.start == m.fnOffset && word.text == m.fnName) ||
                        (word.start == m.macroNameOffset && word.text == m.macroName)
                if (hit) {
                    val loc = xmlIndex.findStatement(dao.namespace, m.id, m.stmtTag) ?: return null
                    val targetOffset = loc.statement.idAttrOffset.takeIf { it >= 0 }
                        ?: loc.statement.tagOffset
                    return navigateTo(loc.file, targetOffset, project)
                }
            }
        }
        // struct 类型名 → Find Usages 弹窗(聚合 Rust 引用/use 导入/泛型/返回值/XML resultType)
        val typeLoc = RustDaoIndex.getInstance(project).findType(word.text) ?: return null
        val defTarget = NavigationUtil.findElement(typeLoc.file, project, typeLoc.type.nameOffset)
            ?: return null
        if (isClickContext()) {
            RustStructUsageSearcher.showAsync(project, word.text)
        }
        return arrayOf(defTarget)
    }

    /** 取 offset 处的词;offset 落在词尾边界后的非词字符上时回退取前一个词 */
    private fun wordAt(text: String, offset: Int): WordSpan? {
        if (text.isEmpty() || offset < 0 || offset > text.length) return null
        val wordChar: (Char) -> Boolean = { it.isLetterOrDigit() || it == '_' }
        var i = offset.coerceAtMost(text.length - 1)
        if (!wordChar(text[i])) {
            if (i == 0 || !wordChar(text[i - 1])) return null
            i--
        }
        var start = i
        while (start > 0 && wordChar(text[start - 1])) start--
        var end = i
        while (end < text.length && wordChar(text[end])) end++
        if (end <= start) return null
        return WordSpan(text.substring(start, end), start, end)
    }

    private data class WordSpan(val text: String, val start: Int, val end: Int)

    // ------------------------------------------------------------------
    // 公共
    // ------------------------------------------------------------------

    /**
     * 生成目标数组;平台在 Ctrl+悬停渲染下划线时也会调用本 handler,
     * 因此仅在真实鼠标点击后的短窗口内才自行导航(见 isClickContext)。
     */
    private fun navigateTo(file: VirtualFile, offset: Int, project: Project): Array<PsiElement>? {
        val target = NavigationUtil.findElement(file, project, offset) ?: return null
        if (isClickContext()) {
            ApplicationManager.getApplication().invokeLater {
                OpenFileDescriptor(project, file, offset).navigate(true)
            }
        }
        return arrayOf(target)
    }

    companion object {
        /** 属性名匹配(悬停/点击热路径共用,编译一次) */
        private val ATTR_NAME_REGEX = Regex("""^#\[\s*([A-Za-z_][A-Za-z0-9_]*)""")
        private val NS_SUFFIX_REGEX = Regex("""\bnamespace\s*=\s*"?$""")
        private val XML_SUFFIX_REGEX = Regex("""\bxml\s*=\s*"?$""")
        private val ID_SUFFIX_REGEX = Regex("""\bid\s*=\s*"?$""")

        /** 最近一次鼠标左键按下的时间戳;用于区分"点击查询"与"Ctrl 悬停渲染查询" */
        @Volatile
        private var lastClickAt = 0L

        private const val CLICK_WINDOW_MS = 350L

        init {
            ApplicationManager.getApplication().invokeLater {
                try {
                    java.awt.Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
                        if (event is java.awt.event.MouseEvent &&
                            event.id == java.awt.event.MouseEvent.MOUSE_PRESSED &&
                            (event.modifiersEx and java.awt.event.InputEvent.BUTTON1_DOWN_MASK) != 0
                        ) {
                            lastClickAt = System.currentTimeMillis()
                        }
                    }, java.awt.AWTEvent.MOUSE_EVENT_MASK)
                } catch (_: Exception) {
                }
            }
        }

        /**
         * 平台在 Ctrl+悬停(渲染下划线)与真实点击两种场景都会调用 handler;
         * 只有处于点击后的短窗口内才执行自行导航,避免悬停即跳转。
         */
        private fun isClickContext(): Boolean =
            System.currentTimeMillis() - lastClickAt <= CLICK_WINDOW_MS
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
