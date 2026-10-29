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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.IOException

class MusicService : Service() {

    private var player: MediaPlayer? = null
    private val tickHandler = Handler(Looper.getMainLooper())
    private var retryCount = 0

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
    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && isPlaying) {
                positionSec = p.currentPosition / 1000
                tickHandler.postDelayed(this, 500)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        val uriString = intent?.getStringExtra(EXTRA_URI) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        // حالت پیش‌نمایش: فقط پخش می‌کند؛ در پایان آهنگ خاموشی انجام نمی‌شود
        previewMode = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        retryCount = 0

        createChannelAndStartForeground()
        launchPlayer(uriString)
        return START_NOT_STICKY
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
                MusicService.isPreview = previewMode
                MusicService.isPlaying = true
                requestAudioFocus()
                prepared.setVolume(1f, 1f)
                prepared.start()
                tickHandler.post(ticker)
            }
            mp.setOnCompletionListener {
                // پایان آهنگ: در حالت عادی خاموشی (یا قفل صفحه)؛ در پیش‌نمایش فقط توقف
                if (!previewMode) {
                    val prefs = getSharedPreferences("sleep_music", Context.MODE_PRIVATE)
                    ActionExecutor.execute(this@MusicService, prefs.getBoolean("power_menu", true))
                }
                stopEverything()
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
        tickHandler.removeCallbacks(ticker)
        abandonAudioFocus()
        player?.run {
            try { stop() } catch (_: Exception) { }
            release()
        }
        player = null
        positionSec = 0
        audioSessionId = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isPlaying = false
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

    private var previewMode = false

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_PREVIEW = "extra_preview"
        const val ACTION_STOP = "action_stop"
        const val CHANNEL_ID = "music_playback"
        const val NOTIFICATION_ID = 1

        // وضعیت پخش برای نمایش زنده رقص نور در اکتیویتی
        @Volatile var isPlaying = false
        @Volatile var isPreview = false
        @Volatile var positionSec = 0
        @Volatile var durationSec = 0
        @Volatile var audioSessionId = 0
    }
}
