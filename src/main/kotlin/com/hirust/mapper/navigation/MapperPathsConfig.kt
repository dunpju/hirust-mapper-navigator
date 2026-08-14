package com.hirust.mapper.navigation

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 从 Rust 源码中提取 `.with_mapper_paths(vec![...])` 配置，
 * 解析出 XML mapper 文件的 glob 路径模式。
 *
 * 示例配置:
 *   .with_mapper_paths(vec!["resources/mapper/**/*.xml".to_string()]);
 *
 * 提取结果:
 *   ["resources/mapper/**/*.xml"]
 */
class MapperPathsConfig(private val project: Project) {

    /** 当前生效的 glob 模式列表 */
    private val _patterns: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    val patterns: List<String> get() = _patterns.toList()

    /**
     * 扫描项目中所有 Rust 文件，查找 `.with_mapper_paths(...)` 调用，
     * 提取其中的字符串字面量作为 glob 模式。
     */
    fun refresh() {
        _patterns.clear()
        val scope = GlobalSearchScope.projectScope(project)
        val psiManager = PsiManager.getInstance(project)

        // 在项目范围内搜索所有 .with_mapper_paths 方法调用
        val rustFiles = psiManager.findFiles(scope)
            .filterIsInstance<RsFile>()

        for (rustFile in rustFiles) {
            extractPatternsFromFile(rustFile)
        }
    }

    /**
     * 从单个 Rust 文件中提取 with_mapper_paths 参数
     */
    private fun extractPatternsFromFile(file: RsFile) {
        file.accept(object : RsRecursiveVisitor() {
            override fun visitMethodCall(expr: RsMethodCall) {
                super.visitMethodCall(expr)

                val methodName = expr.identifier.text
                if (methodName == "with_mapper_paths") {
                    extractStringLiterals(expr)
                }
            }

            override fun visitCallExpr(expr: RsCallExpr) {
                super.visitCallExpr(expr)

                // 处理关联函数调用形式 HirustMapperConfig::new().with_mapper_paths(...)
                // RsCallExpr 的 fn 引用可能包含方法链
                val reference = expr.expr
                if (reference is RsDotExpr) {
                    val method = reference.methodCall
                    if (method != null && method.identifier.text == "with_mapper_paths") {
                        extractStringLiterals(method)
                    }
                }
            }
        })
    }

    /**
     * 从方法调用的参数中提取所有字符串字面量
     */
    private fun extractStringLiterals(methodCall: RsMethodCall) {
        val arguments = methodCall.valueArgumentList?.exprList ?: return

        for (arg in arguments) {
            when (arg) {
                is RsLitExpr -> {
                    // 直接字符串字面量 "resources/mapper/**/*.xml"
                    val text = extractStringFromLit(arg)
                    if (text != null && text.contains(".xml")) {
                        _patterns.addIfAbsent(text)
                    }
                }
                is RsCallExpr -> {
                    // vec!["...", "..."] 调用 — 需要递归提取
                    if (arg.expr is RsPathExpr) {
                        val pathExpr = arg.expr as RsPathExpr
                        val path = pathExpr.path
                        if (path?.referenceName == "vec") {
                            for (vecArg in arg.valueArgumentList?.exprList ?: emptyList()) {
                                if (vecArg is RsLitExpr) {
                                    val text = extractStringFromLit(vecArg)
                                    if (text != null && text.contains(".xml")) {
                                        _patterns.addIfAbsent(text)
                                    }
                                }
                            }
                        }
                    }
                }
                is RsMacroCall -> {
                    // vec! 宏调用形式
                    if (arg.macroName == "vec") {
                        val macroBody = arg.macroArgumentList
                        if (macroBody != null) {
                            // vec! 内部可能有多个字符串字面量
                            val bodyText = macroBody.text
                            // 简单提取: 找到所有 "..." 中的内容
                            val regex = Regex("\"([^\"]+\\.xml)\"")
                            for (match in regex.findAll(bodyText)) {
                                _patterns.addIfAbsent(match.groupValues[1])
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 从 RsLitExpr 中提取字符串值（去除引号和可能的 .to_string()）
     */
    private fun extractStringFromLit(lit: RsLitExpr): String? {
        return try {
            val text = lit.text
            when {
                text.startsWith("\"") && text.endsWith("\"") -> {
                    text.substring(1, text.length - 1)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun getInstance(project: Project): MapperPathsConfig =
            project.getService(MapperPathsConfig::class.java)
    }
}
