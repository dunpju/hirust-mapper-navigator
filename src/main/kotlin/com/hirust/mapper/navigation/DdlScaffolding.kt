package com.hirust.mapper.navigation

/**
 * DDL → Rust/MyBatis 脚手架(纯函数,可单元测试)。
 *
 * 输入:`CREATE TABLE` DDL 文本;输出:Rust struct / DAO / mapper XML 三份代码。
 * SQL 类型 → Rust 类型映射,可空列包 Option,主键列视为非空。
 */
object DdlScaffolding {

    // ------------------------------------------------------------------
    // DDL 解析
    // ------------------------------------------------------------------

    data class DdlColumn(
        val name: String,
        val sqlType: String,
        val nullable: Boolean,
        val isPk: Boolean
    )

    data class DdlTable(val name: String, val columns: List<DdlColumn>)

    private val CREATE_TABLE_HEAD = Regex(
        """(?i)create\s+table\s+(?:if\s+not\s+exists\s+)?[`]?([a-zA-Z0-9_]+)[`]?\s*\("""
    )

    /** 表级约束关键字(行首命中则跳过该行) */
    private val CONSTRAINT_KEYWORDS =
        setOf("CONSTRAINT", "PRIMARY", "UNIQUE", "KEY", "INDEX", "FOREIGN", "CHECK", "FULLTEXT", "SPATIAL")

    /** 解析 DDL 文本中的全部 CREATE TABLE */
    fun parseCreateTables(ddl: String): List<DdlTable> {
        val tables = mutableListOf<DdlTable>()
        for (m in CREATE_TABLE_HEAD.findAll(ddl)) {
            val name = m.groupValues[1]
            val open = m.range.last // '(' 位置
            val close = findMatchingParen(ddl, open)
            if (close < 0) continue
            val body = ddl.substring(open + 1, close)
            tables += parseTableBody(name, body)
        }
        return tables
    }

    private fun parseTableBody(tableName: String, body: String): DdlTable {
        val pkColumns = mutableSetOf<String>()
        val columns = mutableListOf<DdlColumn>()

        for (part in splitTopLevel(body)) {
            val seg = part.trim()
            if (seg.isEmpty()) continue
            val firstToken = Regex("""^`?([a-zA-Z0-9_]+)`?""").find(seg)?.groupValues?.get(1) ?: continue
            if (firstToken.uppercase() in CONSTRAINT_KEYWORDS) {
                // 表级主键:PRIMARY KEY (`id`, `tenant`)
                val pkRegex = Regex("""(?i)^primary\s+key\s*\(([^)]*)\)""")
                pkRegex.find(seg)?.groupValues?.get(1)?.split(',')?.forEach {
                    val col = it.trim().trim('`')
                    if (col.isNotEmpty()) pkColumns += col
                }
                continue
            }
            val rest = seg.substringAfterFirstToken()
            val typeMatch = Regex("""^([a-zA-Z]+)(\s*\(\s*\d+\s*(,\s*\d+\s*)?\))?""").find(rest.trim())
                ?: continue
            // 完整类型含括号参数(区分 TINYINT 与 TINYINT(1)=bool)
            val sqlType = typeMatch.value.uppercase()
            val afterType = rest.trim().substring(typeMatch.value.length)
            val upper = afterType.uppercase()
            val inlinePk = upper.contains("PRIMARY KEY")
            if (inlinePk) pkColumns += firstToken
            val nullable = !upper.contains("NOT NULL") && !inlinePk
            columns += DdlColumn(firstToken, sqlType, nullable, inlinePk)
        }
        return DdlTable(
            tableName,
            columns.map { it.copy(isPk = it.isPk || it.name in pkColumns) }
        )
    }

    private fun String.substringAfterFirstToken(): String {
        // 去掉首个 token(含反引号)
        val m = Regex("""^`?[a-zA-Z0-9_]+`?\s*""").find(this) ?: return this
        return this.substring(m.value.length)
    }

    /** 顶层逗号切分(忽略括号深度内的逗号) */
    internal fun splitTopLevel(body: String): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        var inQuote = false
        for (c in body) {
            when {
                c == '\'' -> {
                    inQuote = !inQuote
                    sb.append(c)
                }
                inQuote -> sb.append(c)
                c == '(' -> {
                    depth++
                    sb.append(c)
                }
                c == ')' -> {
                    depth--
                    sb.append(c)
                }
                c == ',' && depth == 0 -> {
                    parts += sb.toString()
                    sb.clear()
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) parts += sb.toString()
        return parts
    }

    /** 匹配 '(' 的 ')'(忽略引号内与嵌套) */
    private fun findMatchingParen(text: String, openIndex: Int): Int {
        var depth = 0
        var inQuote = false
        for (i in openIndex until text.length) {
            val c = text[i]
            when {
                c == '\'' -> inQuote = !inQuote
                inQuote -> {}
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    // ------------------------------------------------------------------
    // 类型映射与命名
    // ------------------------------------------------------------------

    /** Rust 关键字(字段名命中时加 r# 前缀) */
    private val RUST_KEYWORDS = setOf(
        "type", "impl", "fn", "let", "match", "mod", "move", "mut", "pub",
        "ref", "return", "self", "static", "struct", "super", "trait", "true", "unsafe", "use", "where"
    )

    fun fieldName(column: String): String =
        if (column in RUST_KEYWORDS) "r#$column" else column

    /** SQL 类型 → Rust 类型(可空再包 Option);TINYINT(1)/BIT 视为 bool */
    fun rustTypeFor(sqlType: String, nullable: Boolean): String {
        val u = sqlType.uppercase().replace(Regex("""\s+"""), "")
        val base = when {
            u == "TINYINT(1)" || u == "BIT(1)" || u == "BIT" || u == "BOOLEAN" || u == "BOOL" -> "bool"
            u.startsWith("TINYINT") -> "i8"
            u.startsWith("SMALLINT") || u == "INT2" -> "i16"
            u.startsWith("MEDIUMINT") || u == "INT" || u == "INTEGER" || u == "INT4" -> "i32"
            u.startsWith("BIGINT") || u == "INT8" -> "i64"
            u == "FLOAT" || u == "REAL" || u == "FLOAT4" -> "f32"
            u == "DOUBLE" || u == "DOUBLEPRECISION" || u == "FLOAT8" || u.startsWith("DOUBLE") -> "f64"
            u.startsWith("DECIMAL") || u.startsWith("NUMERIC") -> "f64"
            u.startsWith("BLOB") || u.startsWith("BINARY") || u.startsWith("VARBINARY") || u == "BYTEA" -> "Vec<u8>"
            else -> "String"
        }
        return if (nullable) "Option<$base>" else base
    }

    /** 表名 → PascalCase 类型名(privilege_project → PrivilegeProject) */
    fun structName(tableName: String): String =
        tableName.split('_', '-').filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }

    // ------------------------------------------------------------------
    // 代码生成
    // ------------------------------------------------------------------

    /** 生成 Rust struct 文件内容(含 serde 派生宏,可被宏生成的序列化代码引用) */
    fun structCode(table: DdlTable): String {
        val sb = StringBuilder()
        sb.append("use serde::{Deserialize, Serialize};\n\n")
        sb.append("#[derive(Debug, Clone, Serialize, Deserialize)]\n")
        sb.append("pub struct ${structName(table.name)} {\n")
        for (col in table.columns) {
            sb.append("    pub ${fieldName(col.name)}: ${rustTypeFor(col.sqlType, col.nullable)},\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    /** 生成 DAO 文件内容 */
    fun daoCode(table: DdlTable, namespace: String, modelModule: String, xmlPath: String = ""): String {
        val struct = structName(table.name)
        val stem = table.name
        // 模型导入路径包含模块名(文件名):use crate::app::models::test_user::TestUser;
        val modelImport = if (modelModule.isNotEmpty()) "use $modelModule::$stem::$struct;\n" else ""
        val header = "use std::sync::Arc;\n\n" +
                "use hirust_mapper::{dao, Result, SqlSessionFactory};\n\n" +
                modelImport
        val idCol = table.columns.firstOrNull { it.isPk } ?: table.columns.firstOrNull()
        val idName = idCol?.let { fieldName(it.name) } ?: "id"
        val idType = idCol?.let { rustTypeFor(it.sqlType, false) } ?: "u64"
        // delete 参数用 <表名>_<列名> 避免宏对参数名 `id` 的特殊处理
        val deleteIdName = idCol?.let { "${table.name}_${it.name}" } ?: "${table.name}_id"
        val xmlAttr = if (xmlPath.isNotEmpty()) ", xml = \"$xmlPath\"" else ""
        return """
            |pub struct ${struct}Dao {
            |    __hm_factory: Arc<SqlSessionFactory>,
            |}
            |
            |impl ${struct}Dao {
            |    #[allow(dead_code)]
            |    pub fn new(factory: SqlSessionFactory) -> Self {
            |        Self {
            |            __hm_factory: Arc::new(factory),
            |        }
            |    }
            |
            |    pub fn from_arc(factory: Arc<SqlSessionFactory>) -> Self {
            |        Self { __hm_factory: factory }
            |    }
            |
            |    #[allow(dead_code)]
            |    pub fn factory(&self) -> &Arc<SqlSessionFactory> {
            |        &self.__hm_factory
            |    }
            |}
            |
            |#[dao(namespace = "$namespace"$xmlAttr)]
            |impl ${struct}Dao {
            |    #[mapper_query]
            |    pub async fn get_all(&self) -> Result<Vec<$struct>> {}
            |
            |    #[mapper_query]
            |    pub async fn get_by_id(&self, $deleteIdName: $idType) -> Result<Option<$struct>> {}
            |
            |    #[mapper_query(kind = "insert")]
            |    pub async fn create(&self, p: &$struct) -> Result<i64> {}
            |
            |    #[mapper_query(kind = "update")]
            |    pub async fn update(&self, p: &$struct) -> Result<u64> {}
            |
            |    #[mapper_query(kind = "delete")]
            |    pub async fn remove(&self, $deleteIdName: $idType) -> Result<u64> {}
            |}
        """.trimMargin().let { header + "\n" + it } + "\n"
    }

    /** 生成 mapper XML 文件内容(SQL 列名用原始名;占位符用 Rust 字段名) */
    fun mapperXml(table: DdlTable, namespace: String): String {
        val struct = structName(table.name)
        val idCol = table.columns.firstOrNull { it.isPk } ?: table.columns.firstOrNull()
        val idName = idCol?.name ?: "id"
        val cols = table.columns.joinToString(", ") { "`${it.name}`" }
        val inserts = table.columns.joinToString(", ") { "#{${fieldName(it.name)}}" }
        val updates = table.columns.filter { !it.isPk }
            .joinToString(", ") { "`${it.name}` = #{${fieldName(it.name)}}" }
        val whereId = "`$idName` = #{${fieldName(idName)}}"
        // get_by_id / delete 占位符用 <表名>_<列名>(与 DAO 参数一致,避开宏对 `id` 的特殊处理)
        val deleteIdName = idCol?.let { "${table.name}_${it.name}" } ?: "${table.name}_id"
        val whereDeleteId = "`$idName` = #{$deleteIdName}"
        return """<?xml version="1.0" encoding="UTF-8"?>
            |<mapper namespace="$namespace">
            |
            |    <select id="get_all" resultType="$struct">
            |        SELECT $cols FROM ${table.name}
            |    </select>
            |
            |    <select id="get_by_id" resultType="$struct">
            |        SELECT $cols FROM ${table.name} WHERE $whereDeleteId
            |    </select>
            |
            |    <insert id="create">
            |        INSERT INTO ${table.name} ($cols)
            |        VALUES ($inserts)
            |    </insert>
            |
            |    <update id="update">
            |        UPDATE ${table.name} SET $updates
            |        WHERE $whereId
            |    </update>
            |
            |    <delete id="remove">
            |        DELETE FROM ${table.name} WHERE $whereDeleteId
            |    </delete>
            |
            |</mapper>
        """.trimMargin() + "\n"
    }
}
