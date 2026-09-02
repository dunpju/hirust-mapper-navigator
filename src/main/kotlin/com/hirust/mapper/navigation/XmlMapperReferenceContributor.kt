package com.hirust.mapper.navigation

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext

/**
 * XML 侧引用贡献者(language=XML,该通道已真机验证生效):
 * - `<mapper namespace="...">` 的 namespace 属性值 → Rust DAO(#[dao] 属性处)
 * - `<select|insert|update|delete id="...">` 的 id 属性值 → Rust 方法(fn 名处)
 * - `<include refid="...">` 的 refid 属性值 → `<sql id>` 片段定义(v1.2.4)
 *
 * Ctrl+Click 与 Go to Declaration 均由此生效;引用可解析时 Ctrl+悬停
 * 由平台渲染原生超链接样式(下划线 + 手型光标)。
 */
class XmlMapperReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(XmlAttributeValue::class.java),
            XmlMapperReferenceProvider()
        )
    }
}

class XmlMapperReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val attrValue = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
        val attr = attrValue.parent as? XmlAttribute ?: return PsiReference.EMPTY_ARRAY
        val tag = attr.parent as? XmlTag ?: return PsiReference.EMPTY_ARRAY
        val value = attrValue.value ?: return PsiReference.EMPTY_ARRAY
        val interesting = (tag.name == "mapper" && attr.name == "namespace") ||
                (tag.name in XmlMapperParser.STATEMENT_TAGS && attr.name == "id") ||
                (tag.name in XmlMapperParser.STATEMENT_TAGS && attr.name == "resultType") ||
                (tag.name == "include" && attr.name == "refid") ||
                (tag.name == "sql" && attr.name == "id")
        if (!interesting) return PsiReference.EMPTY_ARRAY

        val ref = when {
            tag.name == "mapper" && attr.name == "namespace" && value.isNotEmpty() ->
                XmlNamespaceToDaoReference(attrValue)
            tag.name in XmlMapperParser.STATEMENT_TAGS && attr.name == "id" && value.isNotEmpty() ->
                XmlStatementIdToMethodReference(attrValue)
            tag.name in XmlMapperParser.STATEMENT_TAGS && attr.name == "resultType" && value.isNotEmpty() ->
                XmlResultTypeToRustReference(attrValue)
            tag.name == "include" && attr.name == "refid" && value.isNotEmpty() ->
                XmlIncludeRefidToSqlReference(attrValue)
            tag.name == "sql" && attr.name == "id" && value.isNotEmpty() ->
                XmlSqlIdToIncludesReference(attrValue)
            else -> null
        } ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(ref)
    }
}

/** namespace 属性值 → Rust DAO */
class XmlNamespaceToDaoReference(element: XmlAttributeValue) :
    PsiReferenceBase<XmlAttributeValue>(element) {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(XmlNamespaceToDaoReference::class.java)

    override fun resolve(): PsiElement? {
        val ns = element.value ?: return null
        val project = element.project
        val loc = RustDaoIndex.getInstance(project).findDaoByNamespace(ns) ?: return null
        return NavigationUtil.findElement(loc.file, project, loc.dao.attrOffset)
    }

    /**
     * namespace 自动补全:列出项目全部已索引 DAO 的 namespace。
     * 候选项显示 DAO 类型名(typeText)与所在文件(灰色尾注)。
     */
    override fun getVariants(): Array<LookupElement> {
        val project = element.project
        // allDaos() 不做 ensureInitialized,先经 XML 索引入口触发协调扫描确保 DAO 索引就绪
        XmlNamespaceIndex.getInstance(project).ensureInitialized()
        return RustDaoIndex.getInstance(project).allDaos()
            .distinctBy { it.dao.namespace }
            .map { loc ->
                LookupElementBuilder.create(loc.dao.namespace)
                    .withIcon(Icons.TO_RUST)
                    .withTypeText(loc.dao.implName.ifEmpty { "dao" })
                    .withTailText("  (${loc.file.name})", true)
            }
            .toTypedArray()
    }
}

/** 语句 id 属性值 → Rust 方法 */
class XmlStatementIdToMethodReference(element: XmlAttributeValue) :
    PsiReferenceBase<XmlAttributeValue>(element) {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(XmlStatementIdToMethodReference::class.java)

    override fun resolve(): PsiElement? {
        val id = element.value ?: return null
        val project = element.project
        val attr = element.parent as? XmlAttribute ?: return null
        val tag = attr.parent as? XmlTag ?: return null
        val vFile = element.containingFile.virtualFile ?: return null

        val info = XmlNamespaceIndex.getInstance(project).getMapperInfo(vFile) ?: return null
        val loc = RustDaoIndex.getInstance(project).findMethod(info.namespace, id, tag.name) ?: return null
        return NavigationUtil.findElement(loc.file, project, loc.method.fnOffset)
    }

    /**
     * 语句 id 自动补全:列出当前 mapper 的 namespace 对应 DAO 的全部方法 id。
     * - 仅建议与标签类型匹配的方法(select 标签 ↔ mapper_query/select 等宏)
     * - 排除本文件已被其他语句/片段占用的 id(当前正在编辑的 id 除外)
     * 候选项显示对应 fn 名(typeText)与语句标签(灰色尾注)。
     */
    override fun getVariants(): Array<LookupElement> {
        val project = element.project
        val vFile = element.containingFile.virtualFile ?: return emptyArray()
        val attr = element.parent as? XmlAttribute ?: return emptyArray()
        val tag = attr.parent as? XmlTag ?: return emptyArray()

        val info = XmlNamespaceIndex.getInstance(project).getMapperInfo(vFile) ?: return emptyArray()
        val daoLoc = RustDaoIndex.getInstance(project).findDaoByNamespace(info.namespace) ?: return emptyArray()

        val currentId = element.value ?: ""
        val usedIds = (info.statements.map { it.id } + info.sqlFragments.map { it.id })
            .toSet() - currentId
        return daoLoc.dao.methods
            .filter { it.stmtTag == tag.name && it.id !in usedIds }
            .map { m ->
                LookupElementBuilder.create(m.id)
                    .withIcon(Icons.TO_RUST)
                    .withTypeText("fn ${m.fnName}")
                    .withTailText("  <${m.stmtTag}>", true)
            }
            .toTypedArray()
    }
}

/**
 * `<select|insert|update|delete resultType="Xxx">` 的 resultType 属性值 →
 * Rust 中同名 `struct Xxx` 定义(v1.2.7)。
 *
 * 支持限定名(取 `::` / `.` 分隔的末段);无同名 struct 时 resolve 返回 null
 * (容错:不跳转、不下划线)。落点:struct 名称标识符。
 */
class XmlResultTypeToRustReference(element: XmlAttributeValue) :
    PsiReferenceBase<XmlAttributeValue>(element) {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(XmlResultTypeToRustReference::class.java)

    override fun resolve(): PsiElement? {
        val raw = element.value ?: return null
        if (raw.isEmpty()) return null
        val project = element.project
        val loc = RustDaoIndex.getInstance(project).findType(raw) ?: return null
        return NavigationUtil.findElement(loc.file, project, loc.type.nameOffset)
    }

    override fun getVariants(): Array<LookupElement> = emptyArray()
}

/**
 * `<include refid="...">` 的 refid 属性值 → `<sql id="...">` 片段定义(v1.2.4)。
 *
 * 查找策略见 [XmlNamespaceIndex.findSqlFragment]:当前文件优先,
 * 带命名空间前缀(`<ns>.<id>` / `<ns>::<id>`)时跨文件解析;
 * 索引未就绪/未收录时兜底直读当前文件解析(同文件场景零索引依赖);
 * 目标不存在时 resolve 返回 null(容错:不跳转、不报错)。
 *
 * 落点:`<sql id="...">` 的 id 属性值元素(XmlAttributeValue)——
 * 与 Rust→XML 语句跳转的落点形态一致,平台对其导航可靠。
 */
class XmlIncludeRefidToSqlReference(element: XmlAttributeValue) :
    PsiReferenceBase<XmlAttributeValue>(element) {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(XmlIncludeRefidToSqlReference::class.java)

    override fun resolve(): PsiElement? {
        val refid = element.value ?: return null
        if (refid.isEmpty()) return null
        val project = element.project
        val vFile = element.containingFile.virtualFile ?: return null

        // 通道1:索引查找(同文件优先 + 命名空间前缀跨文件)
        val loc = XmlNamespaceIndex.getInstance(project).findSqlFragment(refid, vFile)
        if (loc != null) {
            return sqlTargetElement(loc.file, project, loc.fragment.idAttrOffset)
        }

        // 通道2:容错兜底 —— 直接解析当前文件(索引未就绪/未收录时仍可同文件跳转)
        val frag = readCurrentFragments(vFile)?.firstOrNull { it.id == refid }
        if (frag != null) {
            return sqlTargetElement(vFile, project, frag.idAttrOffset)
        }

        log.info("[hirust-mapper-navigator] include refid unresolved: \"$refid\" in ${vFile.path}")
        return null
    }

    /** 读当前文件并解析 sql 片段(ReadAction 包裹;失败返回 null) */
    private fun readCurrentFragments(vFile: com.intellij.openapi.vfs.VirtualFile): List<SqlFragmentInfo>? =
        try {
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .runReadAction<String?> { NavigationUtil.loadTextDocumentAligned(vFile) }
                ?.let { XmlMapperParser.parse(it) }?.sqlFragments
        } catch (_: Exception) {
            null
        }

    /** 落点元素:id 属性值(XmlAttributeValue,优先)或其叶子 token */
    private fun sqlTargetElement(
        file: com.intellij.openapi.vfs.VirtualFile,
        project: com.intellij.openapi.project.Project,
        offset: Int
    ): PsiElement? {
        val leaf = NavigationUtil.findElement(file, project, offset) ?: return null
        return leaf.parent as? XmlAttributeValue ?: leaf
    }

    override fun getVariants(): Array<LookupElement> = emptyArray()
}

/**
 * `<sql id="...">` 的 id 属性值 → 引用该片段的全部 `<include refid>` 位置(v1.2.6 反向跳转)。
 *
 * 多目标引用([PsiPolyVariantReference]):仅一处引用时 Ctrl+Click 直接跳转,
 * 多处时平台弹出目标列表供选择(如 QuestionMapper 中 list_where 被 2 处 include 引用)。
 *
 * 匹配语义与 [XmlIncludeRefidToSqlReference] 对称:同文件无前缀 refid、
 * 任意文件带本 namespace 前缀的 refid;无任何引用时 multiResolve 返回空数组
 * (容错:不跳转、不下划线)。
 *
 * 落点:include 的 refid 属性值元素(XmlAttributeValue,与正向跳转落点同形态)。
 */
class XmlSqlIdToIncludesReference(element: XmlAttributeValue) :
    PsiReferenceBase<XmlAttributeValue>(element), PsiPolyVariantReference {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(XmlSqlIdToIncludesReference::class.java)

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val id = element.value ?: return ResolveResult.EMPTY_ARRAY
        if (id.isEmpty()) return ResolveResult.EMPTY_ARRAY
        val project = element.project
        val vFile = element.containingFile.virtualFile ?: return ResolveResult.EMPTY_ARRAY
        val locs = XmlNamespaceIndex.getInstance(project).findIncludesOf(id, vFile)
        return locs.mapNotNull { loc ->
            val leaf = NavigationUtil.findElement(loc.file, project, loc.include.refidAttrOffset)
                ?: return@mapNotNull null
            val target = leaf.parent as? XmlAttributeValue ?: leaf
            PsiElementResolveResult(target)
        }.toTypedArray()
    }

    override fun resolve(): PsiElement? = multiResolve(false).firstOrNull()?.element

    override fun getVariants(): Array<LookupElement> = emptyArray()
}
