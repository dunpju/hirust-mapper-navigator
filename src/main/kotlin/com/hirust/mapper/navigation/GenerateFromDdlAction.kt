package com.hirust.mapper.navigation

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Paths

/**
 * DDL → Rust struct + DAO + mapper XML 脚手架生成(v1.5.0)。
 *
 * 在 .sql 文件中编写/粘贴 CREATE TABLE DDL 后触发(右键菜单 / Generate 菜单):
 * - 解析全部表定义(列/类型/可空/主键)
 * - 生成三份文件:模型 struct、DAO(#[dao] + CRUD #[mapper_*])、mapper XML
 * - 目标路径与 namespace 从现有工程结构推导(dao 目录、mapper 目录、namespace 模式)
 * - 已存在的文件跳过;mod.rs 存在时自动追加 pub mod 声明
 */
class GenerateFromDdlAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = vFile != null && vFile.extension == "sql"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (vFile.extension != "sql") return

        val tables = DdlScaffolding.parseCreateTables(editor.document.text)
        if (tables.isEmpty()) {
            notify(project, "未在 .sql 中找到 CREATE TABLE 语句", NotificationType.WARNING)
            return
        }

        // 前置检查:Cargo.toml 依赖(仅提醒,不阻断;用户可选择继续)
        val missing = checkCargoDependencies(project, vFile)
        if (missing.isNotEmpty()) {
            val msg = "目前项目未引入 ${missing.joinToString("、")} 依赖库,是否继续生成?\n(生成后请自行在 Cargo.toml 中添加缺失依赖)"
            val continueGen = com.intellij.openapi.ui.Messages.showYesNoDialog(
                project, msg, "依赖检查", com.intellij.openapi.ui.Messages.getWarningIcon()
            )
            if (continueGen != com.intellij.openapi.ui.Messages.YES) return
        }

        val ctx = resolveProjectContext(project, vFile)
        val created = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        for (table in tables) {
            val stem = table.name
            val namespace = ctx.namespacePattern.replace(ctx.namespaceLastSegment, "${stem}_dao")
            val modelModule = modulePathOf(ctx.basePath, ctx.modelsDir)

            // 1. 模型 struct
            val modelFile = writeIfAbsent(
                project, Paths.get(ctx.modelsDir, "$stem.rs").toString(),
                DdlScaffolding.structCode(table)
            )
            (if (modelFile != null) created else skipped).add(relPath(ctx.basePath, modelFile?.second
                ?: Paths.get(ctx.modelsDir, "$stem.rs").toString()))

            // 2. DAO(xml 相对路径从 Cargo 根推导,如 "resources/mapper/test_user.xml")
            val xmlRelPath = relPath(ctx.basePath, Paths.get(ctx.xmlDir, "$stem.xml").toString())
            val daoPath = Paths.get(ctx.daoDir, "${stem}_dao.rs").toString()
            val daoContent = DdlScaffolding.daoCode(table, namespace, modelModule, xmlRelPath)
            val daoFile = writeIfAbsent(project, daoPath, daoContent)
            (if (daoFile != null) created else skipped).add(relPath(ctx.basePath, daoFile?.second ?: daoPath))

            // 3. mapper XML
            val xmlPath = Paths.get(ctx.xmlDir, "$stem.xml").toString()
            val xmlContent = DdlScaffolding.mapperXml(table, namespace)
            val xmlFile = writeIfAbsent(project, xmlPath, xmlContent)
            (if (xmlFile != null) created else skipped).add(relPath(ctx.basePath, xmlFile?.second ?: xmlPath))
        }

        // mod.rs 追加声明(存在才追加)
        for (table in tables) {
            appendModDecl(project, ctx.modelsDir, table.name)
            appendModDecl(project, ctx.daoDir, "${table.name}_dao")
        }

        // 确保父级 mod.rs 声明 models / dao 目录(即使 mod.rs 已存在也要补)
        ensureParentModDecl(project, ctx.modelsDir)
        ensureParentModDecl(project, ctx.daoDir)

        // 保存所有文档(mod.rs 追加等编辑从内存刷到磁盘),再强制重建索引
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            try {
                FileDocumentManager.getInstance().saveAllDocuments()
            } catch (_: Exception) {
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    MapperScanCoordinator.getInstance(project).forceRebuild()
                } catch (_: Exception) {
                }
            }
        }, project.disposed)

        val msg = buildString {
            append("DDL 脚手架:${tables.size} 张表,生成 ${created.size} 个文件")
            if (skipped.isNotEmpty()) append(";跳过已存在:${skipped.joinToString(", ")}")
        }
        notify(project, msg, NotificationType.INFORMATION)
    }

    // ------------------------------------------------------------------
    // 工程上下文推导
    // ------------------------------------------------------------------

    private data class ProjectContext(
        val basePath: String,
        val daoDir: String,
        val modelsDir: String,
        val xmlDir: String,
        val namespacePattern: String,
        val namespaceLastSegment: String
    )

    private fun resolveProjectContext(project: Project, sqlFile: VirtualFile): ProjectContext {
        // base 取 .sql 所在 Rust crate 根(Cargo.toml 所在目录),而非 project.basePath
        val base = findCargoRoot(sqlFile) ?: project.basePath ?: ""

        // dao 目录与 namespace 模式:取任一已索引 DAO 的位置
        val daoLoc = RustDaoIndex.getInstance(project).allDaos().firstOrNull()
        val daoDir = daoLoc?.file?.parent?.path ?: Paths.get(base, "src", "app", "dao").toString()
        val ns = daoLoc?.dao?.namespace ?: "crate::app::dao::placeholder_dao"
        val lastSegment = ns.substringAfterLast("::")

        // models 目录:优先现有 src/app/models,缺省与 dao 同级的 models
        val preferredModels = Paths.get(base, "src", "app", "models").toString()
        val modelsDir = if (dirExists(preferredModels)) preferredModels
        else Paths.get(daoDir).parent.resolve("models").toString()

        // xml 目录:with_mapper_paths 首个字面目录,缺省 resources/mapper
        val defaultXmlDir = Paths.get(base, "resources", "mapper").toString()
        val mp = MapperPathsConfig.getInstance(project).patterns.firstOrNull()
        val xmlDir = if (mp != null) {
            val dirPart = mp.pattern.substringBeforeLast("**").trimEnd('/')
            val dirRoot = mp.baseDirPath?.takeIf { it.isNotEmpty() } ?: base
            if (dirPart.isNotEmpty()) Paths.get(dirRoot, dirPart).toString() else defaultXmlDir
        } else defaultXmlDir

        return ProjectContext(base, daoDir, modelsDir, xmlDir, ns, lastSegment)
    }

    /** 检查 Cargo.toml 是否引入了脚手架所需的全部依赖;返回缺失项列表(从 .sql 文件向上找 Cargo.toml) */
    /** 确保父级目录的 mod.rs 中有本目录的 pub mod 声明(如 `pub mod models;` 加到 app/mod.rs) */
    private fun ensureParentModDecl(project: Project, dir: String) {
        val fixed = dir.replace('\\', '/')
        val parentDir = fixed.substringBeforeLast('/').let { if (it == fixed) null else it } ?: return
        val moduleName = fixed.substringAfterLast('/')
        appendModDecl(project, parentDir, moduleName)
    }

    /** 从 .sql 文件向上找 Cargo.toml 所在目录(Rust crate 根) */
    private fun findCargoRoot(sqlFile: VirtualFile): String? {
        var dir: VirtualFile? = sqlFile.parent
        while (dir != null) {
            if (dir.findChild("Cargo.toml") != null) return dir.path
            dir = dir.parent
        }
        return null
    }

    private fun checkCargoDependencies(project: Project, sqlFile: VirtualFile): List<String> {
        // 从 .sql 文件所在目录向上查找 Cargo.toml(兼容项目根 ≠ Rust crate 根的场景)
        var dir: VirtualFile? = sqlFile.parent
        var cargoToml: VirtualFile? = null
        while (dir != null) {
            val found = dir.findChild("Cargo.toml")
            if (found != null) {
                cargoToml = found
                break
            }
            dir = dir.parent
        }
        if (cargoToml == null) return listOf("Cargo.toml(未在 .sql 所在目录及上级找到)")

        val content = try {
            com.intellij.openapi.vfs.VfsUtil.loadText(cargoToml)
        } catch (_: Exception) {
            return listOf("Cargo.toml(无法读取)")
        }

        val required = listOf("hirust-mapper", "hirust-mapper-runtime", "serde_json", "serde")
        return required.filter { !content.contains(it) }
    }

    private fun dirExists(path: String): Boolean =
        LocalFileSystem.getInstance().findFileByPath(path.replace('\\', '/'))?.isDirectory == true

    /** 目录绝对路径 → Rust 模块路径(src 之下,目录分隔转 ::;以 Cargo 根为基准) */
    private fun modulePathOf(basePath: String, dir: String): String {
        if (basePath.isEmpty()) return ""
        val rel = Paths.get(basePath).relativize(Paths.get(dir)).toString().replace('\\', '/')
        if (!rel.startsWith("src/")) return ""
        return "crate::" + rel.removePrefix("src/").split('/')
            .filter { it.isNotEmpty() }.joinToString("::")
    }

    /** 绝对路径 → 相对 Cargo 根的显示路径 */
    private fun relPath(basePath: String, absPath: String): String {
        if (basePath.isEmpty()) return absPath
        return Paths.get(basePath).relativize(Paths.get(absPath)).toString().replace('\\', '/')
    }

    // ------------------------------------------------------------------
    // 文件写入
    // ------------------------------------------------------------------

    /** 文件不存在则创建并写入(目录不存在则 mkdirs;VfsUtil 直写磁盘);返回 null=已跳过 */
    private fun writeIfAbsent(project: Project, absPath: String, content: String): Pair<VirtualFile, String>? {
        val lfs = LocalFileSystem.getInstance()
        val fixed = absPath.replace('\\', '/')
        lfs.refreshAndFindFileByPath(fixed)?.let { return null }

        // Java IO 递归创建目录,再刷新 VFS 获取句柄
        val dirPath = fixed.substringBeforeLast('/')
        val dirIo = java.io.File(dirPath)
        if (!dirIo.exists() && !dirIo.mkdirs()) return null
        lfs.refreshAndFindFileByPath(dirPath) ?: return null

        var result: Pair<VirtualFile, String>? = null
        WriteCommandAction.runWriteCommandAction(project, "Generate DDL Scaffolding", null, {
            val dir = lfs.refreshAndFindFileByPath(dirPath) ?: return@runWriteCommandAction
            val vf = dir.createChildData(this, fixed.substringAfterLast('/'))
            // VfsUtil 直写磁盘(不经过 Document 层),索引可立即读取
            com.intellij.openapi.vfs.VfsUtil.saveText(vf, content)
            result = vf to fixed
        })
        return result
    }

    /** mod.rs 不存在则创建;存在时追加 `pub mod xxx;`(已存在则跳过) */
    private fun appendModDecl(project: Project, dir: String, moduleName: String) {
        val modPath = Paths.get(dir, "mod.rs").toString().replace('\\', '/')
        val modFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(modPath)

        if (modFile == null) {
            // 新建目录:创建 mod.rs 并写入首条声明
            writeIfAbsent(project, modPath, "pub mod $moduleName;\n")
            // 向上级目录的 mod.rs 追加本目录的模块声明(如 `pub mod models;`)
            val parentDir = dir.substringBeforeLast('/').let {
                if (it == dir) null else it
            }
            if (parentDir != null) {
                val parentModName = dir.substringAfterLast('/')
                appendModDecl(project, parentDir, parentModName)
            }
            return
        }

        val doc = FileDocumentManager.getInstance().getDocument(modFile) ?: return
        val decl = "pub mod $moduleName;\n"
        if (doc.text.contains("pub mod $moduleName;")) return
        WriteCommandAction.runWriteCommandAction(project, "Append mod decl", null, {
            doc.insertString(doc.textLength, decl)
        })
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Hirust Mapper Navigator")
            .createNotification(message, type)
            .notify(project)
    }
}
