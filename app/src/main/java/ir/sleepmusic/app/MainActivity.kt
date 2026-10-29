package ir.sleepmusic.app

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private lateinit var tvSelectedMusic: TextView
    private lateinit var tvScheduledTime: TextView
    private lateinit var radioGroup: RadioGroup
    private lateinit var radioScheduled: RadioButton
    private lateinit var switchPowerMenu: MaterialSwitch

    // ویوهای کارت پلیر (طرح یاسی روشن)
    private lateinit var equalizerView: EqualizerView
    private lateinit var tvTimeElapsed: TextView
    private lateinit var tvTimeTotal: TextView
    private lateinit var tvPlayerStatus: TextView
    private lateinit var btnPreview: Button

    // برای اینکه ویژوآلایزر فقط هنگام تغییر وضعیت وصل/قطع شود
    private var lastPlaying = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updatePlaybackUi()
            uiHandler.postDelayed(this, 500)
        }
    }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            prefs.edit().putString("music_uri", uri.toString()).apply()
            updateMusicInfo(uri)
        }
    }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // مجوز رقص نور: Visualizer فقط موج صدا را تحلیل می‌کند؛ هیچ صدایی ضبط نمی‌شود
    private val recordAudioLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("sleep_music", Context.MODE_PRIVATE)

        // راه‌اندازی اولیه: بار اول، قبل از ورود به صفحه‌ی اصلی، مجوزها را مرحله‌به‌مرحله می‌گیریم
        if (!prefs.getBoolean("onboarding_done", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        tvSelectedMusic = findViewById(R.id.tvSelectedMusic)
        tvScheduledTime = findViewById(R.id.tvScheduledTime)
        radioGroup = findViewById(R.id.radioGroup)
        radioScheduled = findViewById(R.id.radioScheduled)
        switchPowerMenu = findViewById(R.id.switchPowerMenu)
        equalizerView = findViewById(R.id.equalizerView)
        tvTimeElapsed = findViewById(R.id.tvTimeElapsed)
        tvTimeTotal = findViewById(R.id.tvTimeTotal)
        tvPlayerStatus = findViewById(R.id.tvPlayerStatus)
        btnPreview = findViewById(R.id.btnPreview)
        equalizerView.setPlaying(false)

        // بازیابی تنظیمات ذخیره‌شده
        val savedUri = prefs.getString("music_uri", null)
        if (savedUri != null) updateMusicInfo(Uri.parse(savedUri))
        val savedHour = prefs.getInt("alarm_hour", -1)
        if (savedHour >= 0) {
            tvScheduledTime.text = "زمان‌بندی: " + formatTime(savedHour, prefs.getInt("alarm_minute", 0))
        }
        switchPowerMenu.isChecked = prefs.getBoolean("power_menu", true)

        // اجازه اعلان (اندروید ۱۳ به بالا) برای اینکه پخش در پس‌زمینه قطع نشود
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // مجوز رقص نور (تحلیل زنده‌ی موج صدا)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        findViewById<Button>(R.id.btnPickMusic).setOnClickListener {
            pickAudio.launch(arrayOf("audio/*"))
        }

        findViewById<Button>(R.id.btnPickTime).setOnClickListener {
            val now = Calendar.getInstance()
            TimePickerDialog(this, { _, hour, minute ->
                prefs.edit().putInt("alarm_hour", hour).putInt("alarm_minute", minute).apply()
                tvScheduledTime.text = "زمان‌بندی: " + formatTime(hour, minute)
                radioScheduled.isChecked = true
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener { stopAction() }

        // تک‌دکمه‌ی اصلی: پخش می‌کند و درست بعد از پایان آهنگ گوشی را خاموش می‌کند
        btnPreview.setOnClickListener {
            if (MusicService.isPlaying) {
                stopService(Intent(this, MusicService::class.java))
                Toast.makeText(this, "پخش متوقف شد", Toast.LENGTH_SHORT).show()
            } else {
                startAction()
            }
        }

        switchPowerMenu.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("power_menu", checked).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(ticker)
    }

    /** نمایش زنده رقص نور و زمان‌ها (وقتی سرویس در حال پخش است) */
    private fun updatePlaybackUi() {
        val playing = MusicService.isPlaying
        if (playing != lastPlaying) {
            lastPlaying = playing
            equalizerView.setPlaying(playing, MusicService.audioSessionId)
            btnPreview.text = if (playing) "■   توقف" else "▶   پخش"
        }
        if (!playing) {
            tvPlayerStatus.text = "آماده برای پخش"
            return
        }
        val dur = MusicService.durationSec
        val pos = MusicService.positionSec
        if (dur > 0) {
            tvTimeTotal.text = formatSecs(dur)
        }
        tvTimeElapsed.text = formatSecs(pos)
        tvPlayerStatus.text = "در حال پخش…"
    }

    /** نام و مدت زمان فایل انتخاب‌شده را در کارت پلیر نشان می‌دهد */
    private fun updateMusicInfo(uri: Uri) {
        // فقط نام فایل را نشان می‌دهیم نه کل مسیر «primary:…» را
        val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "آهنگ انتخاب شد"
        tvSelectedMusic.text = name
        tvPlayerStatus.text = "آماده برای پخش"
        tvTimeElapsed.text = "00:00"

        Thread {
            var durationMs = -1L
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: -1L
                retriever.release()
            } catch (_: Exception) { }

            runOnUiThread {
                tvTimeTotal.text = if (durationMs > 0) formatSecs((durationMs / 1000).toInt()) else "--:--"
            }
        }.start()
    }

    private fun formatSecs(total: Int): String {
        val m = total / 60
        val s = total % 60
        val mm = if (m < 10) "0" + m else "" + m
        val ss = if (s < 10) "0" + s else "" + s
        return mm + ":" + ss
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val h = if (hour < 10) "0" + hour else "" + hour
        val m = if (minute < 10) "0" + minute else "" + minute
        return h + ":" + m
    }

    /** آیا سرویس دسترس‌پذیری فعال است؟ */
    private fun accessibilityEnabled(): Boolean = LockAccessibilityService.instance != null

    /** اگر ولوم رسانه (Media) صفر باشد، کاربر فکر می‌کند پخش خراب است */
    private fun warnIfMuted() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                Toast.makeText(
                    this,
                    "ولوم رسانه (Media) صفر است؛ با دکمه‌ی ولوم گوشی زیادش کن",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (_: Exception) { }
    }

    /** دکمه‌ی اصلی: پخش آهنگ + خاموشی خودکار بعد از پایان (یا زمان‌بندی) */
    private fun startAction() {
        // بدون سرویس دسترس‌پذیری، خاموشی ممکن نیست؛ اول راه‌اندازی را کامل می‌کنیم
        if (!accessibilityEnabled()) {
            Toast.makeText(this, "برای خاموشی خودکار، اول سرویس دسترس‌پذیری را روشن کن", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, OnboardingActivity::class.java))
            return
        }
        val mode = if (radioGroup.checkedRadioButtonId == R.id.radioScheduled) "scheduled" else "after_music"
        if (mode == "after_music") {
            val uri = prefs.getString("music_uri", null)
            if (uri == null) {
                Toast.makeText(this, "اول یک آهنگ انتخاب کن", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(this, MusicService::class.java)
            intent.putExtra(MusicService.EXTRA_URI, uri)
            warnIfMuted()
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "پخش شروع شد؛ درست بعد از پایان آهنگ گوشی خاموش می‌شود", Toast.LENGTH_LONG).show()
        } else {
            val hour = prefs.getInt("alarm_hour", -1)
            if (hour < 0) {
                Toast.makeText(this, "اول ساعت را انتخاب کن", Toast.LENGTH_SHORT).show()
                return
            }
            setAlarm(hour, prefs.getInt("alarm_minute", 0))
        }
    }

    private fun setAlarm(hour: Int, minute: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            this, 1001,
            Intent(this, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }

        // اندروید ۱۲ به بالا: اجازه هشدار دقیق لازم است
        if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
            Toast.makeText(this, "در صفحه بعدی اجازه «هشدار دقیق» را بده", Toast.LENGTH_LONG).show()
            val settingsIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            settingsIntent.data = Uri.parse("package:" + packageName)
            startActivity(settingsIntent)
            return
        }
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        Toast.makeText(this, "زمان‌بندی شد برای ساعت " + formatTime(hour, minute), Toast.LENGTH_LONG).show()
    }

    private fun stopAction() {
        stopService(Intent(this, MusicService::class.java))
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            this, 1001,
            Intent(this, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Toast.makeText(this, "همه‌چیز متوقف شد", Toast.LENGTH_SHORT).show()
    }
}