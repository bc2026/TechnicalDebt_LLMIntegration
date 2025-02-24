package com.intellij.ml.llm.template.intentions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.ml.llm.template.LLMBundle
import com.intellij.ml.llm.template.models.*
//import com.intellij.ml.llm.template.models.CodexRequestProvider
import com.intellij.ml.llm.template.models.ollama.OllamaBody
import com.intellij.ml.llm.template.models.openai.OpenAiChatMessage
import com.intellij.ml.llm.template.settings.LLMSettingsManager
//import com.intellij.ml.llm.template.models.sendEditRequest
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilBase
import java.util.*

@Suppress("UnstableApiUsage")
abstract class ApplyTransformationIntention(
) : IntentionAction {
    private val logger = Logger.getInstance("#com.intellij.ml.llm")
    private fun extractBracketContent(str: String): String {
        val sb = StringBuilder()
        var inBracket = false

        for (c in str) {
            when {
                c == '$' && !inBracket -> {
                    inBracket = true
                    continue
                }
                c == '$' && inBracket -> break
                inBracket -> sb.append(c)
            }
        }

        return sb.toString()
    }



    override fun getFamilyName(): String = LLMBundle.message("intentions.apply.transformation.family.name")

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        return editor != null && file != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return

        val document = editor.document
        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText
        if (selectedText != null) {
            val textRange = TextRange.create(selectionModel.selectionStart, selectionModel.selectionEnd)
            transform(project, selectedText, editor, textRange)
        } else {
            val namedElement = getParentNamedElement(editor)
            if (namedElement != null) {
                val queryText = namedElement.text
                val textRange = namedElement.textRange
                selectionModel.setSelection(textRange.startOffset, textRange.endOffset)
                transform(project, queryText, editor, textRange)
            } else {
                selectionModel.selectLineAtCaret()
                val textRange = getLineTextRange(document, editor)
                transform(project, document.getText(textRange), editor, textRange)
            }
        }
    }

    private fun getLineTextRange(document: Document, editor: Editor): TextRange {
        val lineNumber = document.getLineNumber(editor.caretModel.offset)
        val startOffset = document.getLineStartOffset(lineNumber)
        val endOffset = document.getLineEndOffset(lineNumber)
        return TextRange.create(startOffset, endOffset)
    }

    private fun getParentNamedElement(editor: Editor): PsiNameIdentifierOwner? {
        val element = PsiUtilBase.getElementAtCaret(editor)
        return PsiTreeUtil.getParentOfType(element, PsiNameIdentifierOwner::class.java)
    }

    private fun transform(project: Project, text: String, editor: Editor, textRange: TextRange) {
        val settings = LLMSettingsManager.getInstance()
        val satdType = extractBracketContent(text)

        val instruction = getInstruction(project, editor, satdType) ?: return
        logger.info("Invoke transformation action with '$instruction' instruction for '$text'")
        val task =
            object : Task.Backgroundable(project, LLMBundle.message("intentions.request.background.process.title")) {
                override fun run(indicator: ProgressIndicator) {
                    val modelType = if (settings.provider.equals(LLMSettingsManager.LLMProvider.OLLAMA)) 0 else 1

                    val llmRequestProvider: LLMRequestProvider = when (settings.provider) {
                        LLMSettingsManager.LLMProvider.OLLAMA -> OllamaRequestProvider
                        LLMSettingsManager.LLMProvider.OPENAI -> GPTRequestProvider
                        else -> throw IllegalStateException("Unsupported LLM provider: ${settings.provider}")
                    }

                    // 1-> ollama
                    // 0->  openai
                    if (modelType == 0)
                    {
                        val prompt = "This code has SATDType {"+ extractBracketContent(text) + "}. Take a look at the code: {$text} and fix it."
                        val ollama = OllamaBody(llmRequestProvider.chatModel, prompt, "false")

                        val response = llmRequestProvider?.let {
                            sendOllamaRequest(
                                project,
                                ollama.prompt,
                                stream = "false",
                                llmRequestProvider = it
                            )
                        }

                        if (response != null) {
                            val suggestions = response.getSuggestions()
                            if (suggestions.isEmpty()) {
                                logger.warn("No suggestions received for transformation.")
                            }
                            else {
                                for (s in suggestions) {
                                    println(s.text)
                                }
                            }
                            response.getSuggestions().firstOrNull()?.let {
                                logger.info("Suggested change: $it")
                                invokeLater {
                                    WriteCommandAction.runWriteCommandAction(project) {
                                        updateDocument(project, it.text, editor.document, textRange)
                                    }
                                }
                            }
                        }
                    }

                    else
                    {
                        val messages = listOf(
                            OpenAiChatMessage(role = "user", content = "[$satdType, fix it]: $text"),
                        )

                        val response = llmRequestProvider?.let {
                            sendChatRequest(
                                project,
                                messages,
                                model = llmRequestProvider.chatModel,
                                llmRequestProvider = it
                            )
                        }

                        if (response != null) {
                            val suggestions = response.getSuggestions()
                            if (suggestions.isEmpty()) {
                                logger.warn("No suggestions received for transformation.")
                            }
                            else {
                                for (s in suggestions) {
                                    println(s.text)
                                }
                            }
                            response.getSuggestions().firstOrNull()?.let {
                                logger.info("Suggested change: $it")
                                invokeLater {
                                    WriteCommandAction.runWriteCommandAction(project) {
                                        updateDocument(project, it.text, editor.document, textRange)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ProgressManager.getInstance().runProcessWithProgressAsynchronously(task, BackgroundableProcessIndicator(task))
    }

    abstract fun getInstruction(project: Project, editor: Editor, satdType: String): String?

    private fun updateDocument(project: Project, suggestion: String, document: Document, textRange: TextRange) {
        document.replaceString(textRange.startOffset, textRange.endOffset, suggestion)
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        psiFile?.let {
            val reformatRange = TextRange(textRange.startOffset, textRange.startOffset + suggestion.length)
            CodeStyleManager.getInstance(project).reformatText(it, listOf(reformatRange))
        }
    }

    override fun startInWriteAction(): Boolean = false
}

