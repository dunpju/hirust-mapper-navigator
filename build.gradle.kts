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

// RustRover 基于 CLion，使用 CLion 的 build number 作为 intellij version
// CL 2024.2.1 对应 RustRover 2024.2.x / 2026.x 系列
val clionVersion = "2024.2.1"

intellij {
    type.set("CL")
    version.set(clionVersion)
    pluginName.set("hirust-mapper-navigator")
    downloadSources.set(false)

    // 依赖 Rust 语言插件（intellij-rust），提供 RsOuterAttr, RsMetaItem 等 PSI 类型
    plugins.set(listOf("org.rust.lang"))
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
