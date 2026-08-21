package com.hirust.mapper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 从 Rust 源码中提取 `.with_mapper_paths(vec![...])` 配置，
 * 解析出 XML mapper 文件的 glob 路径模式。
 */
class MapperPathsConfig(private val project: Project) {

    private val log = Logger.getInstance(MapperPathsConfig::class.java)

    private val _patterns: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    val patterns: List<String> get() = _patterns.toList()

    fun refresh() {
        _patterns.clear()
        val scope = GlobalSearchScope.projectScope(project)
        // FilenameIndex.getVirtualFilesByName 按"完整文件名"匹配,无法按扩展名检索,
        // 使用 FileTypeIndex(RustRover 中为 RustFileType,否则 PlainText)+ 扩展名过滤
        val rsFileType = try {
            FileTypeManager.getInstance().getFileTypeByExtension("rs")
        } catch (e: Exception) {
            log.warn("[hirust-mapper-navigator] Failed to resolve rs file type: ${e.message}")
            return
        }
        val virtualFiles = FileTypeIndex.getFiles(rsFileType, scope)
            .filter { it.extension.equals("rs", ignoreCase = true) }

        for (vf in virtualFiles) {
            extractPatternsFromFile(vf)
        }
        log.info("[hirust-mapper-navigator] MapperPathsConfig refreshed: ${_patterns.size} patterns " +
                "from ${virtualFiles.size} rs files")
    }

    private fun extractPatternsFromFile(vf: VirtualFile) {
        val content = try {
            VfsUtil.loadText(vf)
        } catch (e: Exception) {
            return
        }
        if (!content.contains("with_mapper_paths")) return
        extractStringLiteralsFromText(content)
    }

    /** 从源码文本中提取 with_mapper_paths 附近的 *.xml 字符串字面量 */
    private fun extractStringLiteralsFromText(content: String) {
        // 定位 with_mapper_paths 调用附近的 vec![...] 参数区间(粗略:其后 2000 字符)
        val regex = Regex("""with_mapper_paths\s*\(\s*vec!\s*\[""")
        val literalRegex = Regex("\"([^\"]+\\.xml)\"")
        for (call in regex.findAll(content)) {
            val region = content.substring(call.range.first, minOf(content.length, call.range.last + 2000))
            // 截断到 vec![...] 结束
            val regionEnd = region.indexOf(']')
            val scoped = if (regionEnd > 0) region.substring(0, regionEnd) else region
            for (match in literalRegex.findAll(scoped)) {
                val pattern = match.groupValues[1]
                if (pattern.contains("/") || pattern.contains("\\") || pattern.contains("*")) {
                    _patterns.addIfAbsent(pattern)
                }
            }
        }
    }

    companion object {
        fun getInstance(project: Project): MapperPathsConfig =
            project.getService(MapperPathsConfig::class.java)
    }
}
