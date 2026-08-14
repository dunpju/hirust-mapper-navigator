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

dependencies {
    intellijPlatform {
        // CL (CLion) = RustRover 的基础 platform
        create("CL", "2024.2.1")

        // Rust 语言插件 — 提供 RsOuterAttr, RsMetaItem, RsLitExpr 等 PSI 类型
        plugin("org.rust.lang")

        // 插件开发所需的 instrumentation 依赖
        instrumentation()
    }
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
