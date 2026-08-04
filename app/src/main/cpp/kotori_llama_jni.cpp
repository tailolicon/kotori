/**
 * Minimal llama.cpp JNI for on-device HY-MT translation.
 *
 * Constraints:
 *  - n_ctx pinned small (~2048) — never the 16 GB default-context trap
 *  - official HY-MT sampling (temp/top_k/top_p/repeat_penalty)
 *  - chat-templated prompts (see OfflinePrompts / HY-MT special tokens)
 *  - cancel must work during generation (atomic flag, no long-held mutex)
 *  - single backend_init / matching backend_free; serialize load/complete/unload
 *
 * MIT — wraps ggml-org/llama.cpp (MIT).
 */

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "KotoriLlama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

// Official HY-MT1.5 model-card sampling defaults.
constexpr float kDefaultTemp = 0.7f;
constexpr int   kDefaultTopK = 20;
constexpr float kDefaultTopP = 0.6f;
constexpr float kDefaultRepeatPenalty = 1.05f;
constexpr int   kDefaultMaxTokens = 128;
constexpr int   kHardMaxTokens = 512;

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::atomic<bool> cancel{false};
    int n_ctx = 2048;
    int n_threads = 4;
};

// Protects g_engine pointer swaps and load/unload. Generation does NOT hold this for the
// whole decode loop — only to take a shared snapshot of the engine pointer. cancel is
// an atomic on the engine itself so nativeCancel never blocks behind generation.
std::mutex g_lifecycle_mu;
Engine *g_engine = nullptr;
std::atomic<bool> g_backend_inited{false};
// Serialises complete() so two callers cannot share one context concurrently.
std::mutex g_infer_mu;

static void ensure_backend() {
    bool expected = false;
    if (g_backend_inited.compare_exchange_strong(expected, true)) {
        llama_backend_init();
        LOGI("llama_backend_init");
    }
}

static void free_backend_if_idle() {
    // Caller must hold g_lifecycle_mu and g_engine must already be null.
    if (g_engine == nullptr && g_backend_inited.exchange(false)) {
        llama_backend_free();
        LOGI("llama_backend_free");
    }
}

static void free_engine_unlocked(Engine *e) {
    if (!e) return;
    e->cancel.store(true);
    if (e->ctx) {
        llama_free(e->ctx);
        e->ctx = nullptr;
    }
    if (e->model) {
        llama_model_free(e->model);
        e->model = nullptr;
    }
    delete e;
}

static std::string jstring_to_utf8(JNIEnv *env, jstring js) {
    if (!js) return {};
    const char *chars = env->GetStringUTFChars(js, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(js, chars);
    return out;
}

static bool is_valid_utf8(const std::string &s) {
    const auto *bytes = reinterpret_cast<const unsigned char *>(s.data());
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        if (bytes[i] <= 0x7F) {
            i++;
        } else if ((bytes[i] & 0xE0) == 0xC0 && i + 1 < n &&
                   (bytes[i + 1] & 0xC0) == 0x80) {
            i += 2;
        } else if ((bytes[i] & 0xF0) == 0xE0 && i + 2 < n &&
                   (bytes[i + 1] & 0xC0) == 0x80 && (bytes[i + 2] & 0xC0) == 0x80) {
            i += 3;
        } else if ((bytes[i] & 0xF8) == 0xF0 && i + 3 < n &&
                   (bytes[i + 1] & 0xC0) == 0x80 && (bytes[i + 2] & 0xC0) == 0x80 &&
                   (bytes[i + 3] & 0xC0) == 0x80) {
            i += 4;
        } else {
            return false;
        }
    }
    return true;
}

// Official HY-MT special-token wrap (fallback when GGUF has no chat template).
// Shape: <｜hy_begin▁of▁sentence｜><｜hy_User｜>{prompt}<｜hy_Assistant｜>
static std::string wrap_hy_mt_tokens(const std::string &user_prompt) {
    return std::string(u8"<｜hy_begin▁of▁sentence｜>")
            + u8"<｜hy_User｜>"
            + user_prompt
            + u8"<｜hy_Assistant｜>";
}

/**
 * Apply chat template EXACTLY once.
 * 1) Prefer GGUF metadata via llama_model_chat_template + llama_chat_apply_template(tmpl, …)
 *    (b10240 signature: first arg is const char *tmpl, NOT the model pointer).
 * 2) Else wrap with official HY-MT special tokens.
 * Kotlin must pass raw user content only — never pre-templated text.
 */
static std::string apply_chat_template_once(llama_model *model, const std::string &user_prompt) {
    std::vector<llama_chat_message> messages;
    messages.push_back({"user", user_prompt.c_str()});

    const char *tmpl = llama_model_chat_template(model, /* name */ nullptr);
    if (tmpl != nullptr && tmpl[0] != '\0') {
        std::string buf;
        buf.resize(user_prompt.size() + 2048);
        int32_t n = llama_chat_apply_template(
                tmpl,
                messages.data(),
                messages.size(),
                /* add_ass */ true,
                buf.data(),
                static_cast<int32_t>(buf.size()));
        // b10240 returns the full required byte count even when the supplied buffer is too
        // small (negative values indicate an unsupported/invalid template instead).
        if (n > static_cast<int32_t>(buf.size()) && n < (1 << 20)) {
            buf.resize(static_cast<size_t>(n));
            n = llama_chat_apply_template(
                    tmpl,
                    messages.data(),
                    messages.size(),
                    true,
                    buf.data(),
                    static_cast<int32_t>(buf.size()));
        }
        if (n > 0) {
            buf.resize(static_cast<size_t>(n));
            LOGI("chat template from metadata applied (%d chars)", n);
            return buf;
        }
        LOGI("llama_chat_apply_template failed; falling back to HY-MT tokens");
    } else {
        LOGI("no model chat template; using HY-MT special tokens");
    }
    return wrap_hy_mt_tokens(user_prompt);
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_mihon_feature_translation_offline_LlamaNative_nativeIsAvailable(JNIEnv *, jclass) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_mihon_feature_translation_offline_LlamaNative_nativeLoad(
        JNIEnv *env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    // Wait for any in-flight complete to finish before tearing down.
    std::lock_guard<std::mutex> infer(g_infer_mu);
    std::lock_guard<std::mutex> life(g_lifecycle_mu);

    if (g_engine) {
        free_engine_unlocked(g_engine);
        g_engine = nullptr;
        // Keep backend alive across reload — only free when fully idle at unload.
    }

    const std::string path = jstring_to_utf8(env, jpath);
    if (path.empty()) {
        LOGE("nativeLoad: empty path");
        return JNI_FALSE;
    }

    const int ctx_size = n_ctx > 0 ? static_cast<int>(n_ctx) : 2048;
    const int safe_ctx = ctx_size > 4096 ? 4096 : ctx_size;
    int threads = n_threads > 0 ? static_cast<int>(n_threads) : 4;
    if (threads < 1) threads = 1;
    if (threads > 8) threads = 8;

    ensure_backend();

    llama_model_params mparams = llama_model_default_params();
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        LOGE("nativeLoad: failed to load model at %s", path.c_str());
        free_backend_if_idle();
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(safe_ctx);
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("nativeLoad: failed to create context (n_ctx=%d)", safe_ctx);
        llama_model_free(model);
        free_backend_if_idle();
        return JNI_FALSE;
    }

    auto *engine = new Engine();
    engine->model = model;
    engine->ctx = ctx;
    engine->n_ctx = safe_ctx;
    engine->n_threads = threads;
    engine->cancel.store(false);
    g_engine = engine;

    LOGI("nativeLoad: ready path=%s n_ctx=%d threads=%d", path.c_str(), safe_ctx, threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_mihon_feature_translation_offline_LlamaNative_nativeUnload(JNIEnv *, jclass) {
    // Signal cancel first so a running complete exits promptly.
    {
        std::lock_guard<std::mutex> life(g_lifecycle_mu);
        if (g_engine) g_engine->cancel.store(true);
    }
    std::lock_guard<std::mutex> infer(g_infer_mu);
    std::lock_guard<std::mutex> life(g_lifecycle_mu);
    if (g_engine) {
        free_engine_unlocked(g_engine);
        g_engine = nullptr;
    }
    free_backend_if_idle();
    LOGI("nativeUnload: done");
}

extern "C" JNIEXPORT void JNICALL
Java_mihon_feature_translation_offline_LlamaNative_nativeCancel(JNIEnv *, jclass) {
    // Never take g_infer_mu — that would deadlock with complete. Atomic only.
    std::lock_guard<std::mutex> life(g_lifecycle_mu);
    if (g_engine) {
        g_engine->cancel.store(true);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_mihon_feature_translation_offline_LlamaNative_nativeComplete(
        JNIEnv *env,
        jclass,
        jstring jprompt,
        jint max_tokens,
        jfloat temperature,
        jint top_k,
        jfloat top_p,
        jfloat repeat_penalty) {
    std::lock_guard<std::mutex> infer(g_infer_mu);

    Engine *e = nullptr;
    {
        std::lock_guard<std::mutex> life(g_lifecycle_mu);
        e = g_engine;
        if (!e || !e->ctx || !e->model) {
            LOGE("nativeComplete: engine not loaded");
            return nullptr;
        }
        e->cancel.store(false);
    }

    const std::string raw_prompt = jstring_to_utf8(env, jprompt);
    if (raw_prompt.empty()) {
        return env->NewStringUTF("");
    }

    // Always template once here. Kotlin sends raw user content only.
    const std::string prompt = apply_chat_template_once(e->model, raw_prompt);

    const llama_vocab *vocab = llama_model_get_vocab(e->model);
    llama_memory_clear(llama_get_memory(e->ctx), true);

    // Do NOT add BOS again if the chat template already included a BOS / begin-of-sentence token.
    const bool add_bos = false;
    std::vector<llama_token> tokens;
    tokens.resize(prompt.size() + 16);
    int n = llama_tokenize(
            vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            tokens.data(), static_cast<int32_t>(tokens.size()), add_bos, true);
    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(
                vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                tokens.data(), static_cast<int32_t>(tokens.size()), add_bos, true);
    }
    if (n <= 0) {
        LOGE("nativeComplete: tokenize failed (%d)", n);
        return nullptr;
    }
    tokens.resize(static_cast<size_t>(n));

    const int max_prompt = e->n_ctx - 64;
    if (static_cast<int>(tokens.size()) > max_prompt) {
        tokens.erase(tokens.begin(), tokens.end() - max_prompt);
        LOGI("nativeComplete: truncated prompt to %d tokens", max_prompt);
    }

    // One batch capacity for both prefill and single-token decode — no free/realloc per token.
    const int32_t batch_cap = static_cast<int32_t>(
            std::max<size_t>(static_cast<size_t>(tokens.size()), 1));
    llama_batch batch = llama_batch_init(batch_cap, 0, 1);

    for (int32_t i = 0; i < static_cast<int32_t>(tokens.size()); ++i) {
        batch.token[i] = tokens[static_cast<size_t>(i)];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == static_cast<int32_t>(tokens.size()) - 1);
    }
    batch.n_tokens = static_cast<int32_t>(tokens.size());

    if (llama_decode(e->ctx, batch) != 0) {
        LOGE("nativeComplete: prompt decode failed");
        llama_batch_free(batch);
        return nullptr;
    }

    int n_predict = max_tokens > 0 ? static_cast<int>(max_tokens) : kDefaultMaxTokens;
    if (n_predict > kHardMaxTokens) n_predict = kHardMaxTokens;

    const float temp = temperature >= 0.f ? temperature : kDefaultTemp;
    const int tk = top_k > 0 ? static_cast<int>(top_k) : kDefaultTopK;
    const float tp = top_p > 0.f ? top_p : kDefaultTopP;
    const float rp = repeat_penalty > 0.f ? repeat_penalty : kDefaultRepeatPenalty;

    auto *sampler_chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Order: penalties → top_k → top_p → temp → dist  (matches common llama.cpp practice)
    llama_sampler_chain_add(sampler_chain, llama_sampler_init_penalties(
            /* penalty_last_n */ 64,
            /* penalty_repeat */ rp,
            /* penalty_freq */ 0.0f,
            /* penalty_present */ 0.0f));
    llama_sampler_chain_add(sampler_chain, llama_sampler_init_top_k(tk));
    llama_sampler_chain_add(sampler_chain, llama_sampler_init_top_p(tp, 1));
    llama_sampler_chain_add(sampler_chain, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(sampler_chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string output;
    output.reserve(512);
    int n_cur = static_cast<int>(tokens.size());
    int generated = 0;

    while (generated < n_predict) {
        if (e->cancel.load()) {
            LOGI("nativeComplete: cancelled after %d tokens", generated);
            break;
        }
        if (n_cur >= e->n_ctx - 1) {
            LOGI("nativeComplete: context full after %d tokens", generated);
            break;
        }

        const llama_token id = llama_sampler_sample(sampler_chain, e->ctx, -1);
        llama_sampler_accept(sampler_chain, id);

        if (llama_vocab_is_eog(vocab, id)) {
            break;
        }

        char buf[256];
        const int n_piece = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n_piece > 0) {
            output.append(buf, static_cast<size_t>(n_piece));
        }

        // Reuse the same batch buffer for a single-token decode.
        batch.token[0] = id;
        batch.pos[0] = n_cur;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = true;
        batch.n_tokens = 1;

        if (llama_decode(e->ctx, batch) != 0) {
            LOGE("nativeComplete: decode failed at gen step %d", generated);
            break;
        }

        n_cur++;
        generated++;
    }

    llama_batch_free(batch);
    llama_sampler_free(sampler_chain);

    if (!is_valid_utf8(output)) {
        while (!output.empty() && (static_cast<unsigned char>(output.back()) & 0x80) != 0) {
            const unsigned char c = static_cast<unsigned char>(output.back());
            output.pop_back();
            if ((c & 0xC0) == 0xC0) break;
        }
    }

    return env->NewStringUTF(output.c_str());
}
