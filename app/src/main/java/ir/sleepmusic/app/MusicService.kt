package ir.sleepmusic.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * سرویس پخش موسیقی:
 * - پخش تک‌آهنگ یا پلی‌لیست (آهنگ‌ها به ترتیب؛ بعد از آخرین‌شان خاموشی)
 * - مکث/ادامه: در مکث، چیپ «زمان خاموش شدن» فریز می‌شود و زنجیره‌ی خاموشی منتظر می‌ماند
 * - محو شدن صدا در ۱۰ ثانیه‌ی آخر + خبر دادن به صفحه اصلی برای هشدار ۱۰ ثانیه‌ای
 * - لغو خاموشی فقط همین اجرا را لغو می‌کند؛ زنجیره‌ی خاموشی (ActionExecutor) دست‌نخورده است
 * - «توقف کامل» دیگر آمار شب‌ها را صفر نمی‌کند (درخواست کاربر)
 * نکته‌ی مهم فنی: فایل با FileDescriptor از ContentResolver باز می‌شود تا خطای -38 روی
 * مسیرهای فارسی/دارای فضاخالی هرگز تکرار نشود (همان روش پایدار نسخه‌ی ۱.۱).
 */
class MusicService : Service() {

    private var player: MediaPlayer? = null
    private val tickHandler = Handler(Looper.getMainLooper())
    private var retryCount = 0

    // لیست پلی‌لیست فعلی (آدرس فایل‌ها) و شماره آهنگ در حال پخش
    private var queueUris: List<String> = emptyList()
    private var queueIndex = 0
    private var mode = ""

    // هشدار ۱۰ ثانیه‌ای و محو شدن صدا
    private var alertSent = false
    private var shutdownCancelled = false
    private var fadeRunning = false

    // روش دوم (مستقل از موقعیت MediaPlayer): ساعت واقعی از لحظه شروع پخش
    private var wallStartRealtime = 0L
    private var wallDurMs = 0L
    // لحظه‌ی توقف موقت پخش — برای اینکه زمان مکث در ساعت واقعی حساب نشود
    private var pauseWallRealtime = 0L
    // پایان محوشدن بر اساس ساعت واقعی — برای محاسبه حجم
    private var fadeEndRealtime = 0L
    // اعلان هشدار الان روی نوار اعلان است؟ (برای برداشتنش وقتی اپ برگشت پیش‌زمینه)
    private var alertNotifActive = false

    // فوکوس صوتی: بعضی رام‌ها (مثل MIUI) بدون آن، صدا را درست مسیریابی نمی‌کنند
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    private fun requestAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        } catch (_: Exception) { }
    }

    private fun abandonAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            am.abandonAudioFocus(focusListener)
        } catch (_: Exception) { }
    }

    // هر نیم ثانیه موقعیت پخش را به‌روزرسانی می‌کند تا رقص نور اپ زنده بماند
    // و در ۱۰ ثانیه‌ی آخر، هشدار و محو شدن صدا فعال شود
    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && isPlaying) {
                positionSec = p.currentPosition / 1000
                updateRemaining()
                maybeStartAlertAndFade()
                handleAlertUi()
                tickHandler.postDelayed(this, 500)
            }
        }
    }

    /** زمان باقی‌مانده تا خاموشی (کل پلی‌لیست یا تک‌آهنگ) برای چیپ صفحه اصلی */
    private fun updateRemaining() {
        val p = player ?: return
        val trackRemaining = ((p.duration - p.currentPosition).coerceAtLeast(0)) / 1000
        remainingSec = trackRemaining + queueTailSec()
        if (mode == MODE_PLAYLIST) {
            totalSec = queueTotalSec()
        } else {
            totalSec = p.duration / 1000
        }
    }

    private fun queueTailSec(): Int {
        if (mode != MODE_PLAYLIST) return 0
        val durs = queueDurations()
        var sum = 0
        for (i in queueIndex + 1 until durs.size) sum += durs[i]
        return sum
    }

    private fun queueTotalSec(): Int {
        if (mode != MODE_PLAYLIST) return 0
        var sum = 0
        for (d in queueDurations()) sum += d
        return sum
    }

    // مدت هر آهنگ از همان جیسونی که صفحه اصلی ذخیره کرده خوانده می‌شود
    private fun queueDurations(): IntArray {
        val prefs = getSharedPreferences("sleep_music", Context.MODE_PRIVATE)
        val raw = prefs.getString("playlist_json", null) ?: return IntArray(0)
        return try {
            val arr = JSONArray(raw)
            val out = IntArray(arr.length())
            for (i in 0 until arr.length()) {
                out[i] = arr.getJSONObject(i).optInt("d", 0)
            }
            out
        } catch (_: Exception) {
            IntArray(0)
        }
    }

    /**
     * در ۱۰ ثانیه‌ی آخرِ آخرین آهنگ: هشدار + محو شدن صدا (اگر کاربر روشن گذاشته باشد).
     * پاپ‌آپ درون برنامه (همان تصویر تأییدشده) همیشه با برادکست نشان داده می‌شود؛
     * اعلان قفل صفحه فقط وقتی ساخته می‌شود که اپ در پیش‌زمینه نیست.
     * دو روش مستقل با هم چک می‌شوند تا حتی اگر mp3 طول را اشتباه گفت، باز هم فعال شود:
     *   ۱) موقعیت MediaPlayer نسبت به duration
     *   ۲) ساعت واقعی از لحظه‌ی شروع پخش (اگر position گیر کرد)
     */
    private fun maybeStartAlertAndFade() {
        val p = player ?: return
        if (alertSent || shutdownCancelled) return
        val prefs = getSharedPreferences("sleep_music", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("alert_enabled", true)) return
        val isLast = mode != MODE_PLAYLIST || queueIndex >= queueUris.size - 1
        if (!isLast) return
        val remainingPosMs = (p.duration.toLong() - p.currentPosition).coerceAtLeast(0)
        val remainingWallMs = if (wallDurMs > 0) {
            (wallDurMs - (SystemClock.elapsedRealtime() - wallStartRealtime)).coerceAtLeast(0)
        } else {
            Long.MAX_VALUE
        }
        // دو روش مستقل: هر کدام کمتر باشد؛ ولی اگر ساعت واقعی به‌خاطر مکث خراب شد (صفر یا منفی)،
        // نباید هشدار بُمیرد — همان موقعیت MediaPlayer مبنا می‌شود
        val remainingMs = if (remainingWallMs in 1..remainingPosMs) remainingWallMs else remainingPosMs
        if (remainingMs in 1..10000) {
            alertSent = true
            startFade(p, remainingMs)
            val alertIntent = Intent(ALERT_ACTION).setPackage(packageName)
            alertIntent.putExtra("remaining_ms", remainingMs.toInt())
            alertIntent.putExtra("state", "show")
            sendBroadcast(alertIntent)
        }
    }

    /** کم شدن نرم صدا در ۱۰ ثانیه‌ی آخر — با دو روش مستقل، هر کدام کمتر باشد اعمال می‌شود */
    private fun startFade(p: MediaPlayer, remainingMs: Long) {
        if (fadeRunning) return
        fadeRunning = true
        fadeEndRealtime = SystemClock.elapsedRealtime() + remainingMs
        val fadeRunnable = object : Runnable {
            override fun run() {
                val pp = player
                if (!fadeRunning || pp == null || pp !== p) return
                val remainingPos = (pp.duration.toLong() - pp.currentPosition).coerceAtLeast(0)
                val remainingWall = (fadeEndRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                val remainingMsNow = if (remainingWall in 1..remainingPos) remainingWall else remainingPos
                val vol = (remainingMsNow / 10000f).coerceIn(0.03f, 1f)
                try { pp.setVolume(vol, vol) } catch (_: Exception) { }
                if (remainingMsNow > 0) tickHandler.postDelayed(this, 350)
            }
        }
        tickHandler.postDelayed(fadeRunnable, 200)
    }

    /** برگرداندن نرم صدا بعد از لغو خاموشی */
    private fun restoreVolume() {
        fadeRunning = false
        val p = player ?: return
        val steps = floatArrayOf(0.35f, 0.6f, 0.8f, 1f)
        for (i in steps.indices) {
            tickHandler.postDelayed({
                val pp = player
                if (pp != null && pp === p) {
                    try { pp.setVolume(steps[i], steps[i]) } catch (_: Exception) { }
                }
            }, (i * 250).toLong())
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            // کانال اعلان فوری: بالای صفحه و بالای قفل با صدا و لرزش — برای هشدار ۱۰ ثانیه‌ای
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID, "هشدار ۱۰ ثانیه‌ای خاموشی", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "۱۰ ثانیه قبل از خاموش شدن گوشی صدا و لرزش دارد و بالای قفل نشان داده می‌شود"
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    /**
     * هر نیم ثانیه چک می‌شود:
     *   اپ جلوی چشم است  → پاپ‌آپ درون برنامه کار می‌کند؛ اعلان را بردار (برگشت از پس‌زمینه)
     *   اپ در پس‌زمینه است → اعلان قفل صفحه با شمارش معکوس زنده نشان بده
     */
    private fun handleAlertUi() {
        if (!alertSent || shutdownCancelled) return
        if (appInForeground) {
            if (alertNotifActive) cancelAlertNotification()
            return
        }
        val p = player ?: return
        val remainingPos = (p.duration.toLong() - p.currentPosition).coerceAtLeast(0)
        val remainingWall = (fadeEndRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        val remaining = if (remainingWall in 1..remainingPos) remainingWall else remainingPos
        if (remaining <= 0) return
        alertRemainingMs = remaining
        buildAlertNotification(remaining)
    }

    /** اعلان فوری بالای قفل: شمارش معکوس + دو دکمه «لغو خاموشی» و «خاموش کن» — فقط برای وقتی اپ در پیش‌زمینه نیست */
    private fun buildAlertNotification(remainingMs: Long) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val secs = ((remainingMs + 999) / 1000).toInt()
        val cancelIt = PendingIntent.getService(
            this, 12,
            Intent(this, MusicService::class.java).setAction(ACTION_CANCEL_SHUTDOWN),
            PendingIntent.FLAG_IMMUTABLE
        )
        val doNow = PendingIntent.getService(
            this, 13,
            Intent(this, MusicService::class.java).setAction(ACTION_SHUTDOWN_NOW),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("⏰ " + faDigits("" + secs) + " ثانیه تا خاموش شدن گوشی")
            .setContentText("صدای موسیقی کم می‌شود — اگر می‌خواهی بیداری، لغو کن")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "لغو خاموشی", cancelIt)
            .addAction(0, "خاموش کن", doNow)
            .build()
        alertNotifActive = true
        try {
            manager.notify(ALERT_NOTIF_ID, notification)
        } catch (_: Exception) { }
    }

    private fun cancelAlertNotification() {
        alertRemainingMs = 0
        alertNotifActive = false
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        try { manager.cancel(ALERT_NOTIF_ID) } catch (_: Exception) { }
    }

    private fun faDigits(s: String): String = ThemeRepo.faDigits(s)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            // توقف کامل: موسیقی قطع و خاموشی امشب لغو می‌شود؛ آمار شب‌ها دست‌نخورده می‌ماند
            stopEverything()
            return START_NOT_STICKY
        }
        if (action == ACTION_PAUSE) {
            val p = player
            if (p != null && isPlaying) {
                try { p.pause() } catch (_: Exception) { }
                isPlaying = false
                isPaused = true
                tickHandler.removeCallbacks(ticker)
                // ساعت واقعی هم از لحظه‌ی مکث نگه داشته می‌شود تا زمان مکث در آن حساب نشود
                pauseWallRealtime = SystemClock.elapsedRealtime()
                // هشدار ۱۰ ثانیه‌ای هم معطل می‌ماند؛ بعد از ادامه از نو نشان داده می‌شود
                alertSent = false
                fadeRunning = false
                // اگر صدا در حال محوشدن بود، بلندی را برگردان تا بعد از ادامه، موسیقی کامل شنیده شود
                restoreVolume()
                cancelAlertNotification()
                broadcastAlertState("done")
            }
            return START_NOT_STICKY
        }
        if (action == ACTION_RESUME) {
            val p = player
            if (p != null && isPaused) {
                try { p.start() } catch (_: Exception) { }
                isPaused = false
                isPlaying = true
                // زمان مکث از ساعت واقعی کم می‌شود تا روش دوم بعد از مکث هم دقیق بماند
                if (pauseWallRealtime > 0) {
                    wallStartRealtime += SystemClock.elapsedRealtime() - pauseWallRealtime
                    pauseWallRealtime = 0L
                }
                tickHandler.post(ticker)
            }
            return START_NOT_STICKY
        }
        if (action == ACTION_CANCEL_SHUTDOWN) {
            shutdownCancelled = true
            fadeRunning = false
            cancelAlertNotification()
            restoreVolume()
            Toast.makeText(this, "خاموشی لغو شد؛ موسیقی ادامه پیدا می‌کند", Toast.LENGTH_SHORT).show()
            return START_NOT_STICKY
        }
        if (action == ACTION_SHUTDOWN_NOW) {
            val prefs = getSharedPreferences("sleep_music", Context.MODE_PRIVATE)
            // همان زنجیره‌ی خاموشی همیشگی — بدون هیچ تغییری
            ActionExecutor.execute(this@MusicService, prefs.getBoolean("power_menu", true))
            stopEverything()
            return START_NOT_STICKY
        }
        if (action == ACTION_SEEK) {
            val ms = intent.getIntExtra(EXTRA_SEEK_MS, -1)
            val p = player
            if (ms >= 0 && p != null) {
                try {
                    val oldPos = p.currentPosition
                    p.seekTo(ms)
                    // ساعت واقعی هم با همان جابه‌جایی اصلاح می‌شود تا روش دوم همیشه درست بماند
                    wallStartRealtime += (oldPos - ms)
                    positionSec = ms / 1000
                    updateRemaining()
                } catch (_: Exception) { }
            }
            return START_NOT_STICKY
        }

        mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_SINGLE
        shutdownCancelled = false
        alertSent = false
        fadeRunning = false
        retryCount = 0

        if (mode == MODE_PLAYLIST) {
            val uris = readPlaylistUris()
            if (uris.isEmpty()) {
                Toast.makeText(this, "پلی‌لیست خالی است؛ اول آهنگ اضافه کن", Toast.LENGTH_SHORT).show()
                stopSelf()
                return START_NOT_STICKY
            }
            queueUris = uris
            queueIndex = 0
        } else {
            val uriString = intent?.getStringExtra(EXTRA_URI) ?: run {
                stopSelf()
                return START_NOT_STICKY
            }
            queueUris = listOf(uriString)
            queueIndex = 0
        }

        createChannelAndStartForeground()
        launchPlayer(queueUris[queueIndex])
        return START_NOT_STICKY
    }

    private fun readPlaylistUris(): List<String> {
        val prefs = getSharedPreferences("sleep_music", Context.MODE_PRIVATE)
        val raw = prefs.getString("playlist_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val u = arr.getJSONObject(i).optString("u", "")
                if (u.isNotBlank()) out.add(u)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * ساخت پلیر و شروع پخش.
     * نکته‌ی مهم: به‌جای پاس‌دادن مستقیم URI به MediaPlayer (که روی بعضی رام‌ها
     * مخصوصاً با مسیرهای فارسی/دارای فضاخالی خطای -38 می‌دهد)، فایل را با
     * FileDescriptor از ContentResolver باز می‌کنیم؛ این روش همه‌جا پایدار است.
     */
    private fun launchPlayer(uriString: String) {
        player?.run {
            try { stop() } catch (_: Exception) { }
            release()
        }
        player = null

        var fd: ParcelFileDescriptor? = null
        var mp: MediaPlayer? = null
        try {
            mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setOnPreparedListener { prepared ->
                durationSec = prepared.duration / 1000
                positionSec = 0
                audioSessionId = prepared.audioSessionId
                MusicService.isPlaying = true
                requestAudioFocus()
                prepared.setVolume(1f, 1f)
                prepared.start()
                trackIndex = queueIndex
                trackCount = queueUris.size
                modeLive = mode
                // روش دوم (ساعت واقعی) از همین لحظه شروع می‌شود
                wallStartRealtime = SystemClock.elapsedRealtime()
                wallDurMs = prepared.duration.toLong()
                updateRemaining()
                tickHandler.post(ticker)
            }
            mp.setOnCompletionListener {
                // در پلی‌لیست: آهنگ بعدی؛ بعد از آخرین آهنگ: خاموشی همیشگی
                if (mode == MODE_PLAYLIST && queueIndex < queueUris.size - 1 && !shutdownCancelled) {
                    queueIndex++
                    launchPlayer(queueUris[queueIndex])
                    updateNotification()
                } else {
                    if (!shutdownCancelled) {
                        recordNightSuccess()
                        val prefs = getSharedPreferences("sleep_music", Context.MODE_PRIVATE)
                        // زنجیره‌ی خاموشی واقعی — عیناً مثل نسخه‌هایی که تست شده
                        ActionExecutor.execute(this@MusicService, prefs.getBoolean("power_menu", true))
                    }
                    cancelAlertNotification()
                    broadcastAlertState("done")
                    stopEverything()
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                // یک بار خودکار تلاش مجدد؛ اگر باز هم خطا داد پیام نشان می‌دهیم
                if (retryCount < 1) {
                    retryCount++
                    MusicService.isPlaying = false
                    tickHandler.removeCallbacks(ticker)
                    tickHandler.postDelayed({ launchPlayer(uriString) }, 400)
                } else {
                    Toast.makeText(
                        this@MusicService,
                        "پخش با خطا متوقف شد (کد " + what + "/" + extra + ")؛ لطفاً دوباره آهنگ را انتخاب کن",
                        Toast.LENGTH_LONG
                    ).show()
                    stopEverything()
                }
                true
            }
            fd = contentResolver.openFileDescriptor(Uri.parse(uriString), "r")
                ?: throw IOException("باز کردن فایل ممکن نشد")
            mp.setDataSource(fd.fileDescriptor)
            // آماده‌سازی غیرمسدودکننده؛ start در OnPreparedListener انجام می‌شود
            mp.prepareAsync()
            player = mp
        } catch (_: Exception) {
            try { mp?.release() } catch (_: Exception) { }
            Toast.makeText(
                this,
                "دسترسی به فایل موسیقی ممکن نشد؛ لطفاً دوباره آهنگ را انتخاب کن",
                Toast.LENGTH_LONG
            ).show()
            stopEverything()
        } finally {
            // MediaPlayer به‌صورت داخلی کپی fd را نگه می‌دارد؛ بستن این یکی امن است
            try { fd?.close() } catch (_: Exception) { }
        }
    }

    /** آمار شب‌های آرام: پخش تا آخر + خاموشی = یک شب موفق */
    private fun recordNightSuccess() {
        try {
            val prefs = getSharedPreferences("sleep_music", Context.MODE_PRIVATE)
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val today = fmt.format(Calendar.getInstance().time)
            val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val yesterday = fmt.format(yesterdayCal.time)
            val lastDay = prefs.getString("last_success_day", "")
            var streak = prefs.getInt("streak_nights", 0)
            streak = when (lastDay) {
                today -> streak
                yesterday -> streak + 1
                else -> 1
            }
            val best = maxOf(streak, prefs.getInt("best_record", 0))
            prefs.edit()
                .putInt("streak_nights", streak)
                .putInt("best_record", best)
                .putString("last_success_day", today)
                .apply()
        } catch (_: Exception) { }
    }

    private fun broadcastAlertState(state: String) {
        val i = Intent(ALERT_ACTION).setPackage(packageName)
        i.putExtra("state", state)
        sendBroadcast(i)
    }

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val text = if (mode == MODE_PLAYLIST && queueUris.size > 1) {
            "پلی‌لیست: آهنگ " + (queueIndex + 1) + " از " + queueUris.size + " — بعد از آخرین آهنگ، گوشی خاموش می‌شود"
        } else {
            "موسیقی در حال پخش است؛ بعد از پایان، گوشی خاموش می‌شود"
        }
        val openApp = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIt = PendingIntent.getService(
            this, 11,
            Intent(this, MusicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("خواب‌موزیک")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openApp)
            .addAction(0, "توقف", stopIt)
            .setOngoing(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) { }
    }

    private fun createChannelAndStartForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "پخش موسیقی", NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIt = PendingIntent.getService(
            this, 11,
            Intent(this, MusicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("خواب‌موزیک")
            .setContentText("موسیقی در حال پخش است؛ بعد از پایان، گوشی خاموش می‌شود")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openApp)
            .addAction(0, "توقف", stopIt)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopEverything() {
        isPlaying = false
        isPaused = false
        fadeRunning = false
        tickHandler.removeCallbacks(ticker)
        abandonAudioFocus()
        player?.run {
            try { stop() } catch (_: Exception) { }
            release()
        }
        player = null
        positionSec = 0
        remainingSec = 0
        audioSessionId = 0
        cancelAlertNotification()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isPlaying = false
        isPaused = false
        fadeRunning = false
        tickHandler.removeCallbacks(ticker)
        abandonAudioFocus()
        player?.run {
            try { stop() } catch (_: Exception) { }
            release()
        }
        player = null
        audioSessionId = 0
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_MODE = "extra_mode"
        const val MODE_SINGLE = "single"
        const val MODE_PLAYLIST = "playlist"
        const val ACTION_STOP = "action_stop"
        const val ACTION_PAUSE = "action_pause"
        const val ACTION_RESUME = "action_resume"
        const val ACTION_CANCEL_SHUTDOWN = "action_cancel_shutdown"
        const val ACTION_SHUTDOWN_NOW = "action_shutdown_now"
        const val ACTION_SEEK = "action_seek"
        const val EXTRA_SEEK_MS = "extra_seek_ms"
        const val ALERT_ACTION = "ir.sleepmusic.app.TEN_SECOND_ALERT"
        const val CHANNEL_ID = "music_playback"
        const val ALERT_CHANNEL_ID = "shutdown_alert"
        const val NOTIFICATION_ID = 1
        const val ALERT_NOTIF_ID = 2

        // وضعیت پخش برای نمایش زنده رقص نور و چیپ‌ها در اکتیویتی
        @Volatile var isPlaying = false
        @Volatile var isPaused = false
        @Volatile var positionSec = 0
        @Volatile var durationSec = 0
        @Volatile var audioSessionId = 0
        @Volatile var remainingSec = 0
        @Volatile var totalSec = 0
        @Volatile var trackIndex = 0
        @Volatile var trackCount = 0
        @Volatile var modeLive = ""
        @Volatile var alertRemainingMs = 0L
        // اپ در پیش‌زمینه است؟ (MainActivity در onResume/onPause ست می‌کند)
        @Volatile var appInForeground = false
    }
}
