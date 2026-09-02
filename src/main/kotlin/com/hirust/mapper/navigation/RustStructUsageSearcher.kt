package com.hirust.mapper.navigation

import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation

/**
 * Rust struct 的 Find Usages(Ctrl+Click 聚合查找)。
 *
 * 触发:在 .rs 文件中 Ctrl+Click 某个 struct 名称 → 平台原生 Usages 窗口
 * 聚合展示全项目使用位置,双击任意行跳转到对应文件/行。
 *
 * 覆盖五类场景(均为标识符全词匹配,天然覆盖):
 * 1) Rust 代码直接引用;2) use 导入语句;3) 泛型参数(Vec&lt;T&gt;);
 * 4) 方法返回值类型;5) XML 映射文件属性引用(resultType/resultMap/ofType/parameterType/type)。
 *
 * 实现说明:平台 Find Usages 管线依赖语言引用机制(RUST 语言扩展点在本插件
 * 环境不生效,见 PLAN.md 踩坑记录 #6),故采用文本搜索 + UsageViewManager.showUsages
 * 直接装配原生 Usages 窗口 —— 窗口的文件/行号展示与双击导航均为平台内置行为。
 */
object RustStructUsageSearcher {

    private val log = Logger.getInstance(RustStructUsageSearcher::class.java)

    /** XML 中引用类型的属性名(值可为限定名,取末段比对) */
    private val XML_TYPE_ATTR = Regex("""\b(resultType|resultMap|ofType|parameterType|type)\s*=\s*"([^"]*)"""")

    /** 一次命中的位置:文件 + 偏移(Document 坐标)+ 行号(0 基)+ 是否 XML */
    data class Hit(val file: VirtualFile, val offset: Int, val line: Int, val inXml: Boolean)

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
        val psiManager = PsiManager.getInstance(project)
        for (vf in rsFiles) {
            if (!vf.isValid) continue
            val content = app.runReadAction<String?> { NavigationUtil.loadTextDocumentAligned(vf) } ?: continue
            if (!content.contains(name)) continue
            for ((offset, line) in rustUsageOffsets(content, name)) {
                hits.add(Hit(vf, offset, line, inXml = false))
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
                hits.add(Hit(vf, offset, line, inXml = true))
            }
        }
        return hits
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
    // 展示:后台搜索 → EDT 打开原生 Usages 窗口
    // ------------------------------------------------------------------

    /**
     * 后台执行全项目搜索并在 EDT 打开 Find Usages 窗口。
     * @param definitionPsi struct 定义处元素(作为 UsageTarget 展示在窗口头部)
     */
    fun showAsync(project: Project, structName: String, definitionPsi: PsiElement) {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            try {
                val usages = app.runReadAction<List<Usage>> {
                    search(project, structName).mapNotNull { hit ->
                        val psiFile = PsiManager.getInstance(project).findFile(hit.file) ?: return@mapNotNull null
                        val element = psiFile.findElementAt(hit.offset.coerceIn(0, psiFile.textLength - 1))
                            ?: return@mapNotNull null
                        UsageInfo2UsageAdapter(UsageInfo(element)) as Usage
                    }
                }
                app.invokeLater({
                    if (project.isDisposed) return@invokeLater
                    val presentation = UsageViewPresentation().apply {
                        tabName = "Struct Usages: $structName"
                        usagesString = "usages of struct $structName"
                        codeUsagesString = "struct $structName"
                        isCodeUsages = true
                        isOpenInNewTab = false
                        scopeText = "Project"
                    }
                    val targets = arrayOf<UsageTarget>(PsiElement2UsageTargetAdapter(definitionPsi))
                    UsageViewManager.getInstance(project).showUsages(targets, usages.toTypedArray(), presentation)
                    log.info("[hirust-mapper-navigator] Struct usages shown: ${structName} x${usages.size}")
                }, project.disposed)
            } catch (e: Exception) {
                log.warn("[hirust-mapper-navigator] Struct usage search failed: ${e.message}")
            }
        }
    }
}
