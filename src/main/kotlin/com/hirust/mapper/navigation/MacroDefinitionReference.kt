package com.hirust.mapper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceBase

/**
 * 宏定义引用：Ctrl+Click #[mapper_query] / #[dao] 等宏名称，
 * 跳转到 hirust-mapper crate 中对应的 proc_macro_attribute 定义位置。
 *
 * 使用通用 PsiElement API，编译时不依赖 org.rust.lang 插件。
 * 通过文本搜索在 Rust 文件中定位 proc_macro_attribute 宏定义。
 *
 * 查找策略（优先级）:
 *   1. Cargo.toml 中 path = "..." 的本地依赖（如 path = "../hirust-mapper/hirust-mapper"）
 *   2. 项目兄弟目录中的 proc-macro crate
 */
class MacroDefinitionReference(
    element: PsiElement,
    private val macroName: String
) : PsiReferenceBase<PsiElement>(element) {

    private val log = Logger.getInstance(MacroDefinitionReference::class.java)

    override fun resolve(): PsiElement? {
        val project = element.project
        val startTime = System.currentTimeMillis()

        // 策略1: 查找 hirust-mapper 本地依赖的路径
        val localPath = findLocalDependencyPath(project)
        if (localPath != null) {
            log.info("[hirust-mapper-navigator] Found local dependency path: $localPath")
            val result = findProcMacroInDirectory(project, localPath, macroName)
            if (result != null) return result
        }

        // 策略2: 搜索项目兄弟目录中的 proc-macro crate
        val result = globalSearchForProcMacro(project, macroName)

        val elapsed = System.currentTimeMillis() - startTime
        log.debug("[hirust-mapper-navigator] Resolved '$macroName' in ${elapsed}ms: ${result?.let { "found" } ?: "not found"}")

        return result
    }

    /**
     * 从项目 Cargo.toml 中查找 hirust-mapper 的本地路径依赖。
     *
     * 示例:
     *   hirust-mapper = {path = "../hirust-mapper/hirust-mapper", version = "0.2", features = [...]}
     */
    private fun findLocalDependencyPath(project: Project): VirtualFile? {
        val baseDir = project.baseDir ?: return null
        val cargoToml = baseDir.findChild("Cargo.toml") ?: return null

        val content = try {
            com.intellij.openapi.vfs.VfsUtil.loadText(cargoToml)
        } catch (e: Exception) {
            log.warn("[hirust-mapper-navigator] Failed to read Cargo.toml: ${e.message}")
            return null
        }

        // 匹配 hirust-mapper = { ... path = "..." ... }
        val regex = Regex("""hirust-mapper\s*=\s*\{[^}]*path\s*=\s*"([^"]+)"""")
        val match = regex.find(content)
        if (match != null) {
            return baseDir.findFileByRelativePath(match.groupValues[1])
        }
        return null
    }

    /**
     * 在指定目录中查找 proc_macro_attribute 宏定义。
     *
     * 搜索策略:
     *   1. 找到所有 .rs 文件
     *   2. 用文本搜索定位 #[proc_macro_attribute] + pub fn <macroName>
     *   3. 通过 PSI 偏移量定位到精确的 PsiElement
     */
    private fun findProcMacroInDirectory(
        project: Project,
        crateDir: VirtualFile,
        targetMacroName: String
    ): PsiElement? {
        val srcDir = crateDir.findChild("src") ?: return null
        return searchRustFilesRecursively(project, srcDir, targetMacroName)
    }

    /**
     * 递归搜索目录下的所有 .rs 文件
     */
    private fun searchRustFilesRecursively(
        project: Project,
        dir: VirtualFile,
        targetMacroName: String
    ): PsiElement? {
        val psiManager = PsiManager.getInstance(project)

        for (child in dir.children) {
            if (child.isDirectory) {
                val result = searchRustFilesRecursively(project, child, targetMacroName)
                if (result != null) return result
            } else if (child.extension == "rs") {
                val psiFile = psiManager.findFile(child) ?: continue
                val result = findProcMacroInFile(psiFile, targetMacroName)
                if (result != null) return result
            }
        }
        return null
    }

    /**
     * 在单个 Rust 文件中查找 proc_macro_attribute 宏定义。
     *
     * 使用文本匹配而非类型检查:
     *   1. 找到包含 "proc_macro_attribute" 和 "pub fn <macroName>" 的行
     *   2. 计算 PSI 偏移量，定位到函数名 PsiElement
     */
    private fun findProcMacroInFile(psiFile: PsiFile, targetMacroName: String): PsiElement? {
        val text = psiFile.text
        val lines = text.lines()

        var inProcMacroAttr = false
        var currentOffset = 0

        for (line in lines) {
            if (line.contains("proc_macro_attribute") || line.contains("proc_macro")) {
                inProcMacroAttr = true
            }

            if (inProcMacroAttr && line.contains("pub fn $targetMacroName")) {
                // 找到目标: 计算 "pub fn <name>" 中函数名的起始偏移
                val fnKeywordIndex = line.indexOf("fn ")
                if (fnKeywordIndex >= 0) {
                    val nameStartInLine = fnKeywordIndex + 3  // "fn " 的长度
                    val nameEndInLine = line.indexOf('(', nameStartInLine).let {
                        if (it < 0) line.length else it
                    }
                    val nameStartOffset = currentOffset + nameStartInLine
                    val nameEndOffset = currentOffset + nameEndInLine

                    // 通过 PsiElement 的偏移量找到精确的标识符元素
                    val element = psiFile.findElementAt(nameStartOffset)
                    if (element != null) {
                        // 查找包含完整函数名的元素
                        val nameText = text.substring(nameStartOffset, nameEndOffset).trim()
                        if (element.text.trim() == nameText || element.text.trimStart().startsWith(nameText)) {
                            return element
                        }
                        // 尝试在兄弟元素中查找
                        var current: PsiElement? = element
                        while (current != null && current.textRange.startOffset < nameEndOffset) {
                            if (current.text.trim() == nameText) return current
                            current = current.nextSibling
                        }
                    }
                }
                return null  // 找到了 proc_macro_attribute 但函数名不匹配
            }

            // 如果遇到了另一个函数定义但没有匹配到目标，重置标记
            if (inProcMacroAttr && line.trimStart().startsWith("pub fn ") &&
                !line.contains(targetMacroName)) {
                inProcMacroAttr = false
            }

            currentOffset += line.length + 1  // +1 for newline
        }
        return null
    }

    /**
     * 全局搜索 proc macro 定义（兜底策略）。
     *
     * 搜索项目兄弟目录中名称包含 "hirust-mapper" 的目录，
     * 在其中的 proc-macro crate 中查找宏定义。
     */
    private fun globalSearchForProcMacro(
        project: Project,
        targetMacroName: String
    ): PsiElement? {
        val baseDir = project.baseDir ?: return null
        val parentDir = baseDir.parent ?: return null

        for (sibling in parentDir.children) {
            if (!sibling.isDirectory) continue
            if (sibling.name.contains("hirust-mapper")) {
                for (subDir in sibling.children) {
                    if (!subDir.isDirectory) continue
                    if (isProcMacroCrate(subDir)) {
                        val result = findProcMacroInDirectory(project, subDir, targetMacroName)
                        if (result != null) return result
                    }
                }
            }
        }
        return null
    }

    /**
     * 判断目录是否是 proc-macro crate。
     */
    private fun isProcMacroCrate(dir: VirtualFile): Boolean {
        val cargoToml = dir.findChild("Cargo.toml") ?: return false
        return try {
            val content = com.intellij.openapi.vfs.VfsUtil.loadText(cargoToml)
            content.contains("[lib]") && content.contains("proc-macro")
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        /** 已知的 hirust-mapper 宏名称集合 */
        val KNOWN_MACROS = setOf(
            "dao",
            "mapper_query",
            "mapper_insert",
            "mapper_update",
            "mapper_delete",
            "mapper_select",
            "mapper",
            "mapper_exec",
            "mapper_batch"
        )

        /** 判断宏名称是否属于 mapper 系列 */
        fun isMapperMacro(macroName: String): Boolean {
            return macroName in KNOWN_MACROS || macroName.startsWith("mapper_")
        }
    }
}
