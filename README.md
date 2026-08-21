# Hirust Mapper Navigator

RustRover 插件，为 [hirust-mapper](https://github.com/hirust/hirust-mapper) MyBatis 风格 ORM 框架提供自定义 Ctrl+Click 导航功能。

## 功能

### 1. 宏定义跳转

Ctrl+Click 属性宏名称，跳转到 proc macro 定义源码：

```rust
#[mapper_query]           // Ctrl+Click → hirust-mapper crate 中的 #[proc_macro_attribute] pub fn mapper_query
pub async fn get_all(&self) -> Result<Vec<T>> {}

#[mapper_query(id = "list")]
pub async fn list_data(...) -> Result<Vec<T>> {}
```

**支持的宏**：`dao`, `mapper_query`, `mapper_insert`, `mapper_update`, `mapper_delete`, `mapper_select`，以及所有 `mapper_*` 前缀的宏。

### 2. XML 映射文件跳转

Ctrl+Click `#[dao]` 属性中的 namespace 字符串值，跳转到对应的 XML 文件：

```rust
#[dao(namespace = "crate::app::dao::privilege_project_dao")]
impl PrivilegeProjectDao { ... }
```

Ctrl+Click `"crate::app::dao::privilege_project_dao"` → 打开 `resources/mapper/privilege_project.xml`

**命名转换规则**：
- 从 namespace 路径提取末段模块名：`privilege_project_dao`
- 去除后缀 (`_dao`, `_service`, `_repo`, `_repository`, `_mapper`, `_accessor`)：`privilege_project`
- 匹配 XML 文件：`resources/mapper/privilege_project.xml`

### 3. 自动发现 XML 资源路径

插件自动从 Rust 源码中的 `.with_mapper_paths()` 配置提取 XML 文件路径：

```rust
let mapper_config = HirustMapperConfig::new()
    .with_mapper_paths(vec!["resources/mapper/**/*.xml".to_string()]);
```

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

构建产物位于 `build/distributions/hirust-mapper-navigator-1.0.0.zip`。

### 在 RustRover 中安装

1. 打开 RustRover → **Settings** → **Plugins**
2. 点击 **⚙️** → **Install Plugin from Disk...**
3. 选择 `build/distributions/hirust-mapper-navigator-1.0.0.zip`
4. 重启 RustRover

### 调试模式运行

在 RustRover 中直接打开本插件项目（`hirust-mapper-navigator`），使用 Gradle 运行配置：

```bash
# 启动带插件的 RustRover 沙箱实例
./gradlew runIde
```

## 项目结构

```
hirust-mapper-navigator/
├── build.gradle.kts              # IntelliJ Platform 构建配置
├── settings.gradle.kts
├── gradle.properties
├── src/main/kotlin/com/hirust/mapper/navigation/
│   ├── MacroAttributeReferenceContributor.kt   # 入口：注册 Ctrl+Click 引用
│   ├── MacroDefinitionReference.kt             # #[mapper_query] → proc macro 定义
│   ├── NamespaceToXmlReference.kt              # namespace 字符串 → XML 文件
│   ├── NamespacePathResolver.kt                # 路径映射工具（下划线→短横线转换）
│   ├── XmlNamespaceIndex.kt                    # XML namespace 缓存索引
│   ├── XmlIndexRefreshListener.kt              # XML 文件变更监听
│   └── MapperPathsConfig.kt                   # with_mapper_paths 配置解析
├── src/main/resources/META-INF/
│   └── plugin.xml                             # IntelliJ 插件描述符
└── README.md
```

## 版本兼容性

| RustRover | IntelliJ Platform | 插件版本 |
|-----------|-------------------|----------|
| 2026.2.x  | CL 2024.2.x      | 1.0.0    |
| 2024.3.x  | CL 2024.2.x      | 1.0.0    |

## 后续计划（二期）

> 📋 **完整改造方案见 [PLAN.md](PLAN.md)** —— 参照 [MybatisX](https://plugins.jetbrains.com/plugin/10119-mybatisx) 插件的功能改造实施计划（双向跳转 + 行标记），可在任何机器上按该文档独立执行。

- [ ] XML → Rust 双向跳转（Ctrl+Click XML 中的 namespace 跳转到 DAO 文件）
- [ ] XML `<select id="...">` ↔ Rust `#[mapper_query]` 函数双向跳转
- [ ] XML SQL 片段 `<include refid="...">` → `<sql id="...">` 跳转
- [ ] namespace 自动补全
