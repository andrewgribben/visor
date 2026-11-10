package com.macedonga.visor.notify

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Each chat's reply action, by the thread the glasses answer with, while its notification is up. */
@Singleton
class TextReplies @Inject constructor() {
    private class Entry(val key: String, val action: Notification.Action, val provider: Int, val sender: String)

    private val byThread = ConcurrentHashMap<String, Entry>()

    fun put(thread: String, key: String, action: Notification.Action, provider: Int, sender: String) {
        byThread[thread] = Entry(key, action, provider, sender)
    }

    /** The notification [key] is gone, and its reply with it. */
    fun remove(key: String) {
        byThread.values.removeIf { it.key == key }
    }

    fun has(thread: String) = byThread.containsKey(thread)

    /** The reply action of the notification [key], while it is up. */
    fun byKey(key: String): Notification.Action? = byThread.values.firstOrNull { it.key == key }?.action

    /** The thread and reply action of a conversation on [provider] whose sender is named [name], while it is up. */
    fun findBySender(provider: Int, name: String): Pair<String, Notification.Action>? =
        byThread.entries.firstOrNull { it.value.provider == provider && it.value.sender.equals(name, ignoreCase = true) }
            ?.let { it.key to it.value.action }

    /** Sends [text] in [thread] through the app's reply; null when it went, else why not. */
    fun reply(context: Context, thread: String, text: String): String? {
        val action = byThread[thread]?.action ?: return "No reply in that conversation"
        return send(context, action, text)
    }

    /** Sends [text] through [action]; null when it went, else why not. */
    fun send(context: Context, action: Notification.Action, text: String): String? {
        val inputs = action.remoteInputs ?: return "The app gives no reply field"
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, results)
        return runCatching { action.actionIntent.send(context, 0, fill) }.exceptionOrNull()?.let { it.message ?: it.toString() }
    }
}
