package mihon.feature.translation.offline

/**
 * JNI surface for the on-device llama.cpp runtime (`libkotori_llama.so`).
 *
 * Built for arm64-v8a and x86_64. Other ABIs report [isAvailable] = false.
 *
 * Sampling defaults match the official HY-MT1.5 model card
 * (temperature 0.7, top_k 20, top_p 0.6, repeat_penalty 1.05).
 */
internal object LlamaNative {

    @Volatile
    private var loadAttempted = false

    @Volatile
    private var libraryLoaded = false

    fun isAvailable(): Boolean {
        ensureLoaded()
        if (!libraryLoaded) return false
        return runCatching { nativeIsAvailable() }.getOrDefault(false)
    }

    fun load(modelPath: String, nCtx: Int, nThreads: Int): Boolean {
        ensureLoaded()
        if (!libraryLoaded) return false
        return nativeLoad(modelPath, nCtx, nThreads)
    }

    fun unload() {
        if (!libraryLoaded) return
        nativeUnload()
    }

    /** Sets the cancel flag without blocking on generation. */
    fun cancel() {
        if (!libraryLoaded) return
        nativeCancel()
    }

    fun complete(
        prompt: String,
        maxTokens: Int = OfflineModelSpec.MAX_NEW_TOKENS_BUBBLE,
        temperature: Float = OfflineModelSpec.TEMPERATURE,
        topK: Int = OfflineModelSpec.TOP_K,
        topP: Float = OfflineModelSpec.TOP_P,
        repeatPenalty: Float = OfflineModelSpec.REPEAT_PENALTY,
    ): String? {
        ensureLoaded()
        if (!libraryLoaded) return null
        return nativeComplete(prompt, maxTokens, temperature, topK, topP, repeatPenalty)
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loadAttempted) return
        loadAttempted = true
        libraryLoaded = runCatching {
            System.loadLibrary("kotori_llama")
            true
        }.getOrElse { false }
    }

    private external fun nativeIsAvailable(): Boolean
    private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Boolean
    private external fun nativeUnload()
    private external fun nativeCancel()
    private external fun nativeComplete(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        repeatPenalty: Float,
    ): String?
}
