package com.macedonga.visor.core.assistant

/**
 * Who a question can name at its start ("ask Claude ...", "tell ChatGPT ..."): [provider] answers in the glasses
 * when it has a key; [appLabel] is the phone app's own name, for the "open the app" handoff, when it has one.
 * Perplexity has no Visor provider, so it can only be reached by opening its app.
 */
data class AskedProvider(val label: String, val provider: Provider?, val appLabel: String?)

/** Whether "ask Claude/ChatGPT/Perplexity ..." answers in the glasses with that provider, or opens its phone app. */
enum class AskHandoff { ANSWER_IN_GLASSES, OPEN_APP }

/** The names a question may address at its start, each to the provider (for an answer) and/or app (for a handoff). */
private val ASKABLE: Map<String, AskedProvider> = mapOf(
    "claude" to AskedProvider("Claude", Provider.CLAUDE, "Claude"),
    "chatgpt" to AskedProvider("ChatGPT", Provider.CHATGPT, "ChatGPT"),
    "chat gpt" to AskedProvider("ChatGPT", Provider.CHATGPT, "ChatGPT"),
    "gemini" to AskedProvider("Gemini", Provider.GEMINI, null),
    "grok" to AskedProvider("Grok", Provider.GROK, null),
    "openrouter" to AskedProvider("OpenRouter", Provider.OPENROUTER, null),
    "open router" to AskedProvider("OpenRouter", Provider.OPENROUTER, null),
    "groq" to AskedProvider("Groq", Provider.GROQ, null),
    "openai" to AskedProvider("OpenAI", Provider.OPENAI, null),
    "open ai" to AskedProvider("OpenAI", Provider.OPENAI, null),
    "perplexity" to AskedProvider("Perplexity", null, "Perplexity"),
)

// "ask/tell <name> (to) ..." at the start of a question. Longer names first, so "chat gpt" wins over a bare "chat".
private val ASK_ROUTE = Regex(
    "^(?:ask|tell)\\s+(" + ASKABLE.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")\\b(?:\\s+to\\b)?[,:]?\\s*",
    RegexOption.IGNORE_CASE,
)

/** The provider or app [transcript] (with its wake word already gone) names at its start, or null for none. */
fun askRouted(transcript: String): AskedProvider? = ASK_ROUTE.find(transcript)?.let { ASKABLE[it.groupValues[1].lowercase()] }
