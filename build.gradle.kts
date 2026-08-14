plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.4"
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
}

group = "com.hirust"
version = "1.0.0"

repositories {
    mavenCentral()
}

/*
 * 使用 IntelliJ IDEA Community 作为 base IDE（CI 环境总是可用），
 * 从 JetBrains Marketplace 下载 Rust 插件。
 *
 * Rust 插件 (org.rust.lang) 仅随 RustRover 捆绑发行，
 * CLion 等其他 IDE 需要从 Marketplace 安装。
 */
intellij {
    type.set("IC")
    version.set("2024.2.2")
    pluginName.set("hirust-mapper-navigator")
    downloadSources.set(false)

    // org.rust.lang: Rust 语言插件，提供 RsOuterAttr, RsMetaItem, RsLitExpr 等 PSI 类型
    // org.rust.clion: Rust 对 CLion/CMake 的集成（可选，提供 C 项目支持）
    plugins.set(listOf(
        "org.rust.lang",
        "org.toml.lang"
    ))
}

kotlin {
    jvmToolchain(17)
}

tasks {
    buildSearchableOptions {
        enabled = false
    }

    patchPluginXml {
        sinceBuild.set("242.20241")
        untilBuild.set("251.*")
    }
}
