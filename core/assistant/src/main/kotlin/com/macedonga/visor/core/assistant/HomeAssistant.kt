package com.macedonga.visor.core.assistant

import com.macedonga.visor.core.assistant.mcp.ToolAction
import com.macedonga.visor.core.assistant.mcp.ToolResult
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Home Assistant's own Assist agent, over its REST API: not MCP, the same conversation a person types into HA. */

/** One answer from Assist: [text] to speak, [conversationId] to continue it, or [failure] when it did not answer. */
data class HaReply(val text: String, val conversationId: String? = null, val failure: String? = null)

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
        try {
            val answer = haPost("${url.trimEnd('/')}/api/conversation/process", token, body)
            val response = answer.optJSONObject("response")
            val speech = response?.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech")?.ifBlank { null }
            val newId = answer.optString("conversation_id").ifEmpty { null }
            if (speech == null) {
                val code = response?.optJSONObject("data")?.optString("code").orEmpty()
                HaReply("", newId, failure = "no answer from Home Assistant" + code.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty())
            } else HaReply(speech, newId)
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
