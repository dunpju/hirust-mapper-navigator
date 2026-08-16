package com.hirust.mapper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceBase

/**
 * 宏定义引用：Ctrl+Click #[mapper_query] / #[dao] 等宏名称，
 * 跳转到 hirust-mapper crate 中的定义位置。
 *
 * 跳转目标:
 *   - "dao" → hirust-mapper-macros/src/lib.rs 中的 pub fn dao (#[proc_macro_attribute])
 *   - "mapper_query" → hirust-mapper-macros/src/dao.rs 中的 const METHOD_MARKER = "mapper_query"
 *   - "mapper_insert"/"mapper_update"/"mapper_delete" → 同上（mapper_query 是统一标记）
 */
class MacroDefinitionReference(
    element: PsiElement,
    private val macroName: String
) : PsiReferenceBase<PsiElement>(element) {

    private val log = Logger.getInstance(MacroDefinitionReference::class.java)

    override fun resolve(): PsiElement? {
        val project = element.project

        // 1. 查找 hirust-mapper 本地依赖的路径
        val facadePath = findLocalDependencyPath(project)
        if (facadePath != null) {
            log.info("[hirust-mapper] Facade path: ${facadePath.path}")

            // facade crate 的兄弟目录中找 hirust-mapper-macros（proc-macro crate）
            val macrosDir = findMacrosCrate(facadePath)
            if (macrosDir != null) {
                log.info("[hirust-mapper] Macros crate: ${macrosDir.path}")
                val result = findTarget(project, macrosDir, macroName)
                if (result != null) return result
            }
        }

        // 2. 兜底：在项目兄弟目录中全局搜索
        return globalSearchForMacrosCrate(project)
    }

    /**
     * 从项目 Cargo.toml 解析 hirust-mapper 的 path 依赖。
     * 返回 facade crate 的目录，如 E:\share\hirust-mapper\hirust-mapper
     */
    private fun findLocalDependencyPath(project: Project): VirtualFile? {
        val baseDir = project.baseDir ?: return null
        val cargoToml = baseDir.findChild("Cargo.toml") ?: return null

        val content = try {
            VfsUtil.loadText(cargoToml)
        } catch (e: Exception) {
            log.warn("[hirust-mapper] Failed to read Cargo.toml: ${e.message}")
            return null
        }

        // 匹配 hirust-mapper = { ... path = "..." ... }
        val regex = Regex("""hirust-mapper\s*=\s*\{[^}]*path\s*=\s*"([^"]+)"""")
        val match = regex.find(content) ?: return null
        return baseDir.findFileByRelativePath(match.groupValues[1])
    }

    /**
     * 从 facade crate 目录的兄弟目录中找到 hirust-mapper-macros。
     * hirust-mapper/ 目录结构:
     *   hirust-mapper/
     *   ├── Cargo.toml          (workspace root)
     *   ├── hirust-mapper/       (facade crate) ← findLocalDependencyPath 找到这里
     *   ├── hirust-mapper-macros/ (proc-macro crate) ← 目标
     *   ├── hirust-mapper-core/
     *   └── hirust-mapper-runtime/
     */
    private fun findMacrosCrate(facadeDir: VirtualFile): VirtualFile? {
        val parentDir = facadeDir.parent ?: return null
        val macrosDir = parentDir.findChild("hirust-mapper-macros") ?: return null

        // 验证是 proc-macro crate
        val cargoToml = macrosDir.findChild("Cargo.toml") ?: return null
        return try {
            val content = VfsUtil.loadText(cargoToml)
            if (content.contains("proc-macro")) macrosDir else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 在 macros crate 中查找跳转目标。
     *
     * "dao" → lib.rs 中 pub fn dao 所在行
     * "mapper_query" 等 → dao.rs 中 METHOD_MARKER 常量所在行
     */
    private fun findTarget(project: Project, macrosDir: VirtualFile, name: String): PsiElement? {
        val psiManager = PsiManager.getInstance(project)
        val srcDir = macrosDir.findChild("src") ?: return null

        when (name) {
            "dao" -> {
                // 查找 lib.rs 中的 pub fn dao
                val libRs = srcDir.findChild("lib.rs") ?: return null
                val psiFile = psiManager.findFile(libRs) ?: return null
                return findTextTarget(psiFile, "pub fn dao", "dao")
            }
            "hirust_mapper" -> {
                // 查找 lib.rs 中的 pub fn hirust_mapper
                val libRs = srcDir.findChild("lib.rs") ?: return null
                val psiFile = psiManager.findFile(libRs) ?: return null
                return findTextTarget(psiFile, "pub fn hirust_mapper", "hirust_mapper")
            }
            else -> {
                // mapper_query 等 → dao.rs 中的 METHOD_MARKER = "mapper_query"
                val daoRs = srcDir.findChild("dao.rs") ?: return null
                val psiFile = psiManager.findFile(daoRs) ?: return null

                // 查找: const METHOD_MARKER: &str = "mapper_query";
                // 或包含 "mapper_query" 的 METHOD_MARKER 定义
                val markerMatch = findTextTarget(psiFile, "METHOD_MARKER", "mapper_query")
                if (markerMatch != null) return markerMatch

                // 兜底: 直接搜索字符串 "mapper_query" 在 dao.rs 中的出现
                return findTextTarget(psiFile, "\"$name\"", name)
            }
        }
    }

    /**
     * 在 PsiFile 中通过文本搜索定位目标，然后通过 PSI 偏移量找到 PsiElement。
     *
     * @param psiFile 目标文件
     * @param searchLineText 行级搜索文本（匹配包含此文本的行）
     * @param targetName 高亮跳转的标识符名称
     */
    private fun findTextTarget(psiFile: PsiFile, searchLineText: String, targetName: String): PsiElement? {
        val text = psiFile.text
        val lines = text.lines()
        var offset = 0

        for (line in lines) {
            if (line.contains(searchLineText)) {
                // 找到目标行，尝试定位 targetName 的偏移
                val nameIndex = line.indexOf(targetName)
                if (nameIndex >= 0) {
                    val targetOffset = offset + nameIndex
                    val element = psiFile.findElementAt(targetOffset)
                    if (element != null) {
                        // 在当前元素和兄弟中找精确匹配
                        var current: PsiElement? = element
                        while (current != null) {
                            val trimmed = current.text.trim()
                            if (trimmed == targetName || trimmed.contains(targetName)) {
                                return current
                            }
                            current = current.nextSibling
                        }
                        return element
                    }
                }

                // 如果行中找不到 targetName，返回行首任意 PsiElement
                val lineStartOffset = offset
                return psiFile.findElementAt(lineStartOffset)
            }
            offset += line.length + 1
        }
        return null
    }

    /**
     * 全局兜底搜索。
     */
    private fun globalSearchForMacrosCrate(project: Project): PsiElement? {
        val baseDir = project.baseDir ?: return null
        val parentDir = baseDir.parent ?: return null

        for (sibling in parentDir.children) {
            if (!sibling.isDirectory || !sibling.name.contains("hirust-mapper")) continue
            for (subDir in sibling.children) {
                if (!subDir.isDirectory) continue
                val cargoToml = subDir.findChild("Cargo.toml") ?: continue
                try {
                    val content = VfsUtil.loadText(cargoToml)
                    if (content.contains("proc-macro")) {
                        val result = findTarget(project, subDir, macroName)
                        if (result != null) return result
                    }
                } catch (_: Exception) {}
            }
        }
        return null
    }

    companion object {
        val KNOWN_MACROS = setOf(
            "dao", "hirust_mapper", "mapper_query",
            "mapper_insert", "mapper_update", "mapper_delete", "mapper_select",
            "mapper", "mapper_exec", "mapper_batch"
        )

        fun isMapperMacro(macroName: String): Boolean {
            return macroName in KNOWN_MACROS || macroName.startsWith("mapper_")
        }
    }
}
