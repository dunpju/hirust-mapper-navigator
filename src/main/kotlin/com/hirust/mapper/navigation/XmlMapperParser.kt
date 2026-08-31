package com.hirust.mapper.navigation

/**
 * 纯文本解析器:从 XML mapper 文件文本提取 `<mapper namespace="...">`、
 * `<select|insert|update|delete id="...">` 语句与 `<sql id="...">` 可复用片段信息。
 *
 * 不依赖任何 IntelliJ API,可独立单元测试。
 */
data class StatementInfo(
    /** 语句标签名:select | insert | update | delete */
    val tag: String,
    /** 语句 id */
    val id: String,
    /** `<select` 起始偏移(Rust→XML 跳转落点) */
    val tagOffset: Int,
    /** id 属性值(引号内)的起始偏移 */
    val idAttrOffset: Int
)

/** `<sql id="...">` 可复用片段信息(include refid 跳转目标) */
data class SqlFragmentInfo(
    /** 片段 id */
    val id: String,
    /** `<sql` 起始偏移(include→sql 跳转落点) */
    val tagOffset: Int,
    /** id 属性值(引号内)的起始偏移 */
    val idAttrOffset: Int
)

data class MapperInfo(
    val namespace: String,
    /** `<mapper` 起始偏移(Rust→XML 跳转落点) */
    val mapperTagOffset: Int,
    val statements: List<StatementInfo>,
    /** `<sql id="...">` 可复用片段列表 */
    val sqlFragments: List<SqlFragmentInfo> = emptyList()
)

object XmlMapperParser {

    private val MAPPER_NS = Regex("""<mapper\b[^>]*?\bnamespace\s*=\s*"([^"]+)"""")
    private val STATEMENT = Regex("""<(select|insert|update|delete)\b([^>]*)>""")
    private val SQL_FRAGMENT = Regex("""<sql\b([^>]*)>""")
    private val ID_ATTR = Regex("""\bid\s*=\s*"([^"]*)"""")

    /** 语句标签集合 */
    val STATEMENT_TAGS = setOf("select", "insert", "update", "delete")

    /**
     * 解析 XML 文本;无 `<mapper namespace=...>` 时返回 null。
     * 注意:属性值中含 '>' 或注释中的语句标签无法解析(已知限制)。
     */
    fun parse(content: String): MapperInfo? {
        val nsMatch = MAPPER_NS.find(content) ?: return null
        val namespace = nsMatch.groupValues[1]

        val statements = STATEMENT.findAll(content).mapNotNull { m ->
            val attrs = m.groupValues[2]
            val idMatch = ID_ATTR.find(attrs) ?: return@mapNotNull null
            val id = idMatch.groupValues[1]
            if (id.isEmpty()) return@mapNotNull null
            // id 属性值绝对偏移 = 属性文本绝对起始 + 属性文本内相对偏移
            val attrsAbsStart = m.groups[2]!!.range.first
            val idValueOffset = attrsAbsStart + idMatch.groups[1]!!.range.first
            StatementInfo(
                tag = m.groupValues[1],
                id = id,
                tagOffset = m.range.first,
                idAttrOffset = idValueOffset
            )
        }.toList()

        val sqlFragments = SQL_FRAGMENT.findAll(content).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val idMatch = ID_ATTR.find(attrs) ?: return@mapNotNull null
            val id = idMatch.groupValues[1]
            if (id.isEmpty()) return@mapNotNull null
            val attrsAbsStart = m.groups[1]!!.range.first
            val idValueOffset = attrsAbsStart + idMatch.groups[1]!!.range.first
            SqlFragmentInfo(
                id = id,
                tagOffset = m.range.first,
                idAttrOffset = idValueOffset
            )
        }.toList()

        return MapperInfo(namespace, nsMatch.range.first, statements, sqlFragments)
    }

    /** 快捷方式:仅提取 namespace(无 namespace 返回 null) */
    fun extractNamespace(xmlContent: String): String? =
        MAPPER_NS.find(xmlContent)?.groupValues?.get(1)
}
