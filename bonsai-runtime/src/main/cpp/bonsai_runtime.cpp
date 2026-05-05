#include <android/log.h>
#include <jni.h>
#include <sampling.h>
#include <unistd.h>

#include <atomic>
#include <cmath>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "logging.h"

constexpr int N_THREADS_MIN = 2;
constexpr int N_THREADS_MAX = 4;
constexpr int N_THREADS_HEADROOM = 2;
constexpr int DEFAULT_CONTEXT_SIZE = 4096;
constexpr int OVERFLOW_HEADROOM = 4;
constexpr int BATCH_SIZE = 512;
constexpr float DEFAULT_SAMPLER_TEMP = 0.5f;
constexpr int DEFAULT_SAMPLER_TOP_K = 20;
constexpr float DEFAULT_SAMPLER_TOP_P = 0.9f;

static llama_model *g_model = nullptr;
static llama_context *g_context = nullptr;
static llama_batch g_batch = {};
static common_chat_templates_ptr g_chat_templates;
static common_sampler *g_sampler = nullptr;
static std::atomic<bool> g_stop_flag{false};

constexpr const char *ROLE_SYSTEM = "system";
constexpr const char *ROLE_USER = "user";
constexpr const char *ROLE_ASSISTANT = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position = 0;
static llama_pos current_position = 0;
static llama_pos stop_generation_position = 0;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;

static void reset_long_term_states(bool clear_kv_cache = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;

    if (clear_kv_cache && g_context != nullptr) {
        llama_memory_clear(llama_get_memory(g_context), false);
    }
}

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
    assistant_ss.clear();
}

static void free_resources() {
    if (g_sampler != nullptr) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    g_chat_templates.reset();
    if (g_batch.token != nullptr) {
        llama_batch_free(g_batch);
        g_batch = {};
    }
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    reset_long_term_states(false);
    reset_short_term_states();
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    auto formatted = common_chat_format_single(
        g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER, false);
    chat_msgs.push_back(new_msg);
    return formatted;
}

static llama_context *init_context(llama_model *model, int n_ctx = DEFAULT_CONTEXT_SIZE) {
    if (model == nullptr) {
        return nullptr;
    }

    const int n_threads = std::max(
        N_THREADS_MIN,
        std::min(N_THREADS_MAX, static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN)) - N_THREADS_HEADROOM)
    );

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    return llama_init_from_model(model, ctx_params);
}

static common_sampler *new_sampler() {
    common_params_sampling sparams;
    sparams.temp = DEFAULT_SAMPLER_TEMP;
    sparams.top_k = DEFAULT_SAMPLER_TOP_K;
    sparams.top_p = DEFAULT_SAMPLER_TOP_P;
    return common_sampler_init(g_model, sparams);
}

static void shift_context() {
    const int n_discard = (current_position - system_prompt_position) / 2;
    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
}

static int decode_tokens_in_batches(
    llama_context *context,
    llama_batch &batch,
    const llama_tokens &tokens,
    llama_pos start_pos,
    bool compute_last_logit = false
) {
    for (int i = 0; i < static_cast<int>(tokens.size()); i += BATCH_SIZE) {
        const int cur_batch_size = std::min(static_cast<int>(tokens.size()) - i, BATCH_SIZE);
        common_batch_clear(batch);

        if (start_pos + i + cur_batch_size >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
            shift_context();
        }

        for (int j = 0; j < cur_batch_size; ++j) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == static_cast<int>(tokens.size()) - 1);
            common_batch_add(batch, token_id, position, {0}, want_logit);
        }

        if (llama_decode(context, batch) != 0) {
            return 1;
        }
    }

    return 0;
}

static bool is_valid_utf8(const char *string) {
    if (string == nullptr) {
        return true;
    }

    const auto *bytes = reinterpret_cast<const unsigned char *>(string);
    while (*bytes != 0x00) {
        int num = 0;
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_xtrust_bonsai_runtime_BonsaiNativeBridge_init(JNIEnv *env, jobject, jstring nativeLibDir) {
    llama_log_set([](ggml_log_level level, const char *text, void *) {
        if (text == nullptr) {
            return;
        }
        switch (level) {
            case GGML_LOG_LEVEL_ERROR: LOGE("%s", text); break;
            case GGML_LOG_LEVEL_WARN: LOGW("%s", text); break;
            case GGML_LOG_LEVEL_INFO: LOGI("%s", text); break;
            default: LOGD("%s", text); break;
        }
    }, nullptr);

    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, nullptr);
    LOGI("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);
    llama_backend_init();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_xtrust_bonsai_runtime_BonsaiNativeBridge_load(JNIEnv *env, jobject, jstring jmodel_path) {
    llama_model_params model_params = llama_model_default_params();
    const auto *model_path = env->GetStringUTFChars(jmodel_path, nullptr);
    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);

    if (model == nullptr) {
        return 1;
    }

    g_model = model;
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_xtrust_bonsai_runtime_BonsaiNativeBridge_prepare(JNIEnv *, jobject) {
    g_context = init_context(g_model);
    if (g_context == nullptr) {
        return 1;
    }

    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler();
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_xtrust_bonsai_runtime_BonsaiNativeBridge_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_xtrust_bonsai_runtime_BonsaiNativeBridge_benchModel(JNIEnv *env, jobject, jint pp, jint tg, jint pl, jint nr) {
    auto *context = init_context(g_model, pp);
    if (context == nullptr) {
        return env->NewStringUTF("Failed to initialize context");
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    for (int run = 0; run < nr; ++run) {
        common_batch_clear(g_batch);
        for (int i = 0; i < pp; ++i) {
            common_batch_add(g_batch, 0, i, {0}, false);
        }
        g_batch.logits[g_batch.n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        llama_decode(context, g_batch);
        const auto t_pp_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (int i = 0; i < tg; ++i) {
            common_batch_clear(g_batch);
            for (int j = 0; j < pl; ++j) {
                common_batch_add(g_batch, 0, i, {j}, true);
            }
            llama_decode(context, g_batch);
        }
        const auto t_tg_end = ggml_time_us();

        const auto speed_pp = static_cast<double>(pp) / (static_cast<double>(t_pp_end - t_pp_start) / 1000000.0);
        const auto speed_tg = static_cast<double>(pl * tg) / (static_cast<double>(t_tg_end - t_tg_start) / 1000000.0);
        pp_avg += speed_pp;
        tg_avg += speed_tg;
        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;
    }

    llama_free(context);

    pp_avg /= static_cast<double>(nr);
    tg_avg /= static_cast<double>(nr);
    if (nr > 1) {
        pp_std = sqrt(pp_std / static_cast<double>(nr - 1) - pp_avg * pp_avg * static_cast<double>(nr) / static_cast<double>(nr - 1));
        tg_std = sqrt(tg_std / static_cast<double>(nr - 1) - tg_avg * tg_avg * static_cast<double>(nr) / static_cast<double>(nr - 1));
    } else {
        pp_std = 0.0;
        tg_std = 0.0;
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | test | t/s |\n";
    result << "| --- | --- | --- |\n";
    result << "| " << model_desc << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_xtrust_bonsai_runtime_BonsaiNativeBridge_processSystemPrompt(JNIEnv *env, jobject, jstring jsystem_prompt) {
    reset_long_term_states();
    reset_short_term_states();

    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    std::string formatted_system_prompt(system_prompt);
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt, has_chat_template, has_chat_template);
    if (static_cast<int>(system_tokens.size()) > DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        return 1;
    }
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position) != 0) {
        return 2;
    }

    system_prompt_position = current_position = static_cast<llama_pos>(system_tokens.size());
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_xtrust_bonsai_runtime_BonsaiNativeBridge_processUserPrompt(JNIEnv *env, jobject, jstring juser_prompt, jint n_predict) {
    g_stop_flag.store(false);
    reset_short_term_states();

    const auto *user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    std::string formatted_user_prompt(user_prompt);
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, has_chat_template, has_chat_template);
    if (static_cast<int>(user_tokens.size()) > DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        user_tokens.resize(DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM);
    }
    const int user_prompt_size = static_cast<int>(user_tokens.size());

    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true) != 0) {
        return 2;
    }

    current_position += user_prompt_size;
    stop_generation_position = current_position + n_predict;
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_xtrust_bonsai_runtime_BonsaiNativeBridge_generateNextToken(JNIEnv *env, jobject) {
    if (g_stop_flag.load()) {
        return nullptr;
    }
    if (current_position >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        shift_context();
    }
    if (current_position >= stop_generation_position) {
        if (!assistant_ss.str().empty()) {
            chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        }
        return nullptr;
    }

    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        return nullptr;
    }

    current_position++;
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }

    auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    cached_token_chars += new_token_chars;
    if (!is_valid_utf8(cached_token_chars.c_str())) {
        return env->NewStringUTF("");
    }

    assistant_ss << cached_token_chars;
    jstring result = env->NewStringUTF(cached_token_chars.c_str());
    cached_token_chars.clear();
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_xtrust_bonsai_runtime_BonsaiNativeBridge_cancelGeneration(JNIEnv *env, jobject obj) {
    g_stop_flag.store(true);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_xtrust_bonsai_runtime_BonsaiNativeBridge_unload(JNIEnv *, jobject) {
    free_resources();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_xtrust_bonsai_runtime_BonsaiNativeBridge_shutdown(JNIEnv *, jobject) {
    free_resources();
    llama_backend_free();
}
