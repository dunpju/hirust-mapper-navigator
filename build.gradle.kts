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
 * 使用 IntelliJ IDEA Community 作为 base IDE（CI 环境总是可用）。
 *
 * 编译时不依赖 org.rust.lang 插件（它仅随 RustRover 捆绑发行），
 * plugin.xml 中通过 <depends>org.rust.lang</depends> 声明运行时依赖。
 * Kotlin 代码使用通用 PsiElement API + 运行时类型名检查，
 * 不直接引用 RsOuterAttr / RsMetaItem 等 Rust PSI 类型。
 */
intellij {
    type.set("IC")
    version.set("2024.2.2")
    pluginName.set("hirust-mapper-navigator")
    downloadSources.set(false)
}

kotlin {
    jvmToolchain(17)
}

tasks {
    buildSearchableOptions {
        enabled = false
    }

    patchPluginXml {
        sinceBuild.set("242")
        untilBuild.set("263.*")
    }
}
