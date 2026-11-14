package ai.artha.app

import ai.artha.core.LocalModel
import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import java.io.File

/** Qwen 2.5 1.5B running fully on the phone through MediaPipe. Loaded on first use, released when idle. */
class QwenModel(private val context: Context) : LocalModel {
    companion object {
        const val FILE_NAME = "qwen2.5-1.5b-instruct-q8.task"
        const val URL = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"
        const val SIZE_BYTES = 1_600_000_000L
        fun file(c: Context) = File(c.filesDir, "model/$FILE_NAME")
    }

    private var engine: LlmInference? = null
    private val templates = PromptTemplates.builder()
        .setUserPrefix("<|im_start|>user\n").setUserSuffix("<|im_end|>\n")
        .setModelPrefix("<|im_start|>assistant\n").setModelSuffix("<|im_end|>\n")
        .setSystemPrefix("").setSystemSuffix("").build()

    override fun available(): Boolean = file(context).let { it.isFile && it.length() > 100_000_000 }

    @Synchronized
    override fun generate(prompt: String): String {
        val llm = engine ?: LlmInference.createFromOptions(context, LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file(context).absolutePath).setMaxTokens(2048).setMaxTopK(1).build()).also { engine = it }
        val session = LlmInferenceSession.createFromOptions(llm, LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(1).setTemperature(0f).setRandomSeed(1).setPromptTemplates(templates).build())
        return try { session.addQueryChunk(prompt); session.generateResponse() } finally { session.close() }
    }

    @Synchronized
    fun release() { engine?.close(); engine = null }
}
