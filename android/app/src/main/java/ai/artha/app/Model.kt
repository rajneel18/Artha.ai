package ai.artha.app

import ai.artha.core.LocalModel
import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import java.io.File

/**
 * Qwen 2.5 1.5B running fully on the phone through MediaPipe.
 * - Prefers the GPU (fast, keeps the phone responsive), falls back to the CPU if the GPU can't load it.
 * - Reuses the work done on the long, shared instructions across messages (session cloning), so each
 *   SMS only costs its own few dozen tokens.
 */
class QwenModel(private val context: Context) : LocalModel {
    companion object {
        const val FILE_NAME = "qwen2.5-1.5b-instruct-q8.task"
        const val URL = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"
        const val SIZE_BYTES = 1_600_000_000L
        fun file(c: Context) = File(c.filesDir, "model/$FILE_NAME")
        private const val USER = "<|im_start|>user\n"
        private const val ASSISTANT = "<|im_end|>\n<|im_start|>assistant\n"
    }

    private var engine: LlmInference? = null
    private var cachedPrefix: String? = null
    private var base: LlmInferenceSession? = null
    private var cloningWorks = true

    // Chat formatting is added by hand so a shared prefix can be cached.
    private val noTemplates = PromptTemplates.builder().setUserPrefix("").setUserSuffix("").setModelPrefix("").setModelSuffix("")
        .setSystemPrefix("").setSystemSuffix("").build()

    override fun available(): Boolean = file(context).let { it.isFile && it.length() > 100_000_000 }

    private fun load(backend: LlmInference.Backend) = LlmInference.createFromOptions(context, LlmInference.LlmInferenceOptions.builder()
        .setModelPath(file(context).absolutePath).setMaxTokens(2048).setMaxTopK(1).setPreferredBackend(backend).build())

    private fun engine(): LlmInference = engine ?: (try { load(LlmInference.Backend.GPU) } catch (e: Throwable) {
        Log.w("Artha", "GPU unavailable, using CPU", e); load(LlmInference.Backend.CPU)
    }).also { engine = it }

    private fun newSession() = LlmInferenceSession.createFromOptions(engine(), LlmInferenceSession.LlmInferenceSessionOptions.builder()
        .setTopK(1).setTemperature(0f).setRandomSeed(1).setPromptTemplates(noTemplates).build())

    @Synchronized
    override fun generate(prompt: String): String {
        val s = newSession()
        return try { s.addQueryChunk(USER + prompt + ASSISTANT); s.generateResponse() } finally { s.close() }
    }

    @Synchronized
    override fun generate(prefix: String, suffix: String): String {
        if (!cloningWorks) return generate(prefix + suffix)
        try {
            if (cachedPrefix != prefix || base == null) {
                base?.close()
                base = newSession().also { it.addQueryChunk(USER + prefix) }
                cachedPrefix = prefix
            }
            val s = base!!.cloneSession()
            return try { s.addQueryChunk(suffix + ASSISTANT); s.generateResponse() } finally { s.close() }
        } catch (e: Throwable) {
            Log.w("Artha", "Prefix reuse not supported here, using full prompts", e)
            cloningWorks = false; base?.close(); base = null; cachedPrefix = null
            return generate(prefix + suffix)
        }
    }

    @Synchronized
    fun release() { base?.close(); base = null; cachedPrefix = null; engine?.close(); engine = null }
}
