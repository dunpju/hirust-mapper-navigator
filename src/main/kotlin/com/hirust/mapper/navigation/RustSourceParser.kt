package com.hirust.mapper.navigation

/**
 * 纯文本解析器:从 Rust 源码文本提取 `#[dao(namespace = "...")]` 块与
 * `#[mapper_*(id = "...")] fn` 方法信息。
 *
 * 不依赖任何 IntelliJ API,可独立单元测试。
 *
 * 映射规则(经确认):
 *   - 语句 id:宏 id 参数优先,缺省用函数名
 *   - 语句类型:宏 kind 参数优先(kind = "insert" 等,白名单内),
 *              缺省按宏名映射:mapper_insert→insert / mapper_update→update /
 *              mapper_delete→delete,其余(mapper_select / mapper_query / 未知 mapper_*)→select
 *   - xml 属性:#[dao(namespace = "...", xml = "...")] 中的 xml 相对路径,
 *              供索引层做逐 DAO 的精确文件定位(见 [DaoInfo.xmlAttr])
 */
data class MethodInfo(
    /** 语句 id(宏 id 参数,缺省为函数名) */
    val id: String,
    /** id 字面量在源码中的偏移(引号内第一个字符),缺省为函数名时为 -1 */
    val idLiteralOffset: Int,
    val fnName: String,
    /** XML 语句标签名:select | insert | update | delete */
    val stmtTag: String,
    /** 宏名称原文(如 mapper_query),用于宏名引用解析 */
    val macroName: String,
    /** `#[mapper_xxx` 整个属性的起始偏移(行标记锚点) */
    val macroOffset: Int,
    /** 宏名称标识符的偏移(纯文本模式宏名引用的区间起点) */
    val macroNameOffset: Int,
    /** fn 名字标识符偏移(XML→Rust 跳转落点) */
    val fnOffset: Int,
    /** fn 参数列表(生成 XML 语句骨架的 WHERE/列来源;self 已剔除) */
    val params: List<FnParam> = emptyList(),
    /** fn 签名结束(参数闭括号)偏移;-1 表示未捕获。生成动作的光标命中窗口延伸到此 */
    val sigEndOffset: Int = -1
)

/** fn 参数(name: Type 形态,self 已剔除) */
data class FnParam(
    val name: String,
    /** 类型原文(如 u64、Option<String>、&PrivilegeProject) */
    val typeText: String
)

/** Rust struct 类型定义信息(XML resultType → Rust 跳转目标) */
data class RustTypeInfo(
    /** 类型名 */
    val name: String,
    /** struct 名称标识符偏移(resultType 跳转落点) */
    val nameOffset: Int,
    /** struct 字段(普通结构体;元组/单元结构体为空)—— 生成 INSERT 列来源 */
    val fields: List<StructField> = emptyList()
)

/** struct 字段(name: Type 形态) */
data class StructField(
    val name: String,
    val typeText: String
)

data class DaoInfo(
    val namespace: String,
    /** namespace 字面量偏移(引号内第一个字符),用于纯文本模式的引用区间 */
    val nsLiteralOffset: Int,
    /** `#[dao` 整个属性的起始偏移(XML→Rust 跳转落点) */
    val attrOffset: Int,
    /** 宏名称 `dao` 标识符偏移 */
    val attrNameOffset: Int,
    /** impl/struct 关键字偏移,找不到时等于 attrOffset */
    val implOffset: Int,
    /** impl/struct 的类型名,找不到时为空串;名字缺失时回退为关键字本身 */
    val implName: String,
    /** impl/struct 类型名标识符偏移(词级点击锚点);名字缺失时为 -1 */
    val implNameOffset: Int,
    /** `#[dao(xml = "...")]` 属性值(相对 crate 根的路径),缺省为空串 */
    val xmlAttr: String,
    /** xml 属性值(引号内第一个字符)偏移;缺省 -1 */
    val xmlAttrOffset: Int,
    val methods: List<MethodInfo>
)

object RustSourceParser {

    private val NS_PARAM = Regex("""\bnamespace\s*=\s*"([^"]*)"""")
    private val ID_PARAM = Regex("""\bid\s*=\s*"([^"]*)"""")
    private val KIND_PARAM = Regex("""\bkind\s*=\s*"([^"]*)"""")
    private val XML_PARAM = Regex("""\bxml\s*=\s*"([^"]*)"""")
    private val IMPL_KEYWORD = Regex("""\b(impl|struct)\b""")
    /** struct 定义(含 pub/derive 修饰的普通/元组/单元结构体,名字取标识符) */
    private val STRUCT_DEF = Regex("""\bstruct\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val WORD_CHARS: (Char) -> Boolean = { it.isLetterOrDigit() || it == '_' }

    /** kind 参数合法值(与 XML 语句标签一致) */
    private val KIND_TAGS = setOf("select", "insert", "update", "delete")

    /** 函数修饰关键字(mapper 属性与 fn 之间允许出现) */
    private val FN_MODIFIERS = setOf("pub", "async", "unsafe", "const", "extern", "default", "static")

    /** 无方法时,向后搜索 impl/struct 的最大距离 */
    private const val IMPL_SEARCH_LIMIT = 2000

    fun stmtTagFor(macroName: String): String = when (macroName) {
        "mapper_insert" -> "insert"
        "mapper_update" -> "update"
        "mapper_delete" -> "delete"
        else -> "select"
    }

    fun isDaoAttr(name: String): Boolean = name == "dao"

    fun isMapperMethodAttr(name: String): Boolean = name.startsWith("mapper_")

    /**
     * 扫描全部 struct 类型定义(供 XML resultType → Rust 跳转)。
     * 不限 #[dao] 文件 —— resultType 对应的模型类型通常定义在 models 等普通模块中。
     */
    fun parseStructTypes(content: String): List<RustTypeInfo> =
        STRUCT_DEF.findAll(content).map { m ->
            RustTypeInfo(
                m.groupValues[1],
                m.groups[1]!!.range.first,
                parseStructBodyFields(content, m.range.last + 1)
            )
        }.toList()

    /** struct 字段行:`pub name: Type,`(pub 可带可见性限定) */
    private val STRUCT_FIELD_LINE =
        Regex("""^(?:pub(?:\s*\([^)]*\))?\s+)?([a-z_][a-zA-Z0-9_]*)\s*:\s*(.+?)[,;]?\s*$""")

    /** 解析 struct 体 {...} 内的字段(元组/单元结构体返回空) */
    private fun parseStructBodyFields(content: String, from: Int): List<StructField> {
        val n = content.length
        var p = from
        while (p < n && content[p].isWhitespace()) p++
        if (p < n && content[p] == '<') {
            val gc = findGenericClose(content, p)
            if (gc > 0) p = gc + 1
            while (p < n && content[p].isWhitespace()) p++
        }
        if (p >= n || content[p] != '{') return emptyList()
        val close = findMatchingBrace(content, p)
        if (close < 0) return emptyList()
        return content.substring(p + 1, close).lineSequence().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) return@mapNotNull null
            val fm = STRUCT_FIELD_LINE.find(t) ?: return@mapNotNull null
            StructField(fm.groupValues[1], fm.groupValues[2].trim())
        }.toList()
    }

    /** 匹配 '{' 的 '}'(跳过字符串字面量与嵌套大括号) */
    private fun findMatchingBrace(content: String, openIndex: Int): Int {
        val n = content.length
        var depth = 0
        var i = openIndex
        while (i < n) {
            when (content[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
                '"' -> i = skipString(content, i)
            }
            i++
        }
        return -1
    }

    /**
     * 解析 fn 参数列表文本为 name/type 对。
     * 剔除 self(含 &self / &mut self / mut self);无类型的极简参数被忽略。
     */
    fun parseFnParams(paramsText: String): List<FnParam> {
        if (paramsText.isBlank()) return emptyList()
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        for (c in paramsText) {
            when (c) {
                '(', '[', '<' -> {
                    depth++
                    sb.append(c)
                }
                ')', ']', '>' -> {
                    depth--
                    sb.append(c)
                }
                ',' -> if (depth == 0) {
                    parts += sb.toString()
                    sb.clear()
                } else {
                    sb.append(c)
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) parts += sb.toString()

        return parts.mapNotNull { part ->
            val seg = part.trim()
            if (seg.isEmpty()) return@mapNotNull null
            val m = FN_PARAM_LINE.find(seg) ?: return@mapNotNull null
            val name = m.groupValues[1]
            if (name == "self") return@mapNotNull null
            FnParam(name, m.groupValues[2].trim())
        }
    }

    /** 参数形态:可带 & / &mut / mut 前缀的 `name: Type` */
    private val FN_PARAM_LINE =
        Regex("""^(?:mut\s+|&(?:\s*mut)?\s*)*([a-z_][a-zA-Z0-9_]*)\s*:\s*(.+)$""", RegexOption.DOT_MATCHES_ALL)

    /**
     * 解析源码,返回所有带 namespace 的 DAO 块。
     * mapper_* 方法归属其前方最近的 #[dao] 块;无 #[dao] 前导的方法被忽略。
     */
    fun parse(content: String): List<DaoInfo> {
        val attrs = scanAttributes(content)
        val builders = mutableListOf<Builder>()
        var current: Builder? = null

        for (attr in attrs) {
            when {
                isDaoAttr(attr.name) -> {
                    current = null
                    val nsMatch = attr.params?.let { NS_PARAM.find(it) }
                    val ns = nsMatch?.groupValues?.get(1)
                    if (ns.isNullOrEmpty()) continue
                    // namespace 字面量绝对偏移 = 参数文本起始 + 参数内相对偏移
                    val nsAbsOffset = attr.paramsStart + nsMatch.groups[1]!!.range.first
                    // xml 属性(可选):逐 DAO 的精确文件定位通道
                    val xmlMatch = attr.params?.let { XML_PARAM.find(it) }
                    val xmlAttr = xmlMatch?.groupValues?.get(1) ?: ""
                    val xmlAbsOffset = if (xmlAttr.isNotEmpty() && xmlMatch != null) {
                        attr.paramsStart + xmlMatch.groups[1]!!.range.first
                    } else -1
                    current = Builder(ns, nsAbsOffset, attr.start, attr.nameStart, attr.end, xmlAttr, xmlAbsOffset)
                    builders += current
                }
                isMapperMethodAttr(attr.name) && current != null -> {
                    val fn = findFnAfter(content, attr.end) ?: continue
                    val idMatch = attr.params?.let { ID_PARAM.find(it) }
                    val idFromParam = idMatch?.groupValues?.get(1)
                    val (id, idLiteral) = if (!idFromParam.isNullOrEmpty()) {
                        idFromParam to (attr.paramsStart + idMatch.groups[1]!!.range.first)
                    } else {
                        fn.name to -1
                    }
                    // 语句类型:kind 参数优先(白名单内),缺省按宏名映射
                    val kindFromParam = attr.params?.let { KIND_PARAM.find(it) }?.groupValues?.get(1)
                    val stmtTag = kindFromParam?.takeIf { it in KIND_TAGS } ?: stmtTagFor(attr.name)
                    current.methods += MethodInfo(
                        id = id,
                        idLiteralOffset = idLiteral,
                        fnName = fn.name,
                        stmtTag = stmtTag,
                        macroName = attr.name,
                        macroOffset = attr.start,
                        macroNameOffset = attr.nameStart,
                        fnOffset = fn.nameOffset,
                        params = parseFnParams(fn.paramsText),
                        sigEndOffset = fn.parenClose
                    )
                }
            }
        }

        return builders.map { b ->
            // impl 位于 #[dao] 结束与第一个方法宏之间;无方法时限制搜索距离
            val windowEnd = b.methods.firstOrNull()?.macroOffset
                ?: minOf(content.length, b.attrEnd + IMPL_SEARCH_LIMIT)
            val impl = findImplName(content, b.attrEnd, windowEnd)
            DaoInfo(
                namespace = b.namespace,
                nsLiteralOffset = b.nsLiteralOffset,
                attrOffset = b.attrStart,
                attrNameOffset = b.attrNameOffset,
                implOffset = impl?.implOffset ?: b.attrStart,
                implName = impl?.name ?: "",
                implNameOffset = impl?.nameOffset ?: -1,
                xmlAttr = b.xmlAttr,
                xmlAttrOffset = b.xmlAttrOffset,
                methods = b.methods.toList()
            )
        }
    }

    // ------------------------------------------------------------------
    // 属性扫描
    // ------------------------------------------------------------------

    /** 解析出的属性信息 */
    data class AttrInfo(
        val name: String,
        /** 括号内参数文本(无括号时为 null) */
        val params: String?,
        /** 参数文本在源文件中的起始偏移(指向 '(' 后第一个字符),无括号时为 -1 */
        val paramsStart: Int,
        /** `#[` 起始偏移 */
        val start: Int,
        /** `]` 之后一个字符的偏移(属性结束) */
        val end: Int,
        /** 宏名标识符起始偏移 */
        val nameStart: Int
    )

    /** 扫描全部 #[name(params)] 外部属性 */
    private fun scanAttributes(content: String): List<AttrInfo> {
        val result = mutableListOf<AttrInfo>()
        var i = 0
        while (true) {
            val hash = content.indexOf("#[", i)
            if (hash < 0) break
            val attr = parseAttrAt(content, hash)
            if (attr != null) {
                result += attr
                i = attr.end
            } else {
                i = hash + 2
            }
        }
        return result
    }

    /**
     * 解析从 hash 偏移开始的属性,失败返回 null。
     * 跳过内部属性(感叹号形式),支持带路径的属性(tracing::instrument)、嵌套括号、字符串字面量。
     */
    private fun parseAttrAt(content: String, hash: Int): AttrInfo? {
        val n = content.length
        var p = hash + 2
        if (p < n && content[p] == '!') return null // 内部属性 #![...]
        val nameStart = p
        while (p < n && WORD_CHARS(content[p])) p++
        if (p == nameStart) return null
        // 支持路径形式 a::b::c
        while (p + 1 < n && content[p] == ':' && content[p + 1] == ':') {
            p += 2
            val s2 = p
            while (p < n && WORD_CHARS(content[p])) p++
            if (p == s2) break
        }
        val name = content.substring(nameStart, p)

        var q = p
        while (q < n && content[q].isWhitespace()) q++
        var params: String? = null
        var paramsStart = -1
        if (q < n && content[q] == '(') {
            val close = findBalancedClose(content, q)
            if (close < 0) return null
            params = content.substring(q + 1, close)
            paramsStart = q + 1
            q = close + 1
        }
        while (q < n && content[q].isWhitespace()) q++
        if (q >= n || content[q] != ']') return null
        return AttrInfo(name, params, paramsStart, hash, q + 1, nameStart)
    }

    /** 从 openParenIndex 的 '(' 开始,找匹配的 ')'(跳过字符串与嵌套括号) */
    private fun findBalancedClose(content: String, openParenIndex: Int): Int {
        val n = content.length
        var depth = 0
        var i = openParenIndex
        while (i < n) {
            when (content[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
                '"' -> i = skipString(content, i)
            }
            i++
        }
        return -1
    }

    /** 跳过 i 处开始的字符串字面量,返回最后字符(闭引号)的索引 */
    private fun skipString(content: String, i: Int): Int {
        val n = content.length
        var j = i + 1
        while (j < n) {
            when (content[j]) {
                '\\' -> j++
                '"' -> return j
            }
            j++
        }
        return n - 1
    }

    // ------------------------------------------------------------------
    // fn 查找
    // ------------------------------------------------------------------

    data class FnRef(
        val name: String,
        val nameOffset: Int,
        val paramsText: String = "",
        /** 参数闭括号偏移(含),未捕获为 -1 */
        val parenClose: Int = -1
    )

    /**
     * 在 from 之后查找属性所附着的目标 fn:
     * 跳过空白、注释、其他属性与修饰关键字(pub、pub(crate)、async、unsafe、const、extern、default),
     * 直到遇到 `fn NAME`。遇到其他 token(如 struct、impl、大括号)则放弃。
     */
    private fun findFnAfter(content: String, from: Int): FnRef? {
        val n = content.length
        var i = from
        while (i < n) {
            val c = content[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && i + 1 < n && content[i + 1] == '/' ->
                    while (i < n && content[i] != '\n') i++
                c == '/' && i + 1 < n && content[i + 1] == '*' -> {
                    val e = content.indexOf("*/", i + 2)
                    i = if (e < 0) n else e + 2
                }
                c == '#' && i + 1 < n && content[i + 1] == '[' -> {
                    val attr = parseAttrAt(content, i)
                    if (attr == null) return null
                    i = attr.end
                }
                else -> {
                    val wordStart = i
                    var j = i
                    while (j < n && WORD_CHARS(content[j])) j++
                    if (j == wordStart) return null
                    val word = content.substring(wordStart, j)
                    if (word == "fn") {
                        var k = j
                        while (k < n && content[k].isWhitespace()) k++
                        val nameStart = k
                        while (k < n && WORD_CHARS(content[k])) k++
                        if (k == nameStart) return null
                        // 捕获参数列表:(跳过泛型 <T: ...> 后的平衡括号内容)
                        var p = k
                        while (p < n && content[p].isWhitespace()) p++
                        if (p < n && content[p] == '<') {
                            val gc = findGenericClose(content, p)
                            if (gc > 0) {
                                p = gc + 1
                                while (p < n && content[p].isWhitespace()) p++
                            }
                        }
                        var paramsText = ""
                        var parenClose = -1
                        if (p < n && content[p] == '(') {
                            val close = findBalancedClose(content, p)
                            if (close > 0) {
                                paramsText = content.substring(p + 1, close)
                                parenClose = close
                            }
                        }
                        return FnRef(content.substring(nameStart, k), nameStart, paramsText, parenClose)
                    }
                    if (word !in FN_MODIFIERS) return null
                    i = j
                    // pub(crate) / pub(in crate::x)
                    var q = i
                    while (q < n && content[q].isWhitespace()) q++
                    if (q < n && content[q] == '(' && word == "pub") {
                        val close = findBalancedClose(content, q)
                        if (close < 0) return null
                        i = close + 1
                    } else if (q < n && content[q] == '"' && word == "extern") {
                        i = skipString(content, q) + 1
                    }
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // impl 定位
    // ------------------------------------------------------------------

    /** impl/struct 定位结果:关键字偏移 + 类型名偏移 + 类型名 */
    data class ImplRef(val implOffset: Int, val nameOffset: Int, val name: String)

    /** 在 [from, limit) 中找第一个 impl/struct 关键字及其类型名;名字缺失时 nameOffset = -1 */
    private fun findImplName(content: String, from: Int, limit: Int): ImplRef? {
        val region = content.substring(from, limit.coerceAtMost(content.length))
        val m = IMPL_KEYWORD.find(region) ?: return null
        var p = from + m.range.last + 1
        val n = content.length
        while (p < n && content[p].isWhitespace()) p++
        // 跳过泛型 <T> / <'a>
        if (p < n && content[p] == '<') {
            val close = findGenericClose(content, p)
            if (close > 0) {
                p = close + 1
                while (p < n && content[p].isWhitespace()) p++
            }
        }
        val nameStart = p
        while (p < n && WORD_CHARS(content[p])) p++
        if (p == nameStart) return ImplRef(from + m.range.first, -1, m.groupValues[1])
        return ImplRef(from + m.range.first, nameStart, content.substring(nameStart, p))
    }

    /** 匹配 '<' 的 '>'(粗略跳过字符串) */
    private fun findGenericClose(content: String, openIndex: Int): Int {
        val n = content.length
        var depth = 0
        var i = openIndex
        while (i < n) {
            when (content[i]) {
                '<' -> depth++
                '>' -> {
                    depth--
                    if (depth == 0) return i
                }
                '"' -> i = skipString(content, i)
                ';' -> return -1
            }
            i++
        }
        return -1
    }

    /** 建造中的 DAO 块 */
    private class Builder(
        val namespace: String,
        val nsLiteralOffset: Int,
        val attrStart: Int,
        val attrNameOffset: Int,
        /** #[dao(...)] 属性结束(`]` 之后)偏移 */
        val attrEnd: Int,
        val xmlAttr: String,
        val xmlAttrOffset: Int
    ) {
        val methods = mutableListOf<MethodInfo>()
    }
}
