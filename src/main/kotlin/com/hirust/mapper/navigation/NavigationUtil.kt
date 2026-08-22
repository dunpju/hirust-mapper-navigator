package com.hirust.mapper.navigation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

/**
 * 跳转工具:打开文件并定位到指定偏移。
 *
 * 引用解析(resolve)返回目标元素;行标记导航直接调用 [openAt]。
 */
object NavigationUtil {

    /**
     * 读取文件文本并保留原始行结束符(\r\n)。
     *
     * 注意:VfsUtil.loadText 会把行尾归一化为 \n,导致按偏移跳转时在 CRLF 文件上
     * 逐行向下漂移;所有需要计算偏移的解析都必须用本方法。
     * 同时剥离 UTF-8 BOM(编辑器文档同样不含 BOM)。
     */
    fun loadTextRaw(file: VirtualFile): String? = try {
        val text = String(file.contentsToByteArray(), Charsets.UTF_8)
        if (text.startsWith(UTF8_BOM)) text.substring(1) else text
    } catch (e: Exception) {
        null
    }

    /** BOM 字符(U+FEFF),用于 [loadTextRaw] 的剥离判断 */
    private const val UTF8_BOM = "﻿"

    /** 打开文件并把光标定位到 offset(EDT 上执行,滚动到可见) */
    fun openAt(project: Project, file: VirtualFile, offset: Int) {
        com.intellij.openapi.diagnostic.Logger.getInstance("HirustDiag")
            .info("[hirust-mapper-navigator] DIAG openAt: ${file.name} offset=$offset")
        val runnable = Runnable {
            if (project.isDisposed || !file.isValid) return@Runnable
            FileEditorManager.getInstance(project)
                .openTextEditor(OpenFileDescriptor(project, file, offset.coerceAtLeast(0)), true)
        }
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) runnable.run() else app.invokeLater(runnable, project.disposed)
    }

    /** 获取文件中 offset 处的 PSI 元素(引用解析的返回目标) */
    fun findElement(file: VirtualFile, project: Project, offset: Int): PsiElement? {
        if (!file.isValid) return null
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        val element = psiFile.findElementAt(offset.coerceIn(0, (psiFile.textLength - 1).coerceAtLeast(0)))
        if (element != null) {
            val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(psiFile)
            val line = doc?.getLineNumber(element.textOffset.coerceIn(0, doc.textLength - 1)) ?: -1
            com.intellij.openapi.diagnostic.Logger.getInstance("HirustDiag").info(
                "[hirust-mapper-navigator] DIAG findElement: ${file.name} psiLen=${psiFile.textLength} " +
                        "askOffset=$offset elemOffset=${element.textOffset} line=$line " +
                        "elem='${element.text.take(20)}'"
            )
        }
        return element
    }
}
