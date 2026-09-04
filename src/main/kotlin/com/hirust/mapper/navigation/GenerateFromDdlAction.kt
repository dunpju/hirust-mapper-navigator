package com.hirust.mapper.navigation

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
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

        val ctx = resolveProjectContext(project)
        val created = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        for (table in tables) {
            val stem = table.name
            val namespace = ctx.namespacePattern.replace(ctx.namespaceLastSegment, "${stem}_dao")
            val modelModule = modulePathOf(project, ctx.modelsDir)

            // 1. 模型 struct
            val modelFile = writeIfAbsent(
                project, Paths.get(ctx.modelsDir, "$stem.rs").toString(),
                DdlScaffolding.structCode(table)
            )
            (if (modelFile != null) created else skipped).add(relPath(project, modelFile?.second
                ?: Paths.get(ctx.modelsDir, "$stem.rs").toString()))

            // 2. DAO
            val daoPath = Paths.get(ctx.daoDir, "${stem}_dao.rs").toString()
            val daoContent = DdlScaffolding.daoCode(table, namespace, modelModule)
            val daoFile = writeIfAbsent(project, daoPath, daoContent)
            (if (daoFile != null) created else skipped).add(relPath(project, daoFile?.second ?: daoPath))

            // 3. mapper XML
            val xmlPath = Paths.get(ctx.xmlDir, "$stem.xml").toString()
            val xmlContent = DdlScaffolding.mapperXml(table, namespace)
            val xmlFile = writeIfAbsent(project, xmlPath, xmlContent)
            (if (xmlFile != null) created else skipped).add(relPath(project, xmlFile?.second ?: xmlPath))
        }

        // mod.rs 追加声明(存在才追加)
        for (table in tables) {
            appendModDecl(project, ctx.modelsDir, table.name)
            appendModDecl(project, ctx.daoDir, "${table.name}_dao")
        }

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
        val daoDir: String,
        val modelsDir: String,
        val xmlDir: String,
        val namespacePattern: String,
        val namespaceLastSegment: String
    )

    private fun resolveProjectContext(project: Project): ProjectContext {
        val base = project.basePath ?: ""

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

        return ProjectContext(daoDir, modelsDir, xmlDir, ns, lastSegment)
    }

    private fun dirExists(path: String): Boolean =
        LocalFileSystem.getInstance().findFileByPath(path.replace('\\', '/'))?.isDirectory == true

    /** 目录绝对路径 → Rust 模块路径(src 之下,目录分隔转 ::) */
    private fun modulePathOf(project: Project, dir: String): String {
        val base = project.basePath ?: return ""
        val rel = Paths.get(base).relativize(Paths.get(dir)).toString().replace('\\', '/')
        if (!rel.startsWith("src/")) return ""
        return "crate::" + rel.removePrefix("src/").split('/')
            .filter { it.isNotEmpty() }.joinToString("::")
    }

    private fun relPath(project: Project, absPath: String): String {
        val base = project.basePath ?: return absPath
        return Paths.get(base).relativize(Paths.get(absPath)).toString().replace('\\', '/')
    }

    // ------------------------------------------------------------------
    // 文件写入
    // ------------------------------------------------------------------

    /** 文件不存在则创建并写入(VFS 创建+写入均包 WriteCommandAction);返回 null=已跳过 */
    private fun writeIfAbsent(project: Project, absPath: String, content: String): Pair<VirtualFile, String>? {
        val lfs = LocalFileSystem.getInstance()
        val fixed = absPath.replace('\\', '/')
        lfs.refreshAndFindFileByPath(fixed)?.let { return null }

        var result: Pair<VirtualFile, String>? = null
        WriteCommandAction.runWriteCommandAction(project, "Generate DDL Scaffolding", null, {
            val dirPath = fixed.substringBeforeLast('/')
            val dir = lfs.refreshAndFindFileByPath(dirPath) ?: return@runWriteCommandAction
            val vf = dir.createChildData(this, fixed.substringAfterLast('/'))
            FileDocumentManager.getInstance().getDocument(vf)?.setText(content)
            result = vf to fixed
        })
        return result
    }

    /** mod.rs 存在时追加 `pub mod xxx;`(已存在则跳过) */
    private fun appendModDecl(project: Project, dir: String, moduleName: String) {
        val modFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(Paths.get(dir, "mod.rs").toString().replace('\\', '/'))
            ?: return
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
