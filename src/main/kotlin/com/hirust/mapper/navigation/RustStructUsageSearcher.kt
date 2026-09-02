package com.hirust.mapper.navigation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes

/**
 * Rust struct 的 Find Usages(Ctrl+Click 聚合查找)。
 *
 * 触发:在 .rs 文件中 Ctrl+Click 某个 struct 名称 → 结果弹窗聚合展示
 * 全项目使用位置(文件:行号 + 代码片段),点击/回车/双击跳转到对应位置。
 *
 * 覆盖五类场景(均为标识符全词匹配,天然覆盖):
 * 1) Rust 代码直接引用;2) use 导入语句;3) 泛型参数(Vec&lt;T&gt;);
 * 4) 方法返回值类型;5) XML 映射文件属性引用(resultType/resultMap/ofType/parameterType/type)。
 *
 * 实现说明:原生 UsageViewManager 的 showUsages / searchAndShowUsages 在
 * 本插件环境(按 IC 2024.2 编译、RustRover 2026.2 运行)均不可用 —— 前者经
 * BackendUsageViewManager 包装后 tabName 丢失断言失败,后者方法签名跨版本
 * 变化触发 NoSuchMethodError。故改用 JBPopupFactory 结果弹窗(十年级稳定 API)。
 */
object RustStructUsageSearcher {

    private val log = Logger.getInstance(RustStructUsageSearcher::class.java)

    /** XML 中引用类型的属性名(值可为限定名,取末段比对) */
    private val XML_TYPE_ATTR = Regex("""\b(resultType|resultMap|ofType|parameterType|type)\s*=\s*"([^"]*)"""")

    /** 一次命中的位置:文件 + 偏移(Document 坐标)+ 行号(0 基)+ 该行代码片段 + 是否 XML */
    data class Hit(
        val file: VirtualFile,
        val offset: Int,
        val line: Int,
        val snippet: String,
        val inXml: Boolean
    )

    // ------------------------------------------------------------------
    // 纯文本匹配(可单元测试)
    // ------------------------------------------------------------------

    /**
     * 找出 Rust 源码中 struct 名称的全部【全词】使用位置。
     * 排除:定义处(前置 `struct ` 关键字)与行注释(`//` 及 `///` 文档注释)中的匹配。
     * 块注释 /* */ 内的匹配不做排除(已知取舍)。
     */
    fun rustUsageOffsets(content: String, name: String): List<Pair<Int, Int>> {
        if (name.isEmpty()) return emptyList()
        val word = Regex("""\b${Regex.escape(name)}\b""")
        val result = mutableListOf<Pair<Int, Int>>()
        for (m in word.findAll(content)) {
            val offset = m.range.first
            // 排除定义处:同一行紧邻前置 `struct ` 关键字
            val lineStart = content.lastIndexOf('\n', offset) + 1
            val prefix = content.substring(lineStart, offset)
            if (Regex("""\b(struct|enum|type)\s+$""").containsMatchIn(prefix)) continue
            // 排除行注释/文档注释
            if (prefix.trimStart().startsWith("//")) continue
            // 行号(0 基)= offset 之前的换行数
            var line = 0
            for (i in 0 until offset) if (content[i] == '\n') line++
            result.add(offset to line)
        }
        return result
    }

    /**
     * 找出 XML 中类型属性(resultType/resultMap/ofType/parameterType/type)值里
     * 引用该 struct 的位置;限定名(dto::CountRow)取末段比对,命中定位到值内名字处。
     */
    fun xmlUsageOffsets(content: String, name: String): List<Pair<Int, Int>> {
        if (name.isEmpty()) return emptyList()
        val result = mutableListOf<Pair<Int, Int>>()
        for (m in XML_TYPE_ATTR.findAll(content)) {
            val value = m.groupValues[2]
            val lastSegIdx = value.lastIndexOfAny(charArrayOf(':', '.'))
            val lastSeg = if (lastSegIdx >= 0) value.substring(lastSegIdx + 1) else value
            if (lastSeg != name) continue
            val nameInValue = if (lastSegIdx >= 0) lastSegIdx + 1 else 0
            val absOffset = m.groups[2]!!.range.first + nameInValue
            val line = content.substring(0, absOffset).count { it == '\n' }
            result.add(absOffset to line)
        }
        return result
    }

    // ------------------------------------------------------------------
    // 项目级搜索(需 ReadAction,由调用方保证)
    // ------------------------------------------------------------------

    fun search(project: Project, name: String): List<Hit> {
        val app = ApplicationManager.getApplication()
        val scope = GlobalSearchScope.projectScope(project)
        val hits = mutableListOf<Hit>()

        // Rust 源码
        val rsFiles = runReadActionFiles(project, "rs")
        for (vf in rsFiles) {
            if (!vf.isValid) continue
            val content = app.runReadAction<String?> { NavigationUtil.loadTextDocumentAligned(vf) } ?: continue
            if (!content.contains(name)) continue
            for ((offset, line) in rustUsageOffsets(content, name)) {
                hits.add(Hit(vf, offset, line, lineSnippet(content, offset), inXml = false))
            }
        }

        // XML 映射文件(限定 mapper 目录,排除 .idea/target)
        val xmlFiles = runReadActionFiles(project, "xml")
            .filter { it.path.contains("/mapper/", ignoreCase = true) }
        for (vf in xmlFiles) {
            if (!vf.isValid) continue
            val content = app.runReadAction<String?> { NavigationUtil.loadTextDocumentAligned(vf) } ?: continue
            if (!content.contains(name)) continue
            for ((offset, line) in xmlUsageOffsets(content, name)) {
                hits.add(Hit(vf, offset, line, lineSnippet(content, offset), inXml = true))
            }
        }
        return hits
    }

    /** 取 offset 所在行的代码片段(截断到 100 字符) */
    private fun lineSnippet(content: String, offset: Int): String {
        val start = content.lastIndexOf('\n', offset.coerceAtMost(content.length - 1)) + 1
        var end = content.indexOf('\n', offset)
        if (end < 0) end = content.length
        val raw = content.substring(start, end).trim()
        return if (raw.length > 100) raw.substring(0, 100) + "…" else raw
    }

    private fun runReadActionFiles(project: Project, ext: String): List<VirtualFile> {
        val app = ApplicationManager.getApplication()
        return app.runReadAction<List<VirtualFile>> {
            val type = FileTypeManager.getInstance().getFileTypeByExtension(ext)
            FileTypeIndex.getFiles(type, GlobalSearchScope.projectScope(project))
                .filter { it.extension.equals(ext, ignoreCase = true) }
        }
    }

    // ------------------------------------------------------------------
    // 展示:后台搜索 → EDT 结果弹窗(JBPopupFactory,跨版本稳定 API)
    // ------------------------------------------------------------------

    /**
     * 后台执行全项目搜索,在 EDT 弹出结果列表(文件:行号 + 代码片段),
     * 点击/回车/双击任意一行跳转到对应文件的对应偏移。
     */
    fun showAsync(project: Project, structName: String) {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            try {
                val hits = app.runReadAction<List<Hit>> { search(project, structName) }
                app.invokeLater({
                    if (project.isDisposed) return@invokeLater
                    if (hits.isEmpty()) {
                        com.intellij.openapi.ui.Messages.showInfoMessage(
                            "struct $structName 在项目中没有找到使用处",
                            "Struct Usages"
                        )
                        return@invokeLater
                    }
                    val popup = JBPopupFactory.getInstance()
                        .createPopupChooserBuilder(hits)
                        .setTitle("Struct Usages: $structName  (${hits.size} 处)")
                        .setRenderer(object : ColoredListCellRenderer<Hit>() {
                            override fun customizeCellRenderer(
                                list: javax.swing.JList<out Hit>,
                                value: Hit,
                                index: Int,
                                selected: Boolean,
                                hasFocus: Boolean
                            ) {
                                append("${value.file.name}:${value.line + 1}",
                                    SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, null))
                                append("  ${value.snippet}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                                toolTipText = value.file.path
                            }
                        })
                        .setItemChosenCallback { hit ->
                            com.intellij.openapi.fileEditor.OpenFileDescriptor(
                                project, hit.file, hit.offset
                            ).navigate(true)
                        }
                        .createPopup()
                    popup.showInFocusCenter()
                    log.info("[hirust-mapper-navigator] StructUsages popup shown: $structName x${hits.size}")
                }, project.disposed)
            } catch (e: Exception) {
                log.warn("[hirust-mapper-navigator] Struct usage search failed: ${e.message}")
            }
        }
    }
}
