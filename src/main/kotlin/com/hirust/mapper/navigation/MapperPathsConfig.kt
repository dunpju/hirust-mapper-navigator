package com.hirust.mapper.navigation

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 从 Rust 源码中提取 `.with_mapper_paths(vec![...])` 配置，
 * 解析出 XML mapper 文件的 glob 路径模式。
 */
class MapperPathsConfig(private val project: Project) {

    private val _patterns: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    val patterns: List<String> get() = _patterns.toList()

    fun refresh() {
        _patterns.clear()
        val scope = GlobalSearchScope.projectScope(project)
        val psiManager = PsiManager.getInstance(project)

        val virtualFiles = FilenameIndex.getVirtualFilesByName(".rs", scope, project)
        for (vf in virtualFiles) {
            val psiFile = psiManager.findFile(vf) ?: continue
            extractPatternsFromFile(psiFile)
        }
    }

    private fun extractPatternsFromFile(psiFile: PsiFile) {
        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                if (element.text.contains("with_mapper_paths")) {
                    extractStringLiteralsFromContext(element)
                }
            }
        })
    }

    private fun extractStringLiteralsFromContext(element: PsiElement) {
        val text = element.text
        val regex = Regex("\"([^\"]+\\.xml)\"")
        for (match in regex.findAll(text)) {
            val pattern = match.groupValues[1]
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
