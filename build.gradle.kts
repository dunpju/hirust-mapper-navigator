plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.1"
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
}

group = "com.hirust"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// RustRover 2026.2.1 基于 CLion 2024.2 platform
// type = CL 是 RustRover 的 IntelliJ Platform 类型标识
dependencies {
    intellijPlatform {
        // CL (CLion) = RustRover 的基础 platform
        // 2024.2.1 对应 RustRover 2024.2.x / 2026.x 系列
        create("CL", "2024.2.1")

        // Rust 语言插件 — 提供 RsOuterAttr, RsMetaItem, RsLitExpr 等 PSI 类型
        plugin("org.rust.lang")
    }
}

kotlin {
    jvmToolchain(17)
}

intellij {
    pluginName.set("hirust-mapper-navigator")
}

tasks {
    buildSearchableOptions {
        enabled = false
    }

    patchPluginXml {
        sinceBuild.set("242.20241")
        untilBuild.set("251.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
