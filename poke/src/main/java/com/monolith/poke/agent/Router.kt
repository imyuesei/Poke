package com.monolith.poke.agent

import org.json.JSONObject
import java.util.Locale

/**
 * A few precise patterns that run before Needle, for the harness's own core intents (memory).
 * Needle 3 is tiny: given "my name is Aftab and I live in Jorhat" and a get_weather tool, it
 * may grab the place as a weather location. These sentences are too important to leave to
 * chance, and the patterns are unambiguous, so they go straight to remember / recall.
 * Everything else goes to Needle.
 */
object Router {
    private fun call(name: String, args: JSONObject) = JSONObject().put("name", name).put("arguments", args)

    fun route(text: String): JSONObject? {
        val t = text.trim().trimEnd('.', '!')
        val low = t.lowercase(Locale.US)
        // questions about the user or what they said: recall
        if (Regex("^(what(?:'s| is| was)\\s+my\\b|where do i (live|work)|who am i|what do i do|when is my|" +
                "do you (remember|know)\\b|what did i (tell|say)|what have i told)").containsMatchIn(low)) {
            return call("recall", JSONObject().put("query", t.trimEnd('?')))
        }
        if (low.endsWith("?")) return null
        // statements about the user: remember
        if (Regex("^(my name is|my name's|i am called|i'm called|call me|i live in|i'm from|i am from|i work (at|as|in)|" +
                "my (birthday|job|wife|husband|partner|mom|mum|dad|sister|brother|son|daughter|friend|address|favou?rite)\\b|" +
                "remember( that)?\\b)").containsMatchIn(low)) {
            val fact = t.replace(Regex("(?i)^remember( that)?\\s+"), "")
            return call("remember", JSONObject().put("fact", fact))
        }
        return null
    }
}
