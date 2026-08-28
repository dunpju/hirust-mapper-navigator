package com.hirust.mapper.navigation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 从 Rust 源码中提取 `.with_mapper_paths(vec![...])` 配置，
 * 解析出 XML mapper 文件的 glob 路径模式。
 *
 * 每条模式记录其【声明文件的 crate 根】作为解析基准:v1.2.2 之前模式一律相对
 * project.basePath 解析,在 workspace 布局(crate 位于项目根子目录)下必落空 ——
 * 运行时 `SqlSessionFactory::build(config, ".")` 的 "." 是 crate 根(cargo run 的
 * 工作目录),模式实际相对 crate 根。[MapperPattern.baseDirPath] 即由此推导。
 *
 * 扫描入口由 [MapperScanCoordinator] 统一协调(单次 .rs 遍历),
 * 本类只负责从【已读取的内容】提取模式,不再自行读盘。
 */

/** 一条 with_mapper_paths 模式及其解析基准目录('/' 归一化 path;无 crate 根时为 null) */
data class MapperPattern(val pattern: String, val baseDirPath: String?)

class MapperPathsConfig(private val project: Project) {

    private val log = Logger.getInstance(MapperPathsConfig::class.java)

    private val _patterns: CopyOnWriteArrayList<MapperPattern> = CopyOnWriteArrayList()

    val patterns: List<MapperPattern> get() = _patterns.toList()

    /** 声明文件 parent path -> crate 根(空 Optional = 无 Cargo.toml 祖先);beginScan 清空 */
    private val crateRootCache = ConcurrentHashMap<String, Optional<String>>()

    /** 协调扫描开始:清空旧模式与 crate 根缓存(分支切换可能增删 Cargo.toml) */
    fun beginScan() {
        _patterns.clear()
        crateRootCache.clear()
    }

    /** 协调扫描喂数据:从已读取的文件内容提取模式(避免重复 IO) */
    fun acceptFileContent(vf: VirtualFile, content: String) {
        if (!content.contains("with_mapper_paths")) return
        val literals = extractStringLiterals(content)
        if (literals.isEmpty()) return
        val base = crateRootPathOf(vf)
        for (pattern in literals) {
            _patterns.addIfAbsent(MapperPattern(pattern, base))
            if (log.isDebugEnabled) {
                log.debug("[hirust-mapper-navigator] mapper pattern: $pattern (base=$base, from ${vf.path})")
            }
        }
    }

    /** 从源码文本中提取 with_mapper_paths 的 *.xml 字符串字面量 */
    private fun extractStringLiterals(content: String): List<String> {
        val result = mutableListOf<String>()
        // 定位 with_mapper_paths 调用附近的 vec![...] 参数区间(粗略:其后 2000 字符)
        val regex = Regex("""with_mapper_paths\s*\(\s*vec!\s*\[""")
        val literalRegex = Regex("\"([^\"]+\\.xml)\"")
        for (call in regex.findAll(content)) {
            val region = content.substring(call.range.first, minOf(content.length, call.range.last + 2000))
            // 截断到 vec![...] 结束
            val regionEnd = region.indexOf(']')
            val scoped = if (regionEnd > 0) region.substring(0, regionEnd) else region
            for (match in literalRegex.findAll(scoped)) {
                val pattern = match.groupValues[1]
                if (pattern.contains("/") || pattern.contains("\\") || pattern.contains("*")) {
                    result += pattern
                }
            }
        }
        return result
    }

    /** 带缓存的 crate 根推导(按声明文件 parent path 缓存,防大项目重复走 VFS) */
    private fun crateRootPathOf(vf: VirtualFile): String? {
        val parentPath = vf.parent?.path ?: return null
        return crateRootCache.computeIfAbsent(parentPath) {
            Optional.ofNullable(crateRootPathOfUncached(vf))
        }.orElse(null)
    }

    companion object {
        fun getInstance(project: Project): MapperPathsConfig =
            project.getService(MapperPathsConfig::class.java)

        /**
         * 从文件向上找最近的含 Cargo.toml 的目录(≤10 层),返回 '/' 归一化 path。
         * 镜像 cargo 的包发现:member crate 的最近 Cargo.toml 就是 member 根。
         */
        fun crateRootPathOfUncached(vf: VirtualFile): String? =
            ApplicationManager.getApplication().runReadAction<String?> {
                var dir: VirtualFile? = vf.parent
                var hops = 0
                while (dir != null && hops < 10) {
                    if (dir.findChild("Cargo.toml") != null) {
                        return@runReadAction dir.path.replace('\\', '/')
                    }
                    dir = dir.parent
                    hops++
                }
                null
            }
    }
}
