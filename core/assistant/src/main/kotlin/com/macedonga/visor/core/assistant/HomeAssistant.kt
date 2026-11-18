package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.assistant.mcp.ToolAction
import com.macedonga.visor.core.assistant.mcp.ToolResult
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/** Home Assistant's own Assist agent, over its REST API: not MCP, the same conversation a person types into HA. */

/**
 * One answer from Assist: [text] to speak, [conversationId] to continue it, or [failure] when it did not answer,
 * with Assist's own [code] for it, e.g. "no_intent_match" when it did not understand the request at all.
 */
data class HaReply(val text: String, val conversationId: String? = null, val failure: String? = null, val code: String? = null, val type: String? = null)

/** Whether Home Assistant answers at this address and token, for the settings screen. */
sealed interface HaStatus {
    data object Works : HaStatus
    data object Refused : HaStatus
    data class Failed(val words: String) : HaStatus
}

/**
 * Asks Home Assistant's Assist agent [text] in [language] (a language code, e.g. "en"), continuing [conversationId] when one is
 * given. Never throws: a failure to reach or understand Home Assistant comes back as [HaReply.failure].
 */
suspend fun haConverse(url: String, token: String, text: String, language: String, conversationId: String? = null): HaReply =
    withContext(Dispatchers.IO) {
        val body = JSONObject().put("text", text).put("language", language.ifBlank { "en" })
        conversationId?.let { body.put("conversation_id", it) }
        preferredAgentId(url, token)?.let { body.put("agent_id", it) }
        try {
            val answer = haPost("${url.trimEnd('/')}/api/conversation/process", token, body)
            val response = answer.optJSONObject("response")
            val speech = response?.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech")?.ifBlank { null }
            val newId = answer.optString("conversation_id").ifEmpty { null }
            // An error answer still carries speech ("Sorry, I couldn't understand that"); its code says which error.
            val code = response?.takeIf { it.optString("response_type") == "error" }?.optJSONObject("data")?.optString("code")?.ifEmpty { null }
            if (speech == null) {
                HaReply("", newId, failure = "no answer from Home Assistant" + code?.let { " ($it)" }.orEmpty(), code = code)
            } else HaReply(speech, newId, code = code, type = response?.optString("response_type"))
        } catch (error: Exception) {
            HaReply("", null, failure = error.message ?: "Home Assistant did not answer")
        }
    }

/** GET /api/, which answers {"message":"API running."} with a working token: the settings screen's own test. */
suspend fun haStatus(url: String, token: String): HaStatus = withContext(Dispatchers.IO) {
    try {
        val http = URL("${url.trimEnd('/')}/api/").openConnection() as HttpURLConnection
        try {
            http.connectTimeout = 5_000
            http.readTimeout = 10_000
            http.setRequestProperty("Authorization", "Bearer $token")
            val code = http.responseCode
            when {
                code == 401 || code == 403 -> HaStatus.Refused
                code in 200..299 -> HaStatus.Works
                else -> HaStatus.Failed("HTTP $code")
            }
        } finally {
            http.disconnect()
        }
    } catch (error: Exception) {
        HaStatus.Failed(error.message ?: error.javaClass.simpleName)
    }
}

private fun haPost(url: String, token: String, body: JSONObject): JSONObject {
    val http = URL(url).openConnection() as HttpURLConnection
    try {
        http.requestMethod = "POST"
        http.connectTimeout = 5_000
        http.readTimeout = 10_000
        http.doOutput = true
        http.setRequestProperty("content-type", "application/json")
        http.setRequestProperty("Authorization", "Bearer $token")
        http.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = http.responseCode
        val text = (if (code in 200..299) http.inputStream else http.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        if (code !in 200..299) error("Home Assistant answered $code: ${text.take(200)}")
        return JSONObject(text)
    } finally {
        http.disconnect()
    }
}

// The preferred Assist pipeline's agent, by (url, token): HA's built-in agent ignores the pipeline the person
// chose in Settings > Voice assistants, so without this a Claude/OpenAI pipeline never sees the request. A failed
// discovery is retried after a few minutes rather than every call, so one offline moment doesn't cost 5s each turn.
private data class AgentCacheEntry(val agentId: String?, val atMs: Long, val failed: Boolean)
private val agentCache = mutableMapOf<String, AgentCacheEntry>()
private const val AGENT_FAIL_RETRY_MS = 3 * 60_000L
private val haWsClient by lazy {
    OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
}

/** The preferred pipeline's conversation_engine (its agent_id), cached per (url, token); null when unknown. */
private suspend fun preferredAgentId(url: String, token: String): String? {
    val key = "$url|$token"
    val cached = agentCache[key]
    val stale = cached == null || (cached.failed && System.currentTimeMillis() - cached.atMs > AGENT_FAIL_RETRY_MS)
    if (!stale) return cached?.agentId
    val agentId = withTimeoutOrNull(5_000) { runCatching { discoverPreferredAgentId(url, token) }.getOrNull() }
    agentCache[key] = AgentCacheEntry(agentId, System.currentTimeMillis(), failed = agentId == null)
    return agentId
}

/** ws(s)://<host>/api/websocket for the http(s) [url], keeping any path prefix. */
private fun haWsUrl(url: String): String {
    val trimmed = url.trimEnd('/')
    val swapped = when {
        trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
        trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
        else -> trimmed
    }
    return "$swapped/api/websocket"
}

/**
 * Asks Home Assistant's websocket API for assist_pipeline/pipeline/list and returns the preferred pipeline's
 * conversation_engine (e.g. "conversation.claude"), or null when it could not be found. Never throws.
 */
private suspend fun discoverPreferredAgentId(url: String, token: String): String? {
    val result = CompletableDeferred<String?>()
    val socket = haWsClient.newWebSocket(
        Request.Builder().url(haWsUrl(url)).build(),
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
                    "auth_ok" -> webSocket.send(JSONObject().put("id", 1).put("type", "assist_pipeline/pipeline/list").toString())
                    "auth_invalid" -> result.complete(null)
                    "result" -> {
                        if (msg.optInt("id") == 1) {
                            val listResult = msg.optJSONObject("result")
                            val preferredId = listResult?.optString("preferred_pipeline")
                            val pipelines = listResult?.optJSONArray("pipelines") ?: JSONArray()
                            val preferred = (0 until pipelines.length()).map { pipelines.getJSONObject(it) }
                                .firstOrNull { it.optString("id") == preferredId }
                            result.complete(preferred?.optString("conversation_engine")?.ifBlank { null })
                        }
                    }
                }
                if (result.isCompleted) webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!result.isCompleted) result.complete(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!result.isCompleted) result.complete(null)
            }
        },
    )
    return try {
        result.await()
    } finally {
        socket.cancel()
    }
}

/** The home_assistant tool for the model: offered only once Home Assistant's address and token are both set. */
internal class HomeAssistantTools {

    val prompt = " For any smart home request, such as lights, switches, heating, locks, scenes or sensors, use " +
        "home_assistant with the wearer's own words; say back exactly what it answers, without adding to it."

    val tool: Triple<String, String, JSONObject> = Triple(
        "home_assistant",
        "Ask the home's Home Assistant Assist agent to carry out or answer a smart home request: lights, switches, " +
            "heating, locks, scenes, sensors or any other connected device. Pass the wearer's request in their own " +
            "words. Relay its answer to the wearer as given; never claim it worked when the tool says it did not.",
        JSONObject().put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "command",
                    JSONObject().put("type", "string")
                        .put("description", "The wearer's request, in their own words, e.g. \"turn off the kitchen lights\"."),
                ),
            )
            .put("required", JSONArray().put("command")),
    )

    /** Runs the tool when [name] is it and Home Assistant is set up in [c]; null otherwise. */
    suspend fun run(name: String, input: JSONObject, c: AssistantConfig, language: String): Pair<ToolResult, ToolAction?>? {
        if (name != "home_assistant" || !c.hasHa) return null
        val command = input.optString("command").trim()
        if (command.isEmpty()) return ToolResult("no command", true) to null
        val reply = haConverse(c.haUrl, c.haToken, command, language)
        val action = ToolAction("Home Assistant", command, reply.failure == null, kind = "home_assistant", subject = command)
        return if (reply.failure != null) ToolResult("Home Assistant did not answer: ${reply.failure}", true) to action
        else ToolResult(reply.text, false) to action
    }
}
