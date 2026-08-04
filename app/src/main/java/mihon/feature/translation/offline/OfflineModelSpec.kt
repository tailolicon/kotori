package mihon.feature.translation.offline

/**
 * Identity of the on-device HY-MT model + official generation hyperparameters.
 *
 * Size and SHA-256 were verified against the official Hugging Face GGUF release.
 * Sampling matches the HY-MT1.5 model card (not generic llama defaults).
 */
object OfflineModelSpec {
    const val IDENTITY = "HY-MT1.5-1.8B-Q4_K_M"
    const val FILE_NAME = "HY-MT1.5-1.8B-Q4_K_M.gguf"
    const val DOWNLOAD_URL =
        "https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF/resolve/main/HY-MT1.5-1.8B-Q4_K_M.gguf"
    const val EXPECTED_SIZE_BYTES = 1_133_080_512L
    const val EXPECTED_SHA256 =
        "4383AC0C3C8E476DE98FF979C2A3F069F8C4FB385E7860CF2D28DA896CC477C7"

    /** Context tokens — must stay small; default llama.cpp context tried to allocate 16 GB. */
    const val DEFAULT_CONTEXT = 2048

    /** Max new tokens per bubble / paragraph. Finite — never unbounded generation. */
    const val MAX_NEW_TOKENS_BUBBLE = 128
    const val MAX_NEW_TOKENS_PROSE = 512

    // Official HY-MT1.5 model-card sampling.
    const val TEMPERATURE = 0.7f
    const val TOP_K = 20
    const val TOP_P = 0.6f
    const val REPEAT_PENALTY = 1.05f

    const val DEFAULT_THREADS = 4
    const val MIN_THREADS = 1
    const val MAX_THREADS = 8

    /**
     * Official HY-MT special-token chat template used when GGUF metadata has no template
     * (or when formatting on the Kotlin side for unit tests).
     *
     * Shape: `<｜hy_begin▁of▁sentence｜><｜hy_User｜>{prompt}<｜hy_Assistant｜>`
     */
    const val HY_BOS = "<｜hy_begin▁of▁sentence｜>"
    const val HY_USER = "<｜hy_User｜>"
    const val HY_ASSISTANT = "<｜hy_Assistant｜>"

    const val LEGAL_PROVIDER_NAME = "Kotori"
    const val LEGAL_PROVIDER_ENTITY = "Kotori (app.mihon.dev)"
    const val LICENSE_ASSET_PATH = "licenses/TENCENT_HY_COMMUNITY_LICENSE.txt"
    const val NOTICE_ASSET_PATH = "licenses/TENCENT_HY_NOTICE.txt"
    const val LLAMA_LICENSE_ASSET_PATH = "licenses/llama.cpp-LICENSE.txt"

    /** Preference key version for license acceptance — bump if the agreement text changes. */
    const val LICENSE_ACCEPTANCE_VERSION = 1
}
