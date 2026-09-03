# Hirust Mapper Navigator — MybatisX 风格功能改造计划

> 本文档是完整的实施计划,可脱离原始会话独立执行。
> 参照插件:[MybatisX](https://plugins.jetbrains.com/plugin/10119-mybatisx)(baomidou 出品,源码 https://github.com/baomidou/MybatisX)
> 计划日期:2026-08-21

---

## 一、背景与目标

### 1.1 现状

本项目是 RustRover 插件,为 [hirust-mapper](https://github.com/hirust/hirust-mapper)(MyBatis 风格 Rust ORM)提供导航支持。当前已有:

| 文件 | 作用 | 状态 |
|------|------|------|
| `MacroDefinitionReference.kt` | 宏名 → proc macro 定义源码跳转 | ✅ 正常,但**无入口注册**(贡献者被删) |
| `NamespaceToXmlReference.kt` | `#[dao(namespace="...")]` 字符串 → XML 文件 | ✅ 正常,但**无入口注册** |
| `XmlNamespaceIndex.kt` | namespace → XML 文件索引 | ✅ 正常,仅文件级 |
| `MapperPathsConfig.kt` | 从 `.with_mapper_paths()` 提取 XML glob 路径 | ✅ 正常 |
| `XmlIndexRefreshListener.kt` | XML 文件变更监听 → 刷新索引 | ✅ 正常 |
| `NamespacePathResolver.kt` | namespace → XML 文件多策略匹配 | ✅ 正常 |

**⚠️ 当前项目处于损坏状态**:
1. `plugin.xml` 引用的 `com.hirust.mapper.navigation.PluginStartup` 类在上次重构(commit `90ee9b8`)中被删除但未重建 → 运行时 ClassNotFound,插件无法加载。
2. 所有引用(宏跳转、namespace 跳转)的注册入口(PsiReferenceContributor)已被删除 → 现有功能全部失效。
3. `NamespaceToXmlReference` 中 `element::class.simpleName == "RsLitExpr"` 检查只在 RustRover(带 Rust PSI)运行时生效,构建目标是 IDEA Community(无 Rust 插件)——这是刻意的"编译不依赖 Rust 插件、运行时按类名字符串探测"模式,**本次改造沿用此模式**。

### 1.2 目标(参照 MybatisX 核心功能)

MybatisX 的核心卖点是 **"mapper 和 xml 来回跳转"**(XML 跳转 GIF)。映射到 hirust-mapper 场景,本次改造实现 **Rust DAO ↔ XML Mapper 双向跳转 + 行标记(gutter 图标)**:

**XML → Rust 方向:**
- XML `<mapper namespace="crate::app::dao::xxx_dao">` 的 namespace 值 Ctrl+Click / 行标记图标 → 跳转到 Rust 文件的 `#[dao(namespace = "...")]` 属性处
- XML `<select|insert|update|delete id="xxx">` 的 id 值 Ctrl+Click / 行标记图标 → 跳转到对应的 `#[mapper_query(id = "xxx")]` 方法

**Rust → XML 方向:**
- `#[dao(namespace = "...")]` 中 namespace 字符串 Ctrl+Click(已有,恢复)/ 行标记图标 → 打开 XML 文件并定位到 `<mapper>` 标签
- `#[mapper_query(id = "xxx")]` 中 id 字符串 Ctrl+Click / 行标记图标 → 定位到 XML `<select id="xxx">` 语句标签

**不在本次范围**(用户已确认,留作后续):
- XML 代码补全(id/namespace/refid/resultMap 补全)
- `<include refid>` → `<sql id>` 跳转
- JPA 提示式语句生成
- 数据库代码生成(需依赖 Database 插件)

---

## 二、已确认的设计决策

| 决策点 | 结论 | 说明 |
|--------|------|------|
| 功能范围 | **仅双向跳转 + 行标记** | 不含补全/生成/检查 |
| 技术路线 | **文本解析,无 Rust 编译依赖** | 沿用当前构建(IntelliJ Community 2024.2.2 编译),运行时用类名字符串探测 Rust PSI(RustRover 中生效),纯文本兜底(Community 中生效) |
| id 映射规则 | **宏 id 参数,缺省用函数名** | `#[mapper_query(id = "list")] fn list_data()` → `<select id="list">`;无 `id =` 参数时 → `<select id="list_data">` |
| 语句类型映射 | 宏名 → XML 标签名 | `mapper_select`/`mapper_query` → `<select>`;`mapper_insert` → `<insert>`;`mapper_update` → `<update>`;`mapper_delete` → `<delete>` |
| 跳转落点 | 精确到属性/标签 | XML→Rust 落在 `#[dao(...)]` / `#[mapper_xxx]` 行;Rust→XML 落在 `<mapper>` 标签 / `<select id="...">` 的 id 属性 |

---

## 三、总体架构

```
┌─────────────────────────────────────────────────────────────────┐
│  纯文本解析层(无 IntelliJ 依赖,可单元测试)                          │
│  RustSourceParser.kt   : .rs 文本 → DaoInfo/MethodInfo 模型       │
│  XmlMapperParser.kt    : XML 文本 → MapperInfo/StatementInfo 模型 │
└──────────────┬──────────────────────────────────┬───────────────┘
               │                                  │
┌──────────────▼──────────────┐   ┌───────────────▼───────────────┐
│ RustDaoIndex.kt(项目服务)    │   │ XmlNamespaceIndex.kt(项目服务,  │
│ namespace→Dao, (ns,id)→方法  │◄─►│ 扩展:文件→语句列表,(ns,id)→语句  │
│ 按 modStamp 缓存 per-file     │   │ namespace→文件(已有)+语句级索引  │
└──────┬───────────────────────┘   └───────┬───────────────────────┘
       │                                   │
┌──────▼───────────────────────────────────▼───────────────────────┐
│ 跳转层                                                             │
│ NavigationUtil.kt : VirtualFile+offset → 打开编辑器定位光标           │
│                                                                   │
│ Rust → XML:                        XML → Rust:                    │
│ • RustReferenceContributor          • XmlMapperReferenceContributor│
│   (language=RUST + TEXT 双注册)       (language=XML)               │
│   - 宏名引用(复用 MacroDefinition)   - namespace 属性值 → Rust DAO  │
│   - namespace 字符串(复用,增强落点)   - 语句 id 属性值 → Rust 方法    │
│   - id 字符串 → XML 语句(新增)                                      │
│ • RustLineMarkerProvider           • XmlMapperLineMarkerProvider  │
│   (language=RUST,类名探测)           (language=XML)               │
│   - impl 行图标 → <mapper>          - <mapper> 行图标 → Rust impl  │
│   - fn 行图标 → <select id>         - 语句行图标 → Rust fn          │
└───────────────────────────────────────────────────────────────────┘
```

### 3.1 关键技术风险与对策(实施前必读)

1. **纯文本文件的 PSI 结构**:无 Rust 插件时 `.rs` 文件是 PlainText,整个文件内容是**单个叶子 PsiElement**。
   参考:IDEA 内置的"纯文本 URL 可 Ctrl+Click"就是在该单叶元素上用 `rangeInElement` 实现的。
   → `RustReferenceContributor` 在 TEXT 模式下对单叶元素按文本偏移计算多个子范围引用(`PsiReferenceBase` + softRangeInElement)。

2. **`psi.referenceContributor` 的 `language` 属性是必填的**。
   → 同一个 Contributor 类注册三次:`language="XML"`、`language="RUST"`、`language="TEXT"`。
   Community 中无 RUST 语言 → IDEA 记录警告并跳过该 extension(**非致命**),XML/TEXT 的仍生效;RustRover 中三者全部生效。

3. **Rust PSI 类(如 `RsFunction`)编译期不可见**。
   → 运行时用 `element.javaClass.name.contains("RsImplItem")` / `contains("RsFunction")` / `contains("RsLitExpr")` 字符串探测(现有代码 `NamespaceToXmlReference.canCreateFor` 已是此模式),其余逻辑只用通用 PSI(text/offset/parent)。

4. **`codeInsight.lineMarkerProvider` 的 `language="RUST"` 在 Community 中同样会被跳过(非致命)**。
   → Community 下 Rust 侧无行标记,仅 Ctrl+Click 文本引用兜底;RustRover 下完整。这是接受的取舍。

5. **索引刷新**:XML 与 .rs 两侧都要响应文件变更。
   → 扩展现有 `XmlIndexRefreshListener`(或改名 `MapperIndexRefreshListener`),对 `.rs` 变更调用 `RustDaoIndex.refreshFile`,对 `.xml` 变更维持现有逻辑。

---

## 四、数据模型(纯文本解析层)

### 4.1 `RustSourceParser.kt`(纯函数,零 IntelliJ 依赖)

```kotlin
data class MethodInfo(
    val id: String,            // 语句 id:宏 id 参数,缺省用 fn 名
    val fnName: String,
    val stmtTag: String,       // "select" | "insert" | "update" | "delete"
    val macroOffset: Int,      // #[mapper_xxx 起始偏移(Rust→XML 行标记锚点)
    val fnOffset: Int          // fn 名字标识符偏移(XML→Rust 跳转落点)
)

data class DaoInfo(
    val namespace: String,     // "crate::app::dao::privilege_project_dao"
    val attrOffset: Int,       // #[dao( 起始偏移(XML→Rust 跳转落点)
    val implOffset: Int,       // impl 关键字偏移(行标记锚点)
    val implName: String,      // impl 的类型名,如 "PrivilegeProjectDao"
    val methods: List<MethodInfo>
)

object RustSourceParser {
    fun parse(content: String): List<DaoInfo>
}
```

**解析规则(正则 + 偏移计算,不依赖任何 PSI):**

1. **DAO 定位**:逐个匹配 `#\[dao\s*\(([^)]*)\)\s*\]`,从捕获组提取 `namespace\s*=\s*"([^"]+)"`;再向后扫描(跳过空白/注释/其他属性)找到最近的 `impl\s+([A-Za-z_][A-Za-z0-9_]*)` 或 `pub struct`,记录偏移。
2. **方法定位**:在 DAO 块内(从 `#[dao]` 到下一个 `#[dao]` 或文件尾)匹配 `#\[\s*mapper_(query|select|insert|update|delete)\s*(\(([^]]*)\))?\s*\]`,再向后找最近的 `fn\s+([A-Za-z_][A-Za-z0-9_]*)`。
   - `id` 提取:宏参数文本中 `id\s*=\s*"([^"]+)"`,无则用 fn 名。
   - `stmtTag`:`mapper_select|mapper_query`→`select`,其余同名映射。
   - 所有 `mapper_*` 前缀未知宏(如 `mapper_exec`)→ `stmtTag = "select"` 兜底。
3. **注意**:用 `Regex.findAll` 拿 `range.first` 计算偏移;`#[dao(...)]` 参数中可能含嵌套括号,匹配参数时用非贪婪 + 手动括号平衡扫描更稳(`dao(namespace = "a(b)")` 场景)。

### 4.2 `XmlMapperParser.kt`(纯函数,零 IntelliJ 依赖)

```kotlin
data class StatementInfo(
    val tag: String,           // "select"|"insert"|"update"|"delete"
    val id: String,
    val tagOffset: Int,        // <select 起始偏移(Rust→XML 跳转落点)
    val idAttrOffset: Int      // id 属性值的偏移
)

data class MapperInfo(
    val namespace: String,
    val mapperTagOffset: Int,  // <mapper 起始偏移(Rust→XML 跳转落点)
    val statements: List<StatementInfo>
)

object XmlMapperParser {
    fun parse(content: String): MapperInfo?
}
```

**解析规则:**
- namespace:`<mapper\s+[^>]*namespace\s*=\s*"([^"]+)"`(与现有 `XmlNamespaceIndex.extractNamespace` 一致,增加偏移)。
- 语句:`<(select|insert|update|delete)(\s[^>]*)?>` 中提取 `id\s*=\s*"([^"]+)"`;注意自闭合与多行属性。
- 不处理 `<sql>`/`<include>`(不在范围)。

---

## 五、索引层

### 5.1 `RustDaoIndex.kt`(新增,项目服务)

```kotlin
class RustDaoIndex(private val project: Project) {
    // VirtualFile → Pair(modStamp, List<DaoInfo>),modStamp 变化才重解析
    private val cache = ConcurrentHashMap<VirtualFile, Pair<Long, List<DaoInfo>>>()

    fun findDaoByNamespace(namespace: String): DaoInfo? + VirtualFile?
    fun findMethod(namespace: String, id: String, tag: String? = null): MethodInfo? + VirtualFile?
    // 行标记用:给定 .rs 文件内偏移,找到覆盖它的 DAO / 方法
    fun findDaoAt(vFile: VirtualFile, offset: Int): Pair<VirtualFile, DaoInfo>?
    fun findMethodAt(vFile: VirtualFile, offset: Int): Pair<VirtualFile, MethodInfo>?

    fun refreshFile(vFile: VirtualFile)   // 单文件刷新(监听器调用)
    fun ensureInitialized()               // 懒加载全量扫描
    fun rebuildIndex()
}
```

- 全量扫描:`FilenameIndex.getVirtualFilesByName(project, ".rs", GlobalSearchScope.projectScope(project))`(参照 `MapperPathsConfig.refresh` 的写法)。
- 排除明显无关文件可先文本含 `"#[dao"` 快速过滤(`content.contains("#[dao")`)以减少解析量。

### 5.2 `XmlNamespaceIndex.kt`(扩展)

- 内部改为用 `XmlMapperParser.parse` 替换现有 `extractNamespace` 正则,保留 `namespaceToFile`/`stemToFile` 行为(向后兼容 `NamespacePathResolver`)。
- 新增:
  - `statementsByFile: ConcurrentHashMap<VirtualFile, MapperInfo>`
  - `fun getMapperInfo(file: VirtualFile): MapperInfo?`
  - `fun findStatement(namespace: String, id: String, tag: String? = null): Triple<VirtualFile, StatementInfo, String /*ns*/>?`(优先 namespace 精确匹配,退化为 stem 匹配,复用 `NamespacePathResolver` 策略)

### 5.3 `XmlIndexRefreshListener.kt`(扩展)

- `isRelevantFile` 增加 `.rs` 分支(`file.extension == "rs"` → `RustDaoIndex.refreshFile`)。
- XML 分支在 `refreshFile` 时同步更新 `statementsByFile`。

---

## 六、跳转层

### 6.1 `NavigationUtil.kt`(新增)

```kotlin
object NavigationUtil {
    /** 打开文件并把光标定位到 offset(尽可能高亮命名元素) */
    fun openAt(project: Project, file: VirtualFile, offset: Int)
    // 实现:FileEditorManager.getInstance(project).openFile(file, true)
    //      → 获取 selectedTextEditor → caretModel.moveToOffset(offset)
    //      → editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    //      可选:PsiManager.findElementAt(offset) 若为 Navigatable 则 navigate(true)
}
```

### 6.2 `XmlMapperReferenceContributor.kt`(新增,language="XML")

```kotlin
class XmlMapperReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(XmlAttributeValue::class.java),
            XmlMapperReferenceProvider()
        )
    }
}
```

`XmlMapperReferenceProvider.getReferencesByElement`:
- 元素是 `<mapper>` 标签的 `namespace` 属性值(父 XmlAttribute.name == "namespace",祖父 tag.name == "mapper")→ 返回 `XmlNamespaceToDaoReference`
- 元素是 `select|insert|update|delete` 标签的 `id` 属性值 → 返回 `XmlStatementIdToMethodReference`

两个 Reference(`PsiReferenceBase<XmlAttributeValue>`):
- `resolve()`:
  - namespace → `RustDaoIndex.findDaoByNamespace` → 目标 `(VirtualFile, DaoInfo)`;找不到时退化:按 `NamespacePathResolver.extractStem` 在索引中做模糊匹配。
  - id → 取所在 XML 文件 namespace(经 `XmlNamespaceIndex`)→ `RustDaoIndex.findMethod(ns, id, tag)`。
- `getVariants()` 返回空数组(补全不在本次范围)。

### 6.3 `RustReferenceContributor.kt`(新增,language="RUST" 和 "TEXT" 注册同一个类)

恢复并增强被删除的宏属性贡献者(参照 git 历史 `570e0c`/`7e68bed` 中被删的实现 + 现存引用类):

```
getReferencesByElement(element):
  if element.javaClass.simpleName == "RsLitExpr"           // RustRover 环境
      判断上下文(向上找祖先,文本含 "namespace" / 宏参内 "id"):
        - namespace 字符串 → NamespaceToXmlReference(现有类)
        - id 字符串        → RustIdToXmlStatementReference(新)
  else if element 是纯文本单叶(.rs 无 Rust 插件时)           // Community 环境
      对整个文件文本跑 RustSourceParser + namespace/id 正则,
      为每个命中区间构造带 rangeInElement 的引用:
        - 宏名 → MacroDefinitionReference(现有类)
        - namespace 字符串内容 → NamespaceToXmlReference
        - id 字符串内容 → RustIdToXmlStatementReference
```

`RustIdToXmlStatementReference.resolve()`:
- 向上找 `#[mapper_xxx(...)]` 属性文本 → `RustSourceParser` 语义确定 id 与 stmtTag(或直接用所在文件的 `RustDaoIndex.findMethodAt` 反查)→ `XmlNamespaceIndex.findStatement(ns, id, tag)` → `(VirtualFile, StatementInfo)`。

`NamespaceToXmlReference` 增强:resolve 后跳转落点从"文件"改为 `<mapper>` 标签偏移(用 `XmlNamespaceIndex.getMapperInfo(file).mapperTagOffset`)。

### 6.4 `XmlMapperLineMarkerProvider.kt`(新增,language="XML")

```kotlin
class XmlMapperLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val tag = element.parent as? XmlTag ?: return null   // 锚点用标签名 token
        return when (tag.name) {
            "mapper" -> 若 namespace 可解析到 Rust DAO → 图标(toRust)
            "select","insert","update","delete" -> 若 id 可解析到 Rust 方法 → 图标(toRust)
            else -> null
        }
    }
    // GutterIconNavigationHandler → NavigationUtil.openAt(rustFile, dao.attrOffset / method.fnOffset)
}
```

### 6.5 `RustLineMarkerProvider.kt`(新增,language="RUST")

```kotlin
class RustLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // 类名字符串探测,编译期不依赖 Rust 插件
        val cls = element.javaClass.name
        return when {
            cls.contains("RsFunction") -> {
                // 向上找 #[mapper_*] 属性;RustDaoIndex.findMethodAt(file, offset)
                // → XmlNamespaceIndex.findStatement → 图标(toXml)
            }
            cls.contains("RsImplItem") -> {
                // 向上/向下找 #[dao(namespace=...)] 属性;findDaoAt
                // → XML 文件 → 图标(toXml)
            }
            else -> null
        }
    }
}
```

> 注意:不同 RustRover 版本 PSI 实现类名可能带 `Impl` 后缀(如 `RsFunctionImpl`),用 `contains` 而非 `equals`。若版本不匹配则静默返回 null(降级为仅 Ctrl+Click)。

### 6.6 图标 `Icons.kt` + `resources/icons/`

- `icons/to_xml.svg`(Rust→XML 方向,如鸟形箭头飞向文档)、`icons/to_rust.svg`(反向)。
- `Icons.kt`:`val TO_XML = IconLoader.getIcon("/icons/to_xml.svg", Icons::class.java)` 等。
- SVG 用 13×13 简单几何图形即可(参照 MybatisX 的小鸟风格,颜色如 `#4CAF50`/`#F7A11A`)。
- 同时为插件商店补一个 40×40 的 `pluginIcon.svg`(plugin.xml `<icon>`),当前缺失。

---

## 七、plugin.xml 最终形态

```xml
<idea-plugin>
    <id>com.hirust.mapper.navigator</id>
    <name>Hirust Mapper Navigator</name>
    <vendor .../>
    <icon>icons/pluginIcon.svg</icon>

    <depends>com.intellij.modules.platform</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- 引用贡献:XML 侧(namespace/id 属性值 Ctrl+Click) -->
        <psi.referenceContributor language="XML"
            implementation="com.hirust.mapper.navigation.XmlMapperReferenceContributor"/>

        <!-- 引用贡献:Rust 侧(RustRover 中作用于 RsLitExpr 等) -->
        <psi.referenceContributor language="RUST"
            implementation="com.hirust.mapper.navigation.RustReferenceContributor"/>

        <!-- 引用贡献:纯文本兜底(无 Rust 插件的 IDE 中 .rs 为 TEXT) -->
        <psi.referenceContributor language="TEXT"
            implementation="com.hirust.mapper.navigation.RustReferenceContributor"/>

        <!-- 行标记:XML 侧 gutter 图标 -->
        <codeInsight.lineMarkerProvider language="XML"
            implementationClass="com.hirust.mapper.navigation.XmlMapperLineMarkerProvider"/>

        <!-- 行标记:Rust 侧 gutter 图标(仅 RustRover 生效) -->
        <codeInsight.lineMarkerProvider language="RUST"
            implementationClass="com.hirust.mapper.navigation.RustLineMarkerProvider"/>

        <!-- 项目服务 -->
        <projectService serviceImplementation="com.hirust.mapper.navigation.XmlNamespaceIndex"/>
        <projectService serviceImplementation="com.hirust.mapper.navigation.MapperPathsConfig"/>
        <projectService serviceImplementation="com.hirust.mapper.navigation.RustDaoIndex"/>

        <!-- 文件变更监听(XML + .rs 双侧刷新) -->
        <fileListener implementation="com.hirust.mapper.navigation.XmlIndexRefreshListener"/>
    </extensions>
</idea-plugin>
```

> ⚠️ 删除现有的 `postStartupActivity PluginStartup` 引用(类不存在,导致插件加载失败)。
> Community 中 `language="RUST"` 的两个 extension 会被跳过并记警告,非致命;RustRover 中全部生效。

---

## 八、实施步骤(按顺序执行)

> 执行进度:2026-08-23 **全部完成 ✅**(v1.2.0,双向跳转 + 行标记 + 原生悬停交互均已真机验证)。

### 阶段 1:纯文本解析层 + 单元测试 ✅
1. [x] 新建 `src/main/kotlin/com/hirust/mapper/navigation/RustSourceParser.kt`
2. [x] 新建 `src/main/kotlin/com/hirust/mapper/navigation/XmlMapperParser.kt`
3. [x] 新建 `src/test/kotlin/com/hirust/mapper/navigation/RustSourceParserTest.kt`
   - 用例:带 id 的方法、不带 id 的方法、多个 DAO、嵌套括号 namespace、未知 mapper_* 宏、非 DAO 文件返回空
4. [x] 新建 `src/test/kotlin/com/hirust/mapper/navigation/XmlMapperParserTest.kt`
   - 用例:标准 mapper、多语句、无 namespace、自闭合语句、多行属性
5. [x] `build.gradle.kts` 增加测试依赖(采用 junit:junit:4.13.2)

### 阶段 2:索引层 ✅
6. [x] 新建 `RustDaoIndex.kt`(按 §5.1)
7. [x] 扩展 `XmlNamespaceIndex.kt`:接入 `XmlMapperParser`,新增语句级查询(按 §5.2)
8. [x] 扩展 `XmlIndexRefreshListener.kt`:.rs 变更刷新 `RustDaoIndex`(按 §5.3)

### 阶段 3:XML → Rust 方向 ✅
9. [x] 新建 `NavigationUtil.kt`(按 §6.1)
10. [x] 新建 `XmlMapperReferenceContributor.kt` + 两个 Reference 类(按 §6.2)
11. [x] 新建 `XmlMapperLineMarkerProvider.kt`(按 §6.4)
12. [x] 新建 `Icons.kt` + SVG 图标(toXml/toRust/pluginIcon,按 §6.6)

### 阶段 4:Rust → XML 方向 ✅
13. [x] 新建 `RustReferenceContributor.kt`(按 §6.3,恢复宏名/namespace 引用注册 + 新增 id 引用;额外修复了原 NamespaceToXmlReference 的 rangeInElement 绝对/相对偏移 bug)
14. [x] 增强 `NamespaceToXmlReference`(落点到 `<mapper>` 标签)
15. [x] 新建 `RustLineMarkerProvider.kt`(按 §6.5)

### 阶段 5:装配与文档
16. [x] 重写 `plugin.xml`(按 §7,删除坏的 postStartupActivity;描述与图标已更新)
17. [x] 更新 `README.md`:功能说明(双向跳转、行标记、悬停交互)、环境兼容表、结构图
18. [x] 版本号(最终发布版 **1.2.0**)
19. [x] 新建 `sample/` 手动验证样例工程(Cargo.toml、main.rs 含 with_mapper_paths、DAO、XML)
20. [x] 运行 `gradle test` 全部通过(22/22)
21. [x] 运行 `gradle buildPlugin` 产出 zip
22. [x] 修复 gradle wrapper(`.gitignore` 已加排除例外并生成 wrapper jar 纳入版本控制)

---

## 十二、真机调试踩坑记录(RustRover 2026.2,对二次开发最重要)

按发现顺序记录实际遇到的问题、症状与修复,全部经过日志/字节级验证:

| # | 问题 | 症状 | 修复 |
|---|------|------|------|
| 1 | plugin.xml 使用了不存在的 `<fileListener>` 扩展点(v1.0 遗留) | 插件完全无法加载 | 改用 `<projectListeners>` + `topic="...BulkFileListener"` |
| 2 | `FilenameIndex.getVirtualFilesByName(".rs")` 按"完整文件名"匹配 | 索引 0 个 Rust 文件 | 改用 `FileTypeIndex.getFiles(类型, scope)` + 扩展名过滤 |
| 3 | `MapperPathsConfig.refresh()` 无调用方(v1.0 由已删除的 PluginStartup 调用) | with_mapper_paths 永远为空 | 在 `XmlNamespaceIndex.rebuildIndex()` 中调用;.rs 新建时重建 |
| 4 | glob 解析把 `**` 当目录名的一部分(`resources/mapper/**` 不存在) | 索引 0 个 XML | 按路径段解析:字面段拼目录、`**` 段表递归 |
| 5 | `<depends optional="org.rust.lang">` — RustRover 2026.2 中 Rust 已并入产品本体,该插件 id 不存在(日志:`plugin org.rust.lang is not resolved`) | 可选依赖片段被整体排除 | 放弃依赖 id 方案(注意 `optional` 属性是布尔值,插件 id 放元素文本) |
| 6 | `psi.referenceContributor` / `lineMarkerProvider` 以 `language="RUST"` 或 `"ANY"` 注册均**不会被咨询**(仅 `language="XML"` 生效),原因未公开文档化 | Rust 侧引用/行标记完全失效 | 跳转改走 `gotoDeclarationHandler`(无 language 属性,全语言生效);图标与悬停下划线在编辑器打开时程序化注册(`addLineHighlighter` + `setGutterIconRenderer` + `EditorMouseMotionListener`) |
| 7 | **偏移坐标系**:解析文本与 Editor Document 不一致 —— Document 内部统一用 `\n`(磁盘可为 `\r\n`),而 `VfsUtil.loadText` 实测**不归一化** | CRLF 文件跳转落点逐行向下漂移(147 行漂到 151 行) | `NavigationUtil.loadTextDocumentAligned`:读原始字节 → 去 BOM → `\r\n`→`\n` 再解析 |
| 8 | `GotoDeclarationHandler` 会被**后台协程线程**调用(悬停渲染也会调用) | 直接 `navigate(true)` 触发 EDT 断言;悬停即跳转 | 导航包 `invokeLater` 派发到 EDT;并用 AWT 全局监听记录鼠标点击时刻,仅在点击后 350ms 窗口内自导航 |
| 9 | `EditorEx` 不在 2024.2 可编译 API 上 | 手型光标无法用 `setCustomCursor` | 直接设置 `editor.contentComponent.cursor`(记录原值恢复) |
| 10 | 真实工程中 XML 同时含"动态查询语句"(select0/insert 等)与"宏方法语句"(get_all/list) | 部分 id 无跳转属**预期**(无对应 Rust 方法) | — |

---

## 十三、性能优化记录(v1.2.1)

| 级别 | 问题 | 优化 |
|------|------|------|
| P1 | 首次交互双重全项目 IO(MapperPathsConfig 与 RustDaoIndex 各自全量读 .rs,且在 EDT) | 新增 `MapperScanCoordinator`:**单次 .rs 遍历**同时供给两个索引;`MapperWarmUpStartup`(postStartupActivity)后台预热,首次交互零等待 |
| P1 | VFS 批量 create 事件(分支切换)触发 N 次全量重建 | `scheduleDebouncedRebuild()`:1500ms 防抖合并为一次 |
| P2 | `.idea/workspace.xml` 高频变更触发无谓重索引(项目目录名含 "mapper" 误匹配) | `isRelevantXml` 排除 `/.idea/` 与 `/target/`(后者兼防构建产物误匹配) |
| P2 | 切换标签无条件重绘全部 gutter 图标 | 按 `document.modificationStamp` + 文件图章跳过未变重绘 |
| P2 | 悬停/点击热路径每次新建 3 个 Regex | 提升为 companion `val`(编译一次) |
| P3 | 索引重建线程不安全、后台读文件无 ReadAction | 协调器 `@Synchronized` + 文件读取包裹 `runReadAction`;各索引 `ensureInitialized` 统一回退到协调器 |
| P3 | 文档编辑后图标/悬停下划线不刷新(需切换标签) | `DocumentListener` 触发(经 invokeLater 回 EDT)按新图章重绘 |

已知取舍:未保存的编辑内容不参与解析(索引基于磁盘内容),保存后自动刷新;大文件的首次解析仍为单文件毫秒级正则扫描。

---

## 十四、踩坑记录(v1.2.2:hirust-error-book 导航失效修复)

背景:目标项目为 workspace 布局(项目根 `hirust-error-book/`,crate 在 `server/` 子目录),
现象为跳转能力不对称 —— XML namespace→Rust DAO 正常,其余 6 项功能(全部 Rust→XML、
XML 语句 id→Rust、悬停提示)失效。依赖矩阵定位:**失效功能全部依赖 XmlNamespaceIndex,
正常功能全部不依赖 → 该索引为空(0 个 XML)**。

| # | 问题 | 症状 | 修复 |
|---|------|------|------|
| 1 | **with_mapper_paths glob 按 project.basePath 解析,但模式实际相对 crate 根**(运行时 `SqlSessionFactory::build(config, ".")` 以 crate 根为 CWD;`server/src/db.rs` 声明 `mappers/**/*.xml`,XML 在 `server/mappers/`) | `Directory not found: [mappers]` 警告,索引 0 个 XML,6 项功能失效 | `MapperPathsConfig` 记录每条模式的【声明文件 crate 根】(向上找 Cargo.toml);`XmlNamespaceIndex` 按 `[crate根, 项目根]` 优先级解析 |
| 2 | 回退扫描只在 patterns 为空时触发;且过滤 `contains("/mapper/")` 匹配不到复数 `mappers` | glob 落空时无兜底 | 前两层通道(glob + `#[dao(xml=...)]` 补录)总数为 0 也触发回退;过滤改为按路径段比较(mapper/mappers) |
| 3 | `#[dao(xml = "...")]` 属性从未被利用(目标项目全部 DAO 都带该属性) | — | 新增精确补录通道:`DaoInfo.xmlAttr/xmlAttrOffset`,相对 DAO 文件 crate 根定位,可独立于 with_mapper_paths 引导索引 |
| 4 | **目标代码风格无 `id="..."` 字面量**(`#[mapper_query]` 裸宏,id 缺省=fn 名,"方法名即 statement_id"),Rust 侧点击只处理字符串字面量 | 即使索引修好,"Rust 语句 id→XML"仍无处可点 | GotoDeclarationHandler 增加词级路径:点击 fn 名/宏名→XML 语句,点击 impl 类型名→`<mapper>`;**精确 span 匹配**(词起点==解析器偏移且文本相等)而非区间包含,避免劫持同区域 Rust 自身导航(如 `pub struct SubjectDao` 处的同名出现) |
| 5 | `stmtTagFor` 忽略 `kind = "insert"` 参数 → stmtTag 错算 select | 标签精确匹配错误(靠 id-only 回退兜住才未致命) | kind 参数(白名单 select/insert/update/delete)优先于宏名映射 |
| 6 | `isValidNamespace` 要求 `::`,点号风格 namespace(`dao.subject`)被拒 | 纯文本模式(非 RustRover IDE)的 namespace 引用永远 resolve 失败 | 放宽为接受 `::` 或 `.` 分隔 |
| 7 | `.rs` 内容变更不刷新 patterns(只处理 create 事件) | 修改 db.rs 里的 glob 后索引不更新直到重启 | content change 时读内容含 `with_mapper_paths` 才防抖重建 |
| 8 | sample 项目用 `mapper_paths: vec![...]` 结构体字面量,从不匹配提取正则 —— **pattern 通道此前无任何真实用例覆盖**(正是坑 #1 能潜伏的原因) | 回归盲区 | sample 改为 `.with_mapper_paths(vec![...])` 真实调用形态 |
| 9 | `RustGutterManager.paintGutter` 在 `daos.isEmpty()` 时提前 return,跳过旧 highlighter 清理 | 删光 `#[dao]` 后图标残留 | 清理移到 isEmpty 判断之前 |
| 10 | 词级点击 fn 名时 Rust 插件自身导航目标与插件目标合并 → 可能弹双 target popup | — | 接受(MybatisX 同款取舍);350ms 自导航窗口保证最终落点正确 |

**v1.2.3 增补**:`#[dao(xml = "mappers/XxxMapper.xml")]` 的路径字面量 Ctrl+Click → 跳到该
XML 文件(落点 `<mapper>` 标签)。路径解析与索引收集通道 2 共用
`XmlNamespaceIndex.findXmlFileByRelativePath`(crate 根 → 项目根 → 已索引文件后缀匹配);
悬停下划线区间同步覆盖该字面量;纯文本模式经 `XmlPathToXmlFileReference` 支持。

**v1.2.4 增补**(§11 后续展望中的 include 跳转):`<include refid="..."/>` 的 refid 值
Ctrl+Click → `<sql id="...">` 片段定义(落点 `<sql` 标签)。实现要点:
- 解析层:`XmlMapperParser` 新增 `SqlFragmentInfo`/`sqlFragments`(`<sql\b([^>]*)>` + id 属性)
- 查找层:`XmlNamespaceIndex.findSqlFragment(refid, currentFile)` —— 当前文件优先(无前缀
  id),其次命名空间前缀(`<ns>.<id>` / `<ns>::<id>`,多候选时最长前缀优先,与 MyBatis
  语义一致);**不做跨文件同名 id 的宽松回退**,避免误跳
- 通道选择:沿用已真机验证的 `psi.referenceContributor language="XML"`(无需 gotoDeclarationHandler),
  引用可解析时平台自动渲染 Ctrl+悬停原生下划线;目标缺失时 resolve 返回 null(容错)

**v1.2.5 修复**(refid 下划线/手型出现但 Ctrl+Click 不跳转,真机反馈):两处加固——
1. resolve 落点从 `<sql` 的 `<` 单字符 token 改为 **id 属性值元素(XmlAttributeValue)**
   (与 Rust→XML 语句跳转落点同形态,平台导航可靠)
2. 索引未命中时**兜底直读当前文件**解析 sql 片段(同文件场景零索引依赖);
   仍未命中输出 info 日志 `include refid unresolved`(便于 idea.log 定位)

> 另:RustRover 2026.2.1 新插件系统解析器(`pluginSystem.parser.impl`)在
> Install Plugin from Disk 时会对 plugin.xml 的 `<icon>` 元素报
> `SEVERE Unknown element: icon`(非致命,堆栈在 PluginInstaller.installFromDisk),
> 重试安装即可成功 —— 排查见 idea.log。

**v1.2.6 增补**:`<sql id="...">` 的 id 值 Ctrl+Click → 引用它的全部 `<include refid>`
(反向跳转)。解析层新增 `IncludeInfo`/`MapperInfo.includes`;索引层新增
`findIncludesOf(sqlId, definitionFile)`(匹配语义与 findSqlFragment 对称:同文件无前缀 +
任意文件带本 namespace 前缀;同文件在前按出现顺序;索引未收录当前文件时直读兜底);
引用层用 **PsiPolyVariantReference**(`XmlSqlIdToIncludesReference`)——仅一处引用直接跳,
多处平台弹目标列表,无引用时不跳不划线(容错)。

**v1.2.7 增补**:语句的 `resultType="CountRow"` 值 Ctrl+Click → Rust 中同名
`struct CountRow` 定义。实现要点:解析层新增 `RustTypeInfo`/`parseStructTypes`
(`\bstruct\s+NAME` 扫描,不限 #[dao] 文件 —— resultType 对应的模型类型通常在
models 等普通模块);索引层 `RustDaoIndex` 的文件缓存从 `List<DaoInfo>` 扩展为
`ParsedFile(daos, types)`(一次读盘两种产出,struct 扫描挂在协调扫描的单次遍历里,
无额外 IO),新增 `typeIndex` 与 `findType`(支持限定名取末段);XML 侧新增
`resultType` 属性值引用分支(`XmlResultTypeToRustReference`,落点 struct 名称标识符)。
已核对目标项目:13 个 resultType 值与 models 中 `pub struct` 一一对应。

另:RustRover 2026.2.1 新插件解析器(`com.intellij.platform.pluginSystem.parser`)在
install-from-disk 时对 plugin.xml 的 `<icon>` 元素报非致命 SEVERE `Unknown element: icon`
(日志堆栈含 PluginInstaller.installFromDisk),可能导致首次安装中断——**重试即可装上**;
若持续失败可去掉 `<icon>` 声明出兼容版。

**v1.2.6 增补**:`<sql id="...">` 的 id 值 Ctrl+Click → 引用它的全部 `<include refid>`
(反向跳转)。实现:解析层新增 `IncludeInfo`(refid/tagOffset/refidAttrOffset);
`XmlNamespaceIndex.findIncludesOf`(同文件无前缀 + 任意文件本 namespace 前缀,同文件优先,
索引缺失时直读当前文件兜底);引用类 `XmlSqlIdToIncludesReference` 实现
**PsiPolyVariantReference**——单引用直接跳转,多引用平台弹目标列表(与 MybatisX 行为一致)。

关键架构决策:`RustDaoIndex.allDaos()` 裸访问器【不做 ensureInitialized】——
XmlNamespaceIndex 在协调扫描(rebuildAll→rebuildIndex)中调用它,若触发 ensureInitialized
会同线程重入 `@Synchronized` 的 rebuildAll 造成嵌套全量重建。

---

## 九、验证方案

### 9.1 自动化
```powershell
# 单元测试(解析层)
gradlew.bat test

# 编译 + 打包
gradlew.bat buildPlugin
# 产物: build/distributions/hirust-mapper-navigator-1.1.0.zip
```

### 9.2 手动验证(沙箱)
```powershell
gradlew.bat runIde    # 启动带插件的沙箱 IDE(Community,验证 XML 侧 + 纯文本兜底)
```

沙箱中准备样例工程(建议同时在仓库新建 `sample/` 目录,包含):
```
sample/
├── Cargo.toml                     # hirust-mapper = { path = "../hirust-mapper" }(可选)
├── src/app/dao/privilege_project_dao.rs
│   ```rust
│   #[dao(namespace = "crate::app::dao::privilege_project_dao")]
│   impl PrivilegeProjectDao {
│       #[mapper_query]
//!        pub async fn get_all(&self) -> Result<Vec<PrivilegeProject>, Error> { ... }
//!       #[mapper_query(id = "list")]
//!       pub async fn list_data(&self) -> ... { ... }
//!       #[mapper_insert]
//!       pub async fn create(&self, p: &PrivilegeProject) -> ... { ... }
//!   }
//!   ```
└── resources/mapper/privilege_project.xml
    ```xml
    <mapper namespace="crate::app::dao::privilege_project_dao">
        <select id="get_all">SELECT * FROM privilege_project</select>
        <select id="list">SELECT ... </select>
        <insert id="create">INSERT INTO ...</insert>
    </mapper>
    ```
```

**验证清单(Community 沙箱):**
- [ ] XML 中 Ctrl+Click namespace 值 → 跳到 .rs 的 `#[dao(` 行
- [ ] XML 中 Ctrl+Click `<select id="list">` 的 id 值 → 跳到 `#[mapper_query(id = "list")]`
- [ ] XML `<mapper>` / 语句行有 gutter 图标,点击方向正确
- [ ] .rs(纯文本)中 Ctrl+Click namespace 字符串 / id 字符串 / 宏名 → 正确跳转
- [ ] 无对应目标时不跳转、无异常

**验证清单(RustRover,手动安装 zip):**
- [ ] 上述全部
- [ ] .rs 中 `impl` 行 / `#[mapper_*]` fn 行出现 gutter 图标 → 点击跳 XML 对应位置
- [ ] Rust 原生功能(Ctrl+Click 符号、补全)不受影响

### 9.3 回归
- 原有功能不回退:宏名→proc macro 跳转、namespace→XML 文件跳转、`with_mapper_paths` 自动发现。

---

## 十、文件清单总览

| 操作 | 文件 |
|------|------|
| 新增 | `src/main/kotlin/.../RustSourceParser.kt` |
| 新增 | `src/main/kotlin/.../XmlMapperParser.kt` |
| 新增 | `src/main/kotlin/.../RustDaoIndex.kt` |
| 新增 | `src/main/kotlin/.../NavigationUtil.kt` |
| 新增 | `src/main/kotlin/.../XmlMapperReferenceContributor.kt` |
| 新增 | `src/main/kotlin/.../XmlMapperLineMarkerProvider.kt` |
| 新增 | `src/main/kotlin/.../RustReferenceContributor.kt` |
| 新增 | `src/main/kotlin/.../RustLineMarkerProvider.kt` |
| 新增 | `src/main/kotlin/.../Icons.kt` |
| 新增 | `src/main/resources/icons/to_xml.svg`、`to_rust.svg`、`pluginIcon.svg` |
| 新增 | `src/test/kotlin/.../RustSourceParserTest.kt`、`XmlMapperParserTest.kt` |
| 新增 | `sample/`(手动验证样例工程,可选) |
| 修改 | `XmlNamespaceIndex.kt`(语句级索引) |
| 修改 | `XmlIndexRefreshListener.kt`(.rs 刷新) |
| 修改 | `NamespaceToXmlReference.kt`(落点到 `<mapper>` 标签) |
| 修改 | `plugin.xml`(修复损坏引用 + 注册全部 extension,见 §7) |
| 修改 | `build.gradle.kts`(测试依赖) |
| 修改 | `README.md`(功能文档) |
| 不动 | `MacroDefinitionReference.kt`、`MapperPathsConfig.kt`、`NamespacePathResolver.kt` |

---

## 十一、后续展望(本次不做)

- `<include refid>` ↔ `<sql id>` 跳转与检查(v1.2.4/1.2.6 已做)
- JPA 提示:Rust 方法名 Alt+Enter 生成 XML 语句骨架
- 检查(Inspection):XML 语句无对应 Rust 方法 / Rust 方法无对应语句的告警
- 数据库代码生成(依赖 `com.intellij.database`,仅 RustRover/Ultimate 可用)

---

## 十五、XML 自动补全攻坚记录(v1.3.0)

需求:`<mapper namespace="` / 语句 `id="` 属性值内**输入即弹出**补全(namespace 候选=全项目 DAO;
id 候选=标签类型匹配且未被本文件占用的方法 id)。

### 15.1 补全框架三层通道在本环境全部失效(逐层日志实锤)

| 层 | 尝试 | 结果(日志证据) |
|----|------|----------------|
| 1 | `completionContributor language="XML"`(注册正确) | `CC invoked` 零次 —— 补全会话从不咨询 |
| 2 | 引用 `getVariants()` + `AutoPopupController.scheduleAutoPopup` | `scheduleAutoPopup done` 后 getVariants 零次调用 |
| 3 | `UsageViewPresentation` 系(fluent setter) | Kotlin 属性赋值不落 setter(该类 setter 返回 this) |

### 15.2 最终架构(全部稳定 API)

```
TypedHandlerDelegate.charTyped(第4参已从 FileType 改为 PsiFile —— 2024.2 签名变化)
  └─ invokeLater → commitAndRunReadAction(避免陈旧 PSI)
      ├─ 判定属性上下文(namespace / 语句 id)+ 计算输入前缀
      ├─ 候选:XmlMapperCompletionItems(索引 + 未保存文档兜底)
      └─ Alarm 延迟 250ms → LookupManager.showLookup(editor, items, prefix)
            │  (立即显示会被内置补全会话清空 —— "闪现即消失";延迟错峰解决)
            └─ 一次性 DocumentListener:插入后 commitDocument
                 + FileContentUtilCore.reparseFiles(vf)
                    (补全插入与延迟弹窗竞争导致 XML 增量重解析区间错乱 ——
                     表现为 namespace 引用悬停区间拉伸到后续标签,强制重解析修复)
```

### 15.3 连带修复

- **软引用**:全部 XML 引用加 `isSoft()=true` —— 平台对未解析硬引用自动画红波浪线+错误图标,软引用消除噪音
- **图标未保存联动**:RustGutterManager 语句查找失败时直读 XML 当前文档(未保存内容),补全插入后无需保存即出图标
- **移除图章跳过优化**:图标依赖的 XML 侧状态不在 .rs 文档图章中,跳过导致切换标签后不刷新
- **协调扫描风暴**:`rebuildAll` 加 `ready` 短路(曾每秒 ~7 次全项目扫描;强制重建走 `forceRebuild`)
