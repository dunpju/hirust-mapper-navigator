# Hirust Mapper Navigator

RustRover 插件，为 [hirust-mapper](https://github.com/hirust/hirust-mapper) MyBatis 风格 ORM 框架提供 [MybatisX](https://plugins.jetbrains.com/plugin/10119-mybatisx) 风格的 **Rust ↔ XML 双向跳转** 导航功能。

## 功能

### 1. Rust ↔ XML 双向跳转（MybatisX 风格）

**XML → Rust**（Ctrl+Click 属性值，或点击行号旁小鸟图标）：
- `<mapper namespace="crate::app::dao::xxx_dao">` 的 namespace 值 → 跳到 Rust 的 `#[dao(...)]` 属性
- `<select|insert|update|delete id="xxx">` 的 id 值 → 跳到对应的 `#[mapper_*]` 方法
- `<select resultType="CountRow">` 的 resultType 值（v1.2.7）→ 跳到 Rust 的 `struct CountRow` 定义

**XML → XML**（v1.2.4，Ctrl+Click 属性值）：
- `<include refid="list_where"/>` 的 refid 值 → 同文件的 `<sql id="list_where">` 定义
- 带命名空间前缀的 `refid="dao.question.list_where"` → 对应 namespace 的 XML 文件中的 `<sql>` 定义
- **反向**（v1.2.6）：`<sql id="list_where">` 的 id 值 → 引用它的全部 `<include refid>`；仅一处直接跳转，多处弹出目标列表

**Rust → XML**（Ctrl+Click 字符串，或点击行号旁图标，RustRover 中生效）：
- `#[dao(namespace = "...")]` 的 namespace 字符串 / impl 类型名 → 跳到 XML 的 `<mapper>` 标签
- `#[dao(xml = "mappers/XxxMapper.xml")]` 的路径字符串（v1.2.3）→ 跳到该 XML 文件（相对 crate 根解析）
- `#[mapper_query(id = "list")]` 的 id 字符串 / **fn 名 / 宏名**（v1.2.2）→ 跳到 XML 对应语句

**id 映射规则**：宏 `id` 参数优先，缺省用函数名。语句类型映射：`kind` 参数优先（`kind = "insert"` → `<insert>`），缺省按宏名映射：`mapper_insert`→`<insert>`、`mapper_update`→`<update>`、`mapper_delete`→`<delete>`、其余（`mapper_query`/`mapper_select` 等）→`<select>`。

**交互体验**（与 IDE 原生超链接一致）：
- Rust 侧：按住 Ctrl 悬停 namespace/xml 路径/id 字面量 → 原生样式下划线 + 手型光标；Ctrl+左键点击跳转
- 行号旁小鸟图标点击跳转（Rust 的 impl/方法行、XML 的 mapper/语句行）

```rust
#[dao(namespace = "crate::app::dao::privilege_project_dao")]
impl PrivilegeProjectDao {
    #[mapper_query]                        // XML: <select id="get_all">
    pub async fn get_all(&self) -> Result<Vec<T>> {}

    #[mapper_query(id = "list")]           // XML: <select id="list">
    pub async fn list_data(...) -> Result<Vec<T>> {}
}
```

### 2. 宏定义跳转

Ctrl+Click 属性宏名称，跳转到 proc macro 定义源码：

```rust
#[mapper_query]           // Ctrl+Click → hirust-mapper crate 中的 #[proc_macro_attribute] pub fn mapper_query
pub async fn get_all(&self) -> Result<Vec<T>> {}
```

**支持的宏**：`dao`, `mapper_query`, `mapper_insert`, `mapper_update`, `mapper_delete`, `mapper_select`，以及所有 `mapper_*` 前缀的宏。

### 3. XML 映射文件跳转

Ctrl+Click `#[dao]` 属性中的 namespace 字符串值，跳转到对应的 XML 文件（落点到 `<mapper>` 标签）：

```rust
#[dao(namespace = "crate::app::dao::privilege_project_dao")]
impl PrivilegeProjectDao { ... }
```

**命名转换规则**：
- 从 namespace 路径提取末段模块名：`privilege_project_dao`
- 去除后缀 (`_dao`, `_service`, `_repo`, `_repository`, `_mapper`, `_accessor`)：`privilege_project`
- 匹配 XML 文件：`resources/mapper/privilege_project.xml`

### 4. 自动发现 XML 资源路径

插件自动从 Rust 源码中的 `.with_mapper_paths()` 配置提取 XML 文件路径：

```rust
let mapper_config = HirustMapperConfig::new()
    .with_mapper_paths(vec!["resources/mapper/**/*.xml".to_string()]);
```

### 环境兼容性

| 环境 | Rust→XML Ctrl+Click | Rust 悬停下划线/图标 | XML→Rust Ctrl+Click | XML→Rust 图标 |
|------|--------------------|--------------------|--------------------|--------------|
| RustRover（推荐） | ✅ | ✅ | ✅ | ✅ |
| 其他 IDE（.rs 为纯文本） | ✅（文本区间引用） | 图标 ✅ / 悬停下划线 ✅ | ✅ | ✅ |

插件编译不依赖 Rust 插件（基于 IntelliJ Platform 通用 API + 文本解析）。
> 实现说明：RustRover 2026.2 的 Rust 支持已并入产品本体，`language="RUST"` 的扩展点注册不会生效；
> 因此 Rust 侧跳转走 `GotoDeclarationHandler`（语言无关）、图标与悬停下划线走编辑器程序化注册通道。
> 偏移计算统一按 Document 坐标（`\n` 归一化），CRLF 文件落点同样精准。详见 [PLAN.md](PLAN.md) 踩坑记录。

## 构建

### CI/CD（GitHub Actions）

推送到 `master` 分支或创建 PR 时自动构建，产物上传到 GitHub Actions Artifacts。

推送 `v*` 标签时自动创建 GitHub Release 并附带插件 zip：

```bash
git tag v1.0.0
git push origin v1.0.0
```

### 本地构建

前置条件：**JDK 17+**

```bash
# Linux / macOS
./gradlew buildPlugin

# Windows
gradlew.bat buildPlugin
```

构建产物位于 `build/distributions/hirust-mapper-navigator-1.2.7.zip`。

### 在 RustRover 中安装

1. 打开 RustRover → **Settings** → **Plugins**
2. 点击 **⚙️** → **Install Plugin from Disk...**
3. 选择 `build/distributions/hirust-mapper-navigator-1.2.7.zip`
4. 重启 RustRover

### 调试模式运行

在 RustRover 中直接打开本插件项目（`hirust-mapper-navigator`），使用 Gradle 运行配置：

```bash
# 启动带插件的沙箱实例（Community，验证 XML 侧 + 纯文本兜底）
./gradlew runIde
```

### 手动验证

`sample/` 目录包含完整验证样例（DAO + mapper XML + `with_mapper_paths` 配置），验证清单见 [PLAN.md §9.2](PLAN.md)。

## 项目结构

```
hirust-mapper-navigator/
├── build.gradle.kts              # IntelliJ Platform 构建配置
├── settings.gradle.kts
├── gradle.properties
├── src/main/kotlin/com/hirust/mapper/navigation/
│   ├── RustSourceParser.kt          # Rust 源码纯文本解析(#[dao]/#[mapper_*])
│   ├── XmlMapperParser.kt           # XML mapper 纯文本解析(namespace/语句)
│   ├── RustDaoIndex.kt              # namespace → DAO/方法索引
│   ├── XmlNamespaceIndex.kt         # XML namespace + 语句索引
│   ├── MapperPathsConfig.kt         # with_mapper_paths 配置解析
│   ├── XmlIndexRefreshListener.kt   # XML/.rs 文件变更监听
│   ├── NavigationUtil.kt            # 打开文件并定位偏移
│   ├── Icons.kt                     # 行标记图标
│   ├── XmlMapperReferenceContributor.kt  # XML 侧引用(namespace/id → Rust)
│   ├── XmlMapperLineMarkerProvider.kt    # XML 侧行标记图标
│   ├── RustReferenceContributor.kt       # Rust 侧引用(宏名/namespace/id → XML)
│   ├── RustLineMarkerProvider.kt         # Rust 侧行标记图标(RustRover)
│   ├── MacroDefinitionReference.kt  # #[mapper_query] → proc macro 定义
│   ├── NamespaceToXmlReference.kt   # namespace 字符串 → XML <mapper>
│   └── NamespacePathResolver.kt     # namespace → XML 文件多策略匹配
├── src/test/kotlin/                 # 解析层单元测试
├── src/main/resources/
│   ├── META-INF/plugin.xml          # IntelliJ 插件描述符
│   └── icons/                       # 行标记与插件图标
├── sample/                          # 手动验证样例工程
└── README.md
```

## 版本兼容性

| RustRover | IntelliJ Platform | 插件版本 |
|-----------|-------------------|----------|
| 2026.2.x  | CL 2024.2.x      | 1.0.0    |
| 2024.3.x  | CL 2024.2.x      | 1.0.0    |

## 后续计划（二期）

> 📋 **完整改造方案见 [PLAN.md](PLAN.md)** —— 参照 [MybatisX](https://plugins.jetbrains.com/plugin/10119-mybatisx) 插件的功能改造实施计划（双向跳转 + 行标记），可在任何机器上按该文档独立执行。

- [x] XML → Rust 双向跳转（Ctrl+Click / 行标记：namespace → DAO、语句 id → 方法）
- [x] Rust → XML 双向跳转（Ctrl+Click / 行标记：namespace 字符串 → `<mapper>`、id 字符串 → 语句）
- [x] XML SQL 片段 `<include refid="...">` → `<sql id="...">` 跳转（v1.2.4，支持命名空间前缀跨文件）
- [x] `<sql id>` → `<include refid>` 反向跳转（v1.2.6，多引用弹列表）
- [x] `resultType` → Rust struct 定义跳转（v1.2.7）
- [ ] namespace / 语句 id 自动补全
- [ ] JPA 提示式语句生成（方法名 → XML 语句骨架）
- [ ] 数据库表 → Rust 结构体 + DAO + XML 代码生成
