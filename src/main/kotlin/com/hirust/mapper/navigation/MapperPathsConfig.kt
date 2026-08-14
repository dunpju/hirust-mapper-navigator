package com.hirust.mapper.navigation

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.search.GlobalSearchScope
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 从 Rust 源码中提取 `.with_mapper_paths(vec![...])` 配置，
 * 解析出 XML mapper 文件的 glob 路径模式。
 *
 * 使用通用 PsiElement API + 文本搜索，编译时不依赖 org.rust.lang 插件。
 *
 * 示例配置:
 *   .with_mapper_paths(vec!["resources/mapper/**/*.xml".to_string()]);
 *
 * 提取结果:
 *   ["resources/mapper/**/*.xml"]
 */
class MapperPathsConfig(private val project: Project) {

    /** 当前生效的 glob 模式列表 */
    private val _patterns: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    val patterns: List<String> get() = _patterns.toList()

    /**
     * 扫描项目中所有 Rust 文件，查找 `.with_mapper_paths(...)` 调用，
     * 提取其中的字符串字面量作为 glob 模式。
     */
    fun refresh() {
        _patterns.clear()
        val scope = GlobalSearchScope.projectScope(project)
        val psiManager = PsiManager.getInstance(project)

        // 遍历项目中所有 .rs 文件
        val virtualFiles = com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(
            ".rs", scope, project
        )
        for (vf in virtualFiles) {
            val psiFile = psiManager.findFile(vf) ?: continue
            extractPatternsFromFile(psiFile)
        }
    }

    /**
     * 从单个 Rust 文件中提取 with_mapper_paths 参数。
     *
     * 使用 PsiRecursiveElementVisitor 遍历所有 PSI 节点，
     * 查找文本中包含 "with_mapper_paths" 的元素，
     * 然后从其子节点中提取字符串字面量。
     */
    private fun extractPatternsFromFile(psiFile: com.intellij.psi.PsiFile) {
        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                val text = element.text

                // 检测 with_mapper_paths 调用
                if (text.contains("with_mapper_paths")) {
                    extractStringLiteralsFromContext(element)
                }
            }
        })
    }

    /**
     * 从包含 with_mapper_paths 的 PSI 元素及其子元素中提取字符串字面量。
     *
     * 支持多种 Rust 写法:
     *   .with_mapper_paths(vec!["resources/mapper/**/*.xml".to_string()])
     *   .with_mapper_paths(vec!["resources/mapper/**/*.xml"])
     *   .with_mapper_paths("resources/mapper/**/*.xml")
     */
    private fun extractStringLiteralsFromContext(element: PsiElement) {
        val text = element.text

        // 通用方式: 用正则从整个上下文文本中提取 "....xml" 字符串
        // 这涵盖了 vec![] 宏、.to_string() 链等各种写法
        val regex = Regex("\"([^\"]+\\.xml)\"")
        for (match in regex.findAll(text)) {
            val pattern = match.groupValues[1]
            // 过滤: 只保留看起来像路径的模式（包含 '/' 或 '\\' 或 '*.xml'）
            if (pattern.contains("/") || pattern.contains("\\") || pattern.contains("*")) {
                _patterns.addIfAbsent(pattern)
            }
        }
    }

    companion object {
        fun getInstance(project: Project): MapperPathsConfig =
            project.getService(MapperPathsConfig::class.java)
    }
}
