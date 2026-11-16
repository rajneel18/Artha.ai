package ai.artha.app

import ai.artha.core.LocalModel
import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import java.io.File
import java.util.concurrent.Executors

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

    /** All AI work runs on one low-priority thread (its helper threads inherit that), so the screen stays smooth. */
    private val aiThread = Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "artha-ai")
    }
    private fun <T> onAiThread(block: () -> T): T = aiThread.submit(java.util.concurrent.Callable { block() }).get()
    private var cachedPrefix: String? = null
    private var base: LlmInferenceSession? = null
    private val cloningWorks get() = !cloningFailed && !CrashGuard.isOff(context, "reuse")
    private var cloningFailed = false

    // Chat formatting is added by hand so a shared prefix can be cached.
    private val noTemplates = PromptTemplates.builder().setUserPrefix("").setUserSuffix("").setModelPrefix("").setModelSuffix("")
        .setSystemPrefix("").setSystemSuffix("").build()

    /** Off if even the safe CPU mode crashed on this phone; queued messages then just wait. */
    override fun available(): Boolean = file(context).let { it.isFile && it.length() > 100_000_000 } && !CrashGuard.isOff(context, "cpu")

    private fun load(backend: LlmInference.Backend) = LlmInference.createFromOptions(context, LlmInference.LlmInferenceOptions.builder()
        .setModelPath(file(context).absolutePath).setMaxTokens(2048).setMaxTopK(1).setPreferredBackend(backend).build())

    private fun engine(): LlmInference = engine ?: run {
        if (CrashGuard.isOff(context, "gpu")) load(LlmInference.Backend.CPU)
        else try { CrashGuard.risky(context, "gpu") { load(LlmInference.Backend.GPU) } } catch (e: Throwable) {
            Log.w("Artha", "GPU unavailable, using CPU", e); load(LlmInference.Backend.CPU)
        }
    }.also { engine = it }

    private fun newSession() = LlmInferenceSession.createFromOptions(engine(), LlmInferenceSession.LlmInferenceSessionOptions.builder()
        .setTopK(1).setTemperature(0f).setRandomSeed(1).setPromptTemplates(noTemplates).build())

    @Synchronized
    override fun generate(prompt: String): String = onAiThread {
        val run = {
            val s = newSession()
            try { s.addQueryChunk(USER + prompt + ASSISTANT); s.generateResponse() } finally { s.close() }
        }
        if (CrashGuard.isOff(context, "gpu")) run() else CrashGuard.risky(context, "gpu", run)
    }

    @Synchronized
    override fun generate(prefix: String, suffix: String): String {
        if (!cloningWorks) return generate(prefix + suffix)
        return onAiThread { generateReusing(prefix, suffix) }
    }

    private fun generateReusing(prefix: String, suffix: String): String {
        try {
            if (cachedPrefix != prefix || base == null) {
                base?.close()
                base = newSession().also { it.addQueryChunk(USER + prefix) }
                cachedPrefix = prefix
            }
            return CrashGuard.risky(context, "reuse") {
                val s = base!!.cloneSession()
                try { s.addQueryChunk(suffix + ASSISTANT); s.generateResponse() } finally { s.close() }
            }
        } catch (e: Throwable) {
            Log.w("Artha", "Prefix reuse not supported here, using full prompts", e)
            cloningFailed = true; base?.close(); base = null; cachedPrefix = null
            val s = newSession()
            return try { s.addQueryChunk(USER + prefix + suffix + ASSISTANT); s.generateResponse() } finally { s.close() }
        }
    }

    @Synchronized
    /** Frees the model's memory once the current task (if any) is done. Never blocks the caller. */
    fun release() { aiThread.execute { base?.close(); base = null; cachedPrefix = null; engine?.close(); engine = null } }
}
