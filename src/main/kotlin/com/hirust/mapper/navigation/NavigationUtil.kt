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
     * 读取文件文本并按 Document 坐标归一化行结束符。
     *
     * IntelliJ 的 Editor Document 内部统一使用 \n 存储文本(磁盘上可以是 \r\n),
     * 因此所有用于跳转寻址的偏移都必须基于 \n 归一化后的文本计算,
     * 否则在 CRLF 文件上会逐行向下漂移(每行多 1 字符)。
     * 同时剥离 UTF-8 BOM(文档同样不含 BOM)。
     */
    fun loadTextDocumentAligned(file: VirtualFile): String? = try {
        val raw = String(file.contentsToByteArray(), Charsets.UTF_8)
        val noBom = if (raw.startsWith(UTF8_BOM)) raw.substring(1) else raw
        noBom.replace("\r\n", "\n").replace("\r", "\n")
    } catch (e: Exception) {
        null
    }

    /** BOM 字符(U+FEFF),用于 [loadTextDocumentAligned] 的剥离判断 */
    private const val UTF8_BOM = "﻿"

    /** 打开文件并把光标定位到 offset(EDT 上执行,滚动到可见) */
    fun openAt(project: Project, file: VirtualFile, offset: Int) {
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
        return psiFile.findElementAt(offset.coerceIn(0, (psiFile.textLength - 1).coerceAtLeast(0)))
    }
}
