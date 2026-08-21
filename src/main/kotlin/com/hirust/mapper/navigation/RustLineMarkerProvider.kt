package com.hirust.mapper.navigation

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import java.util.function.Supplier

/**
 * Rust 侧行标记(language=RUST,仅在 RustRover 等 Rust PSI 存在的环境生效;
 * 无 Rust 插件的 IDE 中该 extension 被跳过,Ctrl+Click 文本引用仍然可用):
 * - impl 块(带 #[dao] 属性)→ 图标跳转到 XML `<mapper>` 标签
 * - fn(带 #[mapper_*] 属性)→ 图标跳转到 XML 对应语句
 *
 * 通过类名字符串探测 Rust PSI 实现类(编译期不依赖 Rust 插件):
 * intellij-rust 的实现类名形如 RsFunctionImpl / RsImplItemImpl,故用 contains 判断。
 * 若版本不匹配则静默降级(不显示图标)。
 */
class RustLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val vFile = element.containingFile?.virtualFile ?: return null
        if (vFile.extension != "rs") return null

        val className = element.javaClass.name
        return when {
            className.contains("RsFunction") -> methodMarker(element)
            className.contains("RsImplItem") -> implMarker(element)
            else -> null
        }
    }

    /** fn 级:#[mapper_*] 方法 → XML 语句 */
    private fun methodMarker(fnElement: PsiElement): LineMarkerInfo<*>? {
        val project = fnElement.project
        val vFile = fnElement.containingFile.virtualFile ?: return null
        val loc = RustDaoIndex.getInstance(project)
            .findMethodAt(vFile, fnElement.textRange.startOffset) ?: return null
        val target = XmlNamespaceIndex.getInstance(project)
            .findStatement(loc.dao.namespace, loc.method.id, loc.method.stmtTag) ?: return null

        return LineMarkerInfo(
            fnElement,
            fnElement.textRange,
            Icons.TO_XML,
            null,
            GutterIconNavigationHandler<PsiElement> { _, _ ->
                NavigationUtil.openAt(project, target.file, target.statement.tagOffset)
            },
            GutterIconRenderer.Alignment.LEFT,
            Supplier {
                "跳转到 XML <${target.statement.tag} id=\"${target.statement.id}\"> (${target.file.name})"
            }
        )
    }

    /** impl 级:#[dao] 块 → XML mapper 文件 */
    private fun implMarker(implElement: PsiElement): LineMarkerInfo<*>? {
        val project = implElement.project
        val vFile = implElement.containingFile.virtualFile ?: return null
        val loc = RustDaoIndex.getInstance(project)
            .findDaoAt(vFile, implElement.textRange.startOffset) ?: return null
        val xmlIndex = XmlNamespaceIndex.getInstance(project)
        val xmlFile = xmlIndex.findXmlFile(loc.dao.namespace) ?: return null
        val info = xmlIndex.getMapperInfo(xmlFile)

        return LineMarkerInfo(
            implElement,
            implElement.textRange,
            Icons.TO_XML,
            null,
            GutterIconNavigationHandler<PsiElement> { _, _ ->
                val offset = info?.mapperTagOffset ?: 0
                NavigationUtil.openAt(project, xmlFile, offset)
            },
            GutterIconRenderer.Alignment.LEFT,
            Supplier { "跳转到 XML mapper: ${xmlFile.name} (${loc.dao.namespace})" }
        )
    }
}
