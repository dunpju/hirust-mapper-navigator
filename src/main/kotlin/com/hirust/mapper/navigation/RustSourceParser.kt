package com.hirust.mapper.navigation

/**
 * 纯文本解析器:从 Rust 源码文本提取 `#[dao(namespace = "...")]` 块与
 * `#[mapper_*(id = "...")] fn` 方法信息。
 *
 * 不依赖任何 IntelliJ API,可独立单元测试。
 *
 * 映射规则(经确认):
 *   - 语句 id:宏 id 参数优先,缺省用函数名
 *   - 语句类型:mapper_insert→insert / mapper_update→update / mapper_delete→delete,
 *              其余(mapper_select / mapper_query / 未知 mapper_*)→select
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
    val fnOffset: Int
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
    /** impl/struct 的类型名,找不到时为空串 */
    val implName: String,
    val methods: List<MethodInfo>
)

object RustSourceParser {

    private val NS_PARAM = Regex("""\bnamespace\s*=\s*"([^"]*)"""")
    private val ID_PARAM = Regex("""\bid\s*=\s*"([^"]*)"""")
    private val IMPL_KEYWORD = Regex("""\b(impl|struct)\b""")
    private val WORD_CHARS: (Char) -> Boolean = { it.isLetterOrDigit() || it == '_' }

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
                    current = Builder(ns, nsAbsOffset, attr.start, attr.nameStart, attr.end)
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
                    current.methods += MethodInfo(
                        id = id,
                        idLiteralOffset = idLiteral,
                        fnName = fn.name,
                        stmtTag = stmtTagFor(attr.name),
                        macroName = attr.name,
                        macroOffset = attr.start,
                        macroNameOffset = attr.nameStart,
                        fnOffset = fn.nameOffset
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
                implOffset = impl?.first ?: b.attrStart,
                implName = impl?.second ?: "",
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

    data class FnRef(val name: String, val nameOffset: Int)

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
                        return FnRef(content.substring(nameStart, k), nameStart)
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

    /** 在 [from, limit) 中找第一个 impl/struct 关键字及其类型名 */
    private fun findImplName(content: String, from: Int, limit: Int): Pair<Int, String>? {
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
        if (p == nameStart) return (from + m.range.first) to m.groupValues[1]
        return (from + m.range.first) to content.substring(nameStart, p)
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
        val attrEnd: Int
    ) {
        val methods = mutableListOf<MethodInfo>()
    }
}
