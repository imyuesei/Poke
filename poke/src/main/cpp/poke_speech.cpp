// JNI bridge to eSpeak NG: fast, fully offline speech for Poke's caveman words.
#include <jni.h>
#include <mutex>
#include <string>
#include <vector>

#ifdef POKE_HAS_ESPEAK
#include <espeak-ng/speak_lib.h>
#endif

static std::mutex g_speech;
static std::vector<short> g_pcm;
static int g_rate = 0;

#ifdef POKE_HAS_ESPEAK
static int collect(short* wav, int n, espeak_EVENT*) {
    if (wav && n > 0) g_pcm.insert(g_pcm.end(), wav, wav + n);
    return 0;  // keep going
}
#endif

extern "C" {

JNIEXPORT jint JNICALL Java_com_monolith_poke_Speech_nativeInit(JNIEnv* env, jobject, jstring dataDir) {
#ifdef POKE_HAS_ESPEAK
    std::lock_guard<std::mutex> g(g_speech);
    if (g_rate > 0) return g_rate;
    const char* path = env->GetStringUTFChars(dataDir, nullptr);
    // espeak looks for <path>/espeak-ng-data
    int rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 200, path, 0);
    env->ReleaseStringUTFChars(dataDir, path);
    if (rate <= 0) return -1;
    espeak_SetSynthCallback(collect);
    g_rate = rate;
    return rate;
#else
    return -1;
#endif
}

JNIEXPORT jshortArray JNICALL Java_com_monolith_poke_Speech_nativeSynth(JNIEnv* env, jobject, jstring text, jstring voice,
                                                                      jint rate, jint pitch, jint range) {
#ifdef POKE_HAS_ESPEAK
    std::lock_guard<std::mutex> g(g_speech);
    if (g_rate <= 0) return nullptr;
    const char* v = env->GetStringUTFChars(voice, nullptr);
    espeak_SetVoiceByName(v);
    env->ReleaseStringUTFChars(voice, v);
    espeak_SetParameter(espeakRATE, rate, 0);
    espeak_SetParameter(espeakPITCH, pitch, 0);
    espeak_SetParameter(espeakRANGE, range, 0);
    const char* t = env->GetStringUTFChars(text, nullptr);
    std::string s(t);
    env->ReleaseStringUTFChars(text, t);
    g_pcm.clear();
    espeak_Synth(s.c_str(), s.size() + 1, 0, POS_CHARACTER, 0, espeakCHARS_UTF8, nullptr, nullptr);
    espeak_Synchronize();
    jshortArray out = env->NewShortArray(static_cast<jsize>(g_pcm.size()));
    if (!g_pcm.empty()) env->SetShortArrayRegion(out, 0, static_cast<jsize>(g_pcm.size()), g_pcm.data());
    return out;
#else
    return nullptr;
#endif
}

}  // extern "C"
