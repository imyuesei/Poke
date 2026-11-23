// JNI bridge from Poke (Kotlin) to the Cactus 4 engine C ABI (cactus_*), the same ABI the
// official Python package binds with ctypes. One engine hosts both Needle 3 and Whistle.
#include <jni.h>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#ifdef POKE_HAS_CACTUS
extern "C" {
int cactus_load(const char* data, uint64_t size);
int cactus_models();
const char* cactus_last_error();
int cactus_init(const char* system, const char* tools_json, const char* tool_index_path);
int cactus_complete(const char* text, const float* samples, int count, int max_new_tokens,
                    char* out, int out_size);
void cactus_set_audio(const char* language, const char* keywords, int word_timestamps);
void cactus_set_threads(int threads);
void cactus_reset();
int cactus_embed(const char* text, const float* samples, int count, float* out, int dim);
int cactus_transcribe(const float* samples, int count, const char* language, const char* keywords,
                      int word_timestamps, char* out, int out_size);
}
#endif

static std::mutex g_lock;

namespace {
struct Utf {
    JNIEnv* env; jstring s; const char* c;
    Utf(JNIEnv* e, jstring str) : env(e), s(str), c(str ? e->GetStringUTFChars(str, nullptr) : nullptr) {}
    ~Utf() { if (c) env->ReleaseStringUTFChars(s, c); }
};

jstring str(JNIEnv* env, const char* c) { return env->NewStringUTF(c ? c : ""); }
}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_monolith_poke_brain_Brain_nativeAvailable(JNIEnv*, jobject) {
#ifdef POKE_HAS_CACTUS
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jint JNICALL Java_com_monolith_poke_brain_Brain_nativeLoad(JNIEnv* env, jobject, jbyteArray data) {
#ifdef POKE_HAS_CACTUS
    std::lock_guard<std::mutex> g(g_lock);
    jsize n = env->GetArrayLength(data);
    jbyte* p = env->GetByteArrayElements(data, nullptr);
    int rc = cactus_load(reinterpret_cast<const char*>(p), static_cast<uint64_t>(n));
    env->ReleaseByteArrayElements(data, p, JNI_ABORT);
    return rc;
#else
    return -1;
#endif
}

JNIEXPORT jstring JNICALL Java_com_monolith_poke_brain_Brain_nativeLastError(JNIEnv* env, jobject) {
#ifdef POKE_HAS_CACTUS
    return str(env, cactus_last_error());
#else
    return str(env, "engine not built into this APK");
#endif
}

JNIEXPORT void JNICALL Java_com_monolith_poke_brain_Brain_nativeSetThreads(JNIEnv*, jobject, jint n) {
#ifdef POKE_HAS_CACTUS
    std::lock_guard<std::mutex> g(g_lock);
    cactus_set_threads(n);
#endif
}

JNIEXPORT jint JNICALL Java_com_monolith_poke_brain_Brain_nativeInit(JNIEnv* env, jobject, jstring system, jstring tools) {
#ifdef POKE_HAS_CACTUS
    std::lock_guard<std::mutex> g(g_lock);
    Utf s(env, system), t(env, tools);
    return cactus_init(s.c ? s.c : "", t.c ? t.c : "[]", nullptr);
#else
    return -1;
#endif
}

JNIEXPORT void JNICALL Java_com_monolith_poke_brain_Brain_nativeReset(JNIEnv*, jobject) {
#ifdef POKE_HAS_CACTUS
    std::lock_guard<std::mutex> g(g_lock);
    cactus_reset();
#endif
}

JNIEXPORT jstring JNICALL Java_com_monolith_poke_brain_Brain_nativeComplete(JNIEnv* env, jobject, jstring text, jint maxTokens) {
#ifdef POKE_HAS_CACTUS
    std::lock_guard<std::mutex> g(g_lock);
    Utf t(env, text);
    std::vector<char> out(1 << 16, 0);
    int rc = cactus_complete(t.c ? t.c : "", nullptr, 0, maxTokens, out.data(), static_cast<int>(out.size()));
    if (rc < 0) {
        const char* e = cactus_last_error();
        std::string msg = out[0] ? std::string(out.data()) : std::string(e ? e : "complete failed");
        std::string safe;
        for (char c : msg) { if (c == '"' || c == '\\') safe += '\\'; if (c != '\n') safe += c; }
        return str(env, ("{\"success\":false,\"error\":\"" + safe + "\"}").c_str());
    }
    return str(env, out.data());
#else
    return str(env, "{\"success\":false,\"error\":\"engine not built into this APK\"}");
#endif
}

JNIEXPORT jfloatArray JNICALL Java_com_monolith_poke_brain_Brain_nativeEmbed(JNIEnv* env, jobject, jstring text) {
#ifdef POKE_HAS_CACTUS
    std::lock_guard<std::mutex> g(g_lock);
    Utf t(env, text);
    int dim = cactus_embed(t.c ? t.c : "", nullptr, 0, nullptr, 0);
    if (dim <= 0) return nullptr;
    std::vector<float> v(dim);
    int rc = cactus_embed(t.c ? t.c : "", nullptr, 0, v.data(), dim);
    if (rc != dim) return nullptr;
    jfloatArray arr = env->NewFloatArray(dim);
    env->SetFloatArrayRegion(arr, 0, dim, v.data());
    return arr;
#else
    return nullptr;
#endif
}

JNIEXPORT jstring JNICALL Java_com_monolith_poke_brain_Brain_nativeTranscribe(JNIEnv* env, jobject, jfloatArray samples,
                                                                            jstring language, jstring keywords) {
#ifdef POKE_HAS_CACTUS
    std::lock_guard<std::mutex> g(g_lock);
    Utf lang(env, language), kw(env, keywords);
    jsize n = env->GetArrayLength(samples);
    jfloat* p = env->GetFloatArrayElements(samples, nullptr);
    // the Python client sizes this from the clip length; 256 KB covers 30 s comfortably
    std::vector<char> out(1 << 18, 0);
    int rc = cactus_transcribe(p, n, lang.c, kw.c, 0, out.data(), static_cast<int>(out.size()));
    env->ReleaseFloatArrayElements(samples, p, JNI_ABORT);
    if (rc < 0) {
        const char* e = cactus_last_error();
        std::string msg(e ? e : "transcribe failed"), safe;
        for (char c : msg) { if (c == '"' || c == '\\') safe += '\\'; if (c != '\n') safe += c; }
        return str(env, ("{\"error\":\"" + safe + "\"}").c_str());
    }
    return str(env, out.data());
#else
    return str(env, "{\"error\":\"engine not built into this APK\"}");
#endif
}

}  // extern "C"
