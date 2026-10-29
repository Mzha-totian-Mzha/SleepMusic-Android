package ir.sleepmusic.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("sleep_music", Context.MODE_PRIVATE)
        ActionExecutor.execute(context, prefs.getBoolean("power_menu", true))
    }
}