package com.hirust.mapper.navigation

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * JPA 提示式语句生成(方法 → XML 语句骨架,v1.4.0)。
 *
 * 光标置于 #[mapper_*] 方法(宏行至 fn 名均可)触发:
 * - 语句已存在 → 提示并跳转
 * - 不存在 → 按宏(语句标签)+ fn 名(count → COUNT(*))+ fn 参数(WHERE 条件)
 *   生成骨架,参数类型为已索引 struct 时用其字段生成 INSERT 列/UPDATE SET,
 *   表名取自现有语句的 FROM/INTO 频率(缺省从 namespace 末段推导),
 *   插入到 `</mapper>` 之前并跳转。
 *
 * 注册:标准 action 系统(GenerateGroup / EditorPopupMenu / Alt+Shift+G)——
 * 动作通道无语言限定,规避本环境语言扩展点失效问题(见 PLAN.md)。
 */
class GenerateXmlStatementAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = vFile != null && vFile.extension == "rs"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (vFile.extension != "rs") return
        generateAt(project, vFile, editor.caretModel.offset)
    }

    // ------------------------------------------------------------------
    // 生成入口(菜单动作与 Alt+Shift+Click 鼠标通道共用)
    // ------------------------------------------------------------------

    /**
     * 在 .rs 指定偏移处执行语句生成(#[mapper_*] 方法命中窗口内均可)。
     * 返回是否实际执行了操作(未命中方法返回 false)。
     */
    private fun generateAt(project: Project, vFile: VirtualFile, offset: Int): Boolean {
        if (vFile.extension != "rs") return false
        val loc = RustDaoIndex.getInstance(project).findMethodAt(vFile, offset) ?: run {
            notify(project, "Alt+Shift+Click 的位置不在 #[mapper_*] 方法上", NotificationType.WARNING)
            return false
        }

        // 已存在 → 跳转
        val existing = XmlNamespaceIndex.getInstance(project)
            .findStatement(loc.dao.namespace, loc.method.id, loc.method.stmtTag)
        if (existing != null) {
            notify(project, "语句已存在:${loc.method.id},已跳转", NotificationType.INFORMATION)
            OpenFileDescriptor(
                project, existing.file,
                existing.statement.idAttrOffset.takeIf { it >= 0 } ?: existing.statement.tagOffset
            ).navigate(true)
            return true
        }

        val xmlFile = XmlNamespaceIndex.getInstance(project).findXmlFile(loc.dao.namespace) ?: run {
            notify(project, "未找到 namespace 对应的 mapper XML:${loc.dao.namespace}", NotificationType.WARNING)
            return false
        }

        val m = loc.method
        val table = resolveTableName(project, xmlFile, loc.dao.namespace)
        val structFields = resolveStructFields(project, m.params)
        val sql = SqlSkeletonBuilder.build(m.stmtTag, m.fnName, m.params, structFields, table)
        insertAndNavigate(project, xmlFile, m.stmtTag, m.id, sql)
        return true
    }

    companion object {
        /** Alt+Shift+Click 鼠标通道入口:复用动作实例的生成逻辑 */
        fun generateAt(project: Project, vFile: VirtualFile, offset: Int): Boolean =
            GenerateXmlStatementAction().generateAt(project, vFile, offset)
    }

    // ------------------------------------------------------------------
    // 骨架生成要素
    // ------------------------------------------------------------------

    /** 表名:现有语句 FROM/INTO 最高频者;缺省取 namespace 末段(去 _dao 等后缀) */
    private fun resolveTableName(project: Project, xmlFile: VirtualFile, namespace: String): String {
        val content = readXmlContent(project, xmlFile)
        if (content != null) {
            val counts = HashMap<String, Int>()
            for (re in listOf(Regex("""\bFROM\s+([a-zA-Z_][a-zA-Z0-9_]*)"""),
                Regex("""\bINTO\s+([a-zA-Z_][a-zA-Z0-9_]*)"""))) {
                for (match in re.findAll(content)) {
                    counts[match.groupValues[1]] = (counts[match.groupValues[1]] ?: 0) + 1
                }
            }
            counts.entries.maxByOrNull { it.value }?.key?.let { return it }
        }
        val last = namespace.substringAfterLast("::")
        return listOf("_dao", "_service", "_repo", "_repository", "_mapper", "_accessor")
            .firstOrNull { last.endsWith(it) }?.let { last.removeSuffix(it) } ?: last
    }

    /** 参数类型解析出 struct 字段(首个命中的 struct;&T / Option<T> 解包) */
    private fun resolveStructFields(project: Project, params: List<FnParam>): List<StructField>? {
        for (p in params) {
            var t = p.typeText.trim()
            while (t.startsWith("&")) t = t.removePrefix("&").trim()
            t = t.removePrefix("mut").trim()
            if (t.startsWith("Option<") && t.endsWith(">")) t = t.substring(7, t.length - 1).trim()
            if (!t[0].isUpperCase()) continue
            val type = RustDaoIndex.getInstance(project).findType(t) ?: continue
            if (type.type.fields.isNotEmpty()) return type.type.fields
        }
        return null
    }

    /** 读取 XML 内容:优先未保存的打开文档,其次磁盘 */
    private fun readXmlContent(project: Project, xmlFile: VirtualFile): String? {
        val fdm = FileDocumentManager.getInstance()
        val doc = fdm.getDocument(xmlFile)
        if (doc != null && fdm.isFileModified(xmlFile)) return doc.text
        return ApplicationManager.getApplication().runReadAction<String?> {
            NavigationUtil.loadTextDocumentAligned(xmlFile)
        }
    }

    // ------------------------------------------------------------------
    // 插入与跳转
    // ------------------------------------------------------------------

    private fun insertAndNavigate(
        project: Project,
        xmlFile: VirtualFile,
        tag: String,
        id: String,
        sql: String
    ) {
        val doc: Document = FileDocumentManager.getInstance().getDocument(xmlFile) ?: run {
            notify(project, "无法打开 XML 文档:${xmlFile.name}", NotificationType.WARNING)
            return
        }
        val indent = "    "
        val sqlIndent = "$indent$indent"
        val body = "$indent<$tag id=\"$id\">\n$sqlIndent${sql.replace("\n", "\n$sqlIndent")}\n$indent</$tag>"

        WriteCommandAction.runWriteCommandAction(project, "Generate XML Statement", null, {
            val text = doc.text
            val closeIdx = text.lastIndexOf("</mapper>")
            if (closeIdx >= 0) {
                // 前置一个换行:与上一语句之间恰好一个空行;`</mapper>` 独占一行
                doc.insertString(closeIdx, "\n$body\n")
            } else {
                doc.insertString(doc.textLength, "\n$body\n")
            }
        })

        // 跳转到新语句(id 属性值处)
        val newText = doc.text
        val stmtIdx = Regex("""<$tag id="$id"""").find(newText)?.range?.first ?: 0
        val idIdx = newText.indexOf("\"$id\"", stmtIdx) + 1
        OpenFileDescriptor(project, xmlFile, idIdx).navigate(true)
        // 气泡内容按 HTML 渲染,尖括号需转义(否则 <select> 会被渲染成下拉框)
        notify(
            project,
            "已生成语句 &lt;$tag id=\"$id\"&gt;",
            NotificationType.INFORMATION
        )
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Hirust Mapper Navigator")
            .createNotification(message, type)
            .notify(project)
    }
}

/**
 * SQL 骨架生成(纯函数,可单元测试)。
 *
 * 规则:
 * - select:fn 名含 count → COUNT(*);WHERE = 参数 AND 串联
 * - delete:WHERE = 参数 AND 串联
 * - insert:有 struct 字段 → 列/值一一对应;否则 TODO 占位
 * - update:SET = struct 字段(剔除 id)或参数;WHERE 优先 id 参数
 */
object SqlSkeletonBuilder {

    fun build(
        stmtTag: String,
        fnName: String,
        params: List<FnParam>,
        structFields: List<StructField>?,
        table: String
    ): String {
        val where = params.joinToString(" AND ") { "${it.name} = #{${it.name}}" }
        return when (stmtTag) {
            "select" -> {
                val cols = if (fnName.startsWith("count") || fnName.contains("_count")) "COUNT(*)" else "*"
                if (where.isEmpty()) "SELECT $cols FROM $table"
                else "SELECT $cols FROM $table WHERE $where"
            }
            "delete" ->
                if (where.isEmpty()) "DELETE FROM $table"
                else "DELETE FROM $table WHERE $where"
            "insert" -> {
                val fields = structFields
                if (fields != null && fields.isNotEmpty()) {
                    val cols = fields.joinToString(", ") { it.name }
                    val vals = fields.joinToString(", ") { "#{${it.name}}" }
                    "INSERT INTO $table ($cols)\nVALUES ($vals)"
                } else {
                    "INSERT INTO $table\n-- TODO: 补充列与 VALUES"
                }
            }
            "update" -> {
                val setPart = when {
                    !structFields.isNullOrEmpty() ->
                        structFields.filter { it.name != "id" }
                            .joinToString(", ") { "${it.name} = #{${it.name}}" }
                    params.isNotEmpty() ->
                        params.joinToString(", ") { "${it.name} = #{${it.name}}" }
                    else -> "SET -- TODO"
                }
                val wherePart = if (params.any { it.name == "id" }) "id = #{id}" else "-- TODO: WHERE"
                "UPDATE $table SET $setPart WHERE $wherePart"
            }
            else -> "SELECT * FROM $table"
        }
    }
}
