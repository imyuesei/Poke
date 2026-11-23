package com.monolith.poke.brain

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Poke's brain: the Cactus 4 engine running Needle 3 (understanding: tool calls, extraction,
 * embeddings) and Whistle (ears: speech to text), fully on the phone.
 *
 * The weights are fetched once from Hugging Face at the exact revisions the official
 * cactus-compute 4.1.x client pins, and checked against the same SHA-256 hashes.
 * All engine calls run on one worker thread; the engine is not thread-safe.
 */
object Brain {
    class Model(val name: String, val url: String, val sha256: String, val approxMb: Int)

    val NEEDLE = Model(
        "needle3.cact",
        "https://huggingface.co/Cactus-Compute/needle3/resolve/d6c7d6a2e9655505e0f8e23339356dd1705900eb/needle3.cact",
        "c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38", 35,
    )
    val WHISTLE = Model(
        "whistle.cact",
        "https://huggingface.co/Cactus-Compute/whistle/resolve/88cb00eeb8928ddbcd410b491584f938ba030b07/whistle.cact",
        "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb", 17,
    )
    val MODELS = listOf(NEEDLE, WHISTLE)

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "poke-brain").apply { priority = Thread.NORM_PRIORITY - 1 } }

    /** Whether the native engine is inside this APK. */
    val engineBuilt: Boolean by lazy {
        try {
            System.loadLibrary("pokebrain")
            nativeAvailable()
        } catch (_: Throwable) {
            false
        }
    }

    @Volatile var loaded = false
        private set
    @Volatile var loadMs = 0L
        private set
    private var currentTools: String? = null
    private var currentSystem: String? = null

    fun modelFile(c: Context, m: Model) = File(File(c.filesDir, "models"), m.name)
    fun hasModels(c: Context) = MODELS.all { modelFile(c, it).let { f -> f.exists() && f.length() > 0 } }

    /** Runs [block] on the brain thread and posts the result back through [done]. */
    fun <T> async(block: () -> T, done: (Result<T>) -> Unit) {
        worker.execute {
            val r = runCatching(block)
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(r) }
        }
    }

    // ------------------------------------------------------------------ models

    /** Downloads any missing model, verifying its checksum. Call off the main thread. */
    fun download(c: Context, progress: (String) -> Unit) {
        for (m in MODELS) {
            val f = modelFile(c, m)
            if (f.exists() && f.length() > 0) continue
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, m.name + ".part")
            progress("Downloading ${m.name} (~${m.approxMb} MB)…")
            var url = m.url
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20000
                conn.readTimeout = 60000
                conn.setRequestProperty("User-Agent", "Poke")
                val code = conn.responseCode
                if (code in 300..399 && hops < 5) {
                    // Hugging Face redirects to its CDN, sometimes across hosts
                    url = URL(URL(url), conn.getHeaderField("Location")).toString()
                    conn.disconnect()
                    hops++
                    continue
                }
                if (code != 200) throw IllegalStateException("${m.name}: HTTP $code")
                break
            }
            val total = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done - lastReport > 1_000_000) {
                            lastReport = done
                            val pct = if (total > 0) " ${done * 100 / total}%" else ""
                            progress("Downloading ${m.name}$pct (${done / 1_000_000} MB)")
                        }
                    }
                }
            }
            conn.disconnect()
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != m.sha256) {
                tmp.delete()
                throw IllegalStateException("${m.name}: checksum mismatch")
            }
            if (!tmp.renameTo(f)) throw IllegalStateException("${m.name}: could not save")
        }
        progress("Brain downloaded.")
    }

    // ------------------------------------------------------------------ engine

    /** Loads both models into the engine. Call on the brain thread (via [async]). */
    fun load(c: Context) {
        if (loaded) return
        check(engineBuilt) { "The Cactus engine is not built into this APK." }
        check(hasModels(c)) { "The brain isn't downloaded yet." }
        val t0 = SystemClock.elapsedRealtime()
        for (m in MODELS) {
            val bytes = modelFile(c, m).readBytes()
            if (nativeLoad(bytes) < 0) throw IllegalStateException("${m.name}: ${nativeLastError()}")
        }
        loadMs = SystemClock.elapsedRealtime() - t0
        loaded = true
        currentTools = null
    }

    /** Binds Needle to a toolset (only re-initialises when it changes). */
    private fun bind(system: String, toolsJson: String) {
        if (toolsJson == currentTools && system == currentSystem) return
        if (nativeInit(system, toolsJson) < 0) throw IllegalStateException("init: ${nativeLastError()}")
        currentTools = toolsJson
        currentSystem = system
    }

    class Thought(val raw: JSONObject, val ms: Long) {
        val calls: JSONArray get() = raw.optJSONArray("function_calls") ?: JSONArray()
        val confidence: Double get() = raw.optDouble("confidence", Double.NaN)
        val firstCall: JSONObject? get() = if (calls.length() > 0) calls.getJSONObject(0) else null
    }

    /** One Needle turn over [toolsJson]; conversation state is reset afterwards. */
    fun think(c: Context, text: String, toolsJson: String, system: String): Thought {
        load(c)
        bind(system, toolsJson)
        val t0 = SystemClock.elapsedRealtime()
        val out = nativeComplete(text, 256)
        val ms = SystemClock.elapsedRealtime() - t0
        nativeReset()
        val json = try {
            JSONObject(out)
        } catch (_: Exception) {
            JSONObject().put("success", false).put("error", out)
        }
        return Thought(json, ms)
    }

    class Heard(val text: String, val raw: JSONObject, val ms: Long)

    /** Whistle: 16 kHz mono float samples (up to 30 s) to text. */
    fun hear(c: Context, samples: FloatArray, keywords: String? = null): Heard {
        load(c)
        val t0 = SystemClock.elapsedRealtime()
        val out = nativeTranscribe(samples, "en", keywords)
        val ms = SystemClock.elapsedRealtime() - t0
        val json = try {
            JSONObject(out)
        } catch (_: Exception) {
            JSONObject().put("error", out)
        }
        return Heard(json.optString("text").trim(), json, ms)
    }

    /** Needle 3 text embedding (for memory search), or null if unavailable. */
    fun embed(c: Context, text: String): FloatArray? {
        load(c)
        return nativeEmbed(text)
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until minOf(a.size, b.size)) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na == 0f || nb == 0f) 0f else dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }

    // ------------------------------------------------------------------ JNI

    private external fun nativeAvailable(): Boolean
    private external fun nativeLoad(data: ByteArray): Int
    private external fun nativeLastError(): String
    private external fun nativeSetThreads(n: Int)
    private external fun nativeInit(system: String, toolsJson: String): Int
    private external fun nativeReset()
    private external fun nativeComplete(text: String, maxTokens: Int): String
    private external fun nativeEmbed(text: String): FloatArray?
    private external fun nativeTranscribe(samples: FloatArray, language: String?, keywords: String?): String
}
