plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.1"
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
}

group = "com.hirust"
version = "1.4.3"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.2")
    }
    testImplementation("junit:junit:4.13.2")
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
