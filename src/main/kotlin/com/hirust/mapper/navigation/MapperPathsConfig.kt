package com.hirust.mapper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 从 Rust 源码中提取 `.with_mapper_paths(vec![...])` 配置，
 * 解析出 XML mapper 文件的 glob 路径模式。
 *
 * 扫描入口由 [MapperScanCoordinator] 统一协调(单次 .rs 遍历),
 * 本类只负责从【已读取的内容】提取模式,不再自行读盘。
 */
class MapperPathsConfig(private val project: Project) {

    private val log = Logger.getInstance(MapperPathsConfig::class.java)

    private val _patterns: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    val patterns: List<String> get() = _patterns.toList()

    /** 协调扫描开始:清空旧模式 */
    fun beginScan() {
        _patterns.clear()
    }

    /** 协调扫描喂数据:从已读取的文件内容提取模式(避免重复 IO) */
    fun acceptFileContent(content: String) {
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
