package com.macedonga.visor.phone

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.macedonga.visor.core.assistant.PhoneClock
import com.macedonga.visor.core.assistant.Tar
import com.macedonga.visor.core.assistant.TarType
import java.util.Calendar

/** Timers and one-off alarms set in the phone's clock app too, without its screen: it rings them on the phone and watch. */
class ClockApp(private val context: Context) : PhoneClock {
    override fun set(tar: Tar): Boolean {
        val intent = when (tar.type) {
            TarType.TIMER -> {
                val ms = tar.durationMs ?: return false
                Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, ((ms + 999) / 1000).toInt())
            }
            TarType.ALARM -> {
                // A weekly alarm's days are not read yet: the glasses keep those.
                val at = tar.fireAt?.takeIf { !tar.repeats } ?: return false
                val c = Calendar.getInstance().apply { timeInMillis = at }
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, c.get(Calendar.HOUR_OF_DAY))
                    .putExtra(AlarmClock.EXTRA_MINUTES, c.get(Calendar.MINUTE))
            }
            else -> return false
        }
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tar.title.takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
