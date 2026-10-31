package ir.sleepmusic.app

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * پاپ‌آپ «عکس ۱۰» به‌صورت روکش سیستم (System Overlay):
 * تا حالا پاپ‌آپ فقط وقتی می‌توانست بیاید که اپ جلوی چشم باشد — چون دیالوگِ اکتیویتی است
 * و اندروید اجازه نمی‌دهد اکتیویتیِ بسته، پنجره باز کند. برای همین در همه‌ی تست‌های
 * «اپ بسته / صفحه خاموش» فقط اعلان می‌آمد.
 * حالا همین طرح با اجازه‌ی «نمایش روی سایر برنامه‌ها» به‌صورت روکش سیستم نشان داده می‌شود:
 * حتی با اپ بسته و روی قفل صفحه، و صفحه را هم روشن می‌کند تا حتماً دیده شود.
 * اگر اجازه داده نشده باشد، اپ به همان اعلان + دیالوگ داخلی برمی‌گردد (هیچ چیزی حذف نمی‌شود).
 */
object AlertOverlay {

    private var view: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var endRealtime = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    val isShowing: Boolean
        get() = view != null

    fun canShow(context: Context): Boolean {
        return try {
            android.provider.Settings.canDrawOverlays(context)
        } catch (_: Exception) {
            false
        }
    }

    fun show(context: Context, remainingMs: Long) {
        val ctx = context.applicationContext
        if (!canShow(ctx)) return
        if (view != null) {
            // همین الان باز است؛ فقط شمارش را تازه کن
            endRealtime = SystemClock.elapsedRealtime() + remainingMs
            return
        }
        try {
            val prefs = ctx.getSharedPreferences("sleep_music", Context.MODE_PRIVATE)
            val t = ThemeRepo.byId(prefs.getString("theme_id", "blend"))
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_alert, null)

            val root = v.findViewById<LinearLayout>(R.id.dlgAlertRoot)
            root.background = roundRect(t.cardBg, dp(ctx, 22f))
            val title = v.findViewById<TextView>(R.id.dlgAlertTitle)
            title.setTextColor(t.textPrimary)
            title.textSize = 17f
            title.typeface = Typeface.DEFAULT_BOLD
            val sub = v.findViewById<TextView>(R.id.dlgAlertSub)
            sub.setTextColor(t.textMuted)

            val cancel = v.findViewById<TextView>(R.id.btnAlertCancel)
            cancel.background = gradBtn(t.btnColors, dp(ctx, 14f), dp(ctx, 2f), t.textFaint)
            cancel.setOnClickListener {
                hide()
                try {
                    ctx.startService(Intent(ctx, MusicService::class.java).setAction(MusicService.ACTION_CANCEL_SHUTDOWN))
                } catch (_: Exception) { }
            }
            val now = v.findViewById<TextView>(R.id.btnAlertNow)
            now.setTextColor(t.textMuted)
            now.setOnClickListener {
                hide()
                try {
                    ctx.startService(Intent(ctx, MusicService::class.java).setAction(MusicService.ACTION_SHUTDOWN_NOW))
                } catch (_: Exception) { }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.CENTER
            params.horizontalMargin = 0f
            params.x = 0
            params.y = 0

            endRealtime = SystemClock.elapsedRealtime() + remainingMs
            wm.addView(v, params)
            view = v

            // صفحه خاموش است؟ روشن کنیم تا پاپ‌آپ دیده شود (مثل آلارم‌ها)
            try {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "SleepMusic:AlertPopup"
                ).apply { acquire(14000) }
            } catch (_: Exception) { }

            startCountdown(wm, v, title, v.findViewById(R.id.alertBar))
        } catch (_: Exception) {
            view = null
        }
    }

    private fun startCountdown(wm: WindowManager, v: View, title: TextView, bar: View?) {
        bar?.scaleX = 1f
        // نوار از دو طرف به‌طور متقارن به وسط کم می‌شود (pivot پیش‌فرض = وسط نوار)
        val runnable = object : Runnable {
            override fun run() {
                if (view == null || v !== view) return
                val remaining = (endRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                val secs = ((remaining + 999) / 1000).toInt()
                title.text = ThemeRepo.faDigits("" + secs) + " ثانیه تا خاموش شدن"
                bar?.scaleX = (remaining / 10000f).coerceIn(0f, 1f)
                if (remaining <= 0) {
                    hide()
                    return
                }
                handler.postDelayed(this, 200)
            }
        }
        handler.postDelayed(runnable, 200)
    }

    fun hide() {
        val v = view ?: return
        view = null
        try {
            val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(v)
        } catch (_: Exception) { }
        try { wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
    }

    /** یادآوری یک‌باره‌ی اجازه‌ی «نمایش روی سایر برنامه‌ها» */
    fun askPermission(activity: android.app.Activity) {
        try {
            Toast.makeText(activity, "برای دیدن پاپ‌آپ خاموشی حتی روی قفل صفحه، اجازه‌ی نمایش را بده", Toast.LENGTH_LONG).show()
            activity.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + activity.packageName)
                )
            )
        } catch (_: Exception) { }
    }

    /* ---------- ابزارهای کوچک رسم ---------- */

    private fun dp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun roundRect(color: Int, radiusPx: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(color)
        }

    private fun gradBtn(colors: IntArray, radiusPx: Int, strokePx: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setStroke(strokePx, strokeColor)
        }
}
