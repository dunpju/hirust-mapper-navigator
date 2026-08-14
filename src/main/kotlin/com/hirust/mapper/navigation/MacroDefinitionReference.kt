package com.hirust.mapper.navigation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsMetaItem
import org.rust.lang.core.psi.ext.descendantsOfType
import org.rust.cargo.project.workspace.CargoWorkspace

/**
 * 宏定义引用：Ctrl+Click #[mapper_query] / #[dao] 等宏名称，
 * 跳转到 hirust-mapper crate 中对应的 proc_macro_attribute 定义位置。
 *
 * PSI 位置: RsMetaItem 中的 RsIdent（宏名称标识符）
 *
 * 查找策略（优先级）:
 *   1. Cargo.toml 中 path = "..." 的本地依赖（如 path = "../hirust-mapper/hirust-mapper"）
 *   2. Cargo workspace 成员
 *   3. crates.io 缓存目录 (~/.cargo/registry/src/)
 */
class MacroDefinitionReference(
    element: PsiElement,
    private val macroName: String
) : PsiReferenceBase<PsiElement>(element) {

    private val log = Logger.getInstance(MacroDefinitionReference::class.java)

    override fun resolve(): PsiElement? {
        val project = element.project
        val startTime = System.currentTimeMillis()

        val result = resolveInDependencies(project)

        val elapsed = System.currentTimeMillis() - startTime
        log.debug("[hirust-mapper-navigator] Resolved '$macroName' in ${elapsed}ms: ${result?.let { "found" } ?: "not found"}")

        return result
    }

    /**
     * 在 Cargo 依赖中查找 proc macro 定义
     */
    private fun resolveInDependencies(project: Project): PsiElement? {
        // 获取 Cargo 项目工作空间
        val cargoWorkspace = getCargoWorkspace(project) ?: run {
            log.warn("[hirust-mapper-navigator] Cargo workspace not available for project")
            return null
        }

        // 策略1: 查找 hirust-mapper 本地依赖的路径
        val localPath = findLocalDependencyPath(project)
        if (localPath != null) {
            log.info("[hirust-mapper-navigator] Found local dependency path: $localPath")
            val result = findProcMacroInDirectory(project, localPath, macroName)
            if (result != null) return result
        }

        // 策略2: 搜索所有 Cargo workspace 中的 proc-macro crate
        val result = searchInCargoWorkspace(project, cargoWorkspace, macroName)
        if (result != null) return result

        // 策略3: 全局文件搜索（搜索包含 proc_macro_attribute 定义的所有 Rust 文件）
        return globalSearchForProcMacro(project, macroName)
    }

    /**
     * 从项目 Cargo.toml 中查找 hirust-mapper 的本地路径依赖
     *
     * 示例:
     *   hirust-mapper = {path = "../hirust-mapper/hirust-mapper", version = "0.2", features = [...]}
     *
     * 返回: "../hirust-mapper/hirust-mapper" 的绝对路径
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

        // 查找 hirust-mapper 的 path 依赖
        // 匹配模式: hirust-mapper = { ... path = "..." ... }
        // 或: hirust-mapper = { version = "0.2", path = "../hirust-mapper/hirust-mapper" }
        val hirustMapperRegex = Regex(
            """hirust-mapper\s*=\s*\{[^}]*path\s*=\s*"([^"]+)"[^}]*\}"""
        )
        val match = hirustMapperRegex.find(content)
        if (match != null) {
            val relativePath = match.groupValues[1]
            return baseDir.findFileByRelativePath(relativePath)
        }

        // 也检查简单的路径形式: hirust-mapper = { path = "..." }
        val simpleRegex = Regex(
            """hirust-mapper\s*=\s*\{[^}]*path\s*=\s*"([^"]+)"""".toRegex()
        )
        val simpleMatch = simpleRegex.find(content)
        if (simpleMatch != null) {
            val relativePath = simpleMatch.groupValues[1]
            return baseDir.findFileByRelativePath(relativePath)
        }

        return null
    }

    /**
     * 在指定目录中查找 proc_macro_attribute 宏定义
     *
     * 搜索目标:
     *   #[proc_macro_attribute]
     *   pub fn mapper_query(...) { ... }
     */
    private fun findProcMacroInDirectory(
        project: Project,
        crateDir: VirtualFile,
        targetMacroName: String
    ): PsiElement? {
        val psiManager = PsiManager.getInstance(project)

        // proc macro crate 通常有 Cargo.toml 中声明 proc-macro = true
        // 查找 src/ 目录下的所有 Rust 文件
        val srcDir = crateDir.findChild("src") ?: return null

        for (file in srcDir.children) {
            if (file.extension != "rs") continue

            val psiFile = psiManager.findFile(file) as? RsFile ?: continue
            val result = findProcMacroInFile(psiFile, targetMacroName)
            if (result != null) return result
        }

        // 也搜索子目录
        return searchSubdirectories(project, srcDir, targetMacroName)
    }

    /**
     * 递归搜索子目录
     */
    private fun searchSubdirectories(
        project: Project,
        dir: VirtualFile,
        targetMacroName: String
    ): PsiElement? {
        val psiManager = PsiManager.getInstance(project)
        for (child in dir.children) {
            if (child.isDirectory) {
                val result = searchSubdirectories(project, child, targetMacroName)
                if (result != null) return result
            } else if (child.extension == "rs") {
                val psiFile = psiManager.findFile(child) as? RsFile ?: continue
                val result = findProcMacroInFile(psiFile, targetMacroName)
                if (result != null) return result
            }
        }
        return null
    }

    /**
     * 在单个 Rust 文件中查找 proc_macro_attribute 宏定义
     *
     * 查找模式:
     *   #[proc_macro_attribute]
     *   pub fn <macroName>(...) { ... }
     */
    private fun findProcMacroInFile(rsFile: RsFile, targetMacroName: String): PsiElement? {
        // 遍历文件中所有的属性和函数
        val functions = rsFile.descendantsOfType<RsFunction>()

        for (fn in functions) {
            // 检查函数名是否匹配
            if (fn.name != targetMacroName) continue

            // 检查函数上方是否有 #[proc_macro_attribute] 或 #[proc_macro] 属性
            val attrs = fn.outerAttrList
            for (attr in attrs) {
                val attrText = attr.text
                if (attrText.contains("proc_macro_attribute") ||
                    attrText.contains("proc_macro")
                ) {
                    // 找到匹配的 proc macro 定义，返回函数名标识符
                    return fn.identifier?.psi
                }
            }
        }
        return null
    }

    /**
     * 在 Cargo workspace 中搜索 proc macro crate
     */
    private fun searchInCargoWorkspace(
        project: Project,
        cargoWorkspace: CargoWorkspace,
        targetMacroName: String
    ): PsiElement? {
        // 遍历所有 workspace 中的 crate
        for (pkg in cargoWorkspace.packages) {
            val contentRoot = pkg.contentRoot
            if (contentRoot == null || !contentRoot.isValid) continue

            // 检查是否是 proc-macro crate
            if (isProcMacroCrate(contentRoot)) {
                val result = findProcMacroInDirectory(project, contentRoot, targetMacroName)
                if (result != null) return result
            }
        }
        return null
    }

    /**
     * 判断目录是否是 proc-macro crate
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

    /**
     * 全局搜索 proc macro 定义（兜底策略）
     *
     * 在项目的所有 Rust 依赖中搜索包含 proc_macro_attribute 定义且函数名匹配的文件。
     */
    private fun globalSearchForProcMacro(
        project: Project,
        targetMacroName: String
    ): PsiElement? {
        // 在所有依赖的外部 crate 目录中搜索
        // 主要搜索本地 path 依赖的上级目录
        val baseDir = project.baseDir ?: return null
        val parentDir = baseDir.parent ?: return null

        // 搜索兄弟目录（如 ../hirust-mapper/）
        for (sibling in parentDir.children) {
            if (!sibling.isDirectory) continue
            if (sibling.name.contains("hirust-mapper")) {
                // 搜索 hirust-mapper 目录下的所有 proc-macro 子 crate
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
     * 获取项目的 CargoWorkspace
     */
    private fun getCargoWorkspace(project: Project): CargoWorkspace? {
        val service = try {
            org.rust.cargo.project.toolwindow.CargoProjectsService.getInstance(project)
        } catch (e: Exception) {
            log.debug("[hirust-mapper-navigator] CargoProjectsService not available: ${e.message}")
            return null
        }

        val projects = service.allProjects
        if (projects.isEmpty()) {
            log.warn("[hirust-mapper-navigator] No Cargo projects found")
            return null
        }

        // 取第一个项目的 workspace
        return projects.first().workspace
    }

    companion object {
        /**
         * 已知的 hirust-mapper 宏名称集合
         */
        val KNOWN_MACROS = setOf(
            "dao",
            "mapper_query",
            "mapper_insert",
            "mapper_update",
            "mapper_delete",
            "mapper_select",
            // 扩展: 其他可能的 mapper_* 宏
            "mapper",
            "mapper_exec",
            "mapper_batch"
        )

        /**
         * 判断宏名称是否属于 mapper 系列
         */
        fun isMapperMacro(macroName: String): Boolean {
            return macroName in KNOWN_MACROS || macroName.startsWith("mapper_")
        }
    }
}
