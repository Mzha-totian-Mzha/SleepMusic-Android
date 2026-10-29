package ir.sleepmusic.app

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
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
    private lateinit var tvWizardProgress: TextView

    // وقتی به تنظیمات «شروع خودکار» رفتیم، بعد از برگشتن قدم را «انجام‌شده» علامت می‌زنیم
    private var waitingAutostart = false

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
        tvWizardProgress = findViewById(R.id.tvWizardProgress)
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

        findViewById<Button>(R.id.btnStart).setOnClickListener { startAction() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopAction() }
        btnPreview.setOnClickListener { togglePreview() }

        // راه‌اندازی اولیه: سه قدم، پشت سر هم
        findViewById<View>(R.id.stepAutostart).setOnClickListener { openAutostartSettings() }
        findViewById<View>(R.id.stepBattery).setOnClickListener { openBatterySettings() }
        findViewById<View>(R.id.stepAccessibility).setOnClickListener { openAccessibilitySettings() }

        switchPowerMenu.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("power_menu", checked).apply()
        }

        maybeStartWizard()
    }

    override fun onResume() {
        super.onResume()
        if (waitingAutostart) {
            waitingAutostart = false
            // وضعیت Autostart از بیرون قابل خواندن نیست؛ بازدید کاربر = انجام شد
            prefs.edit().putBoolean("autostart_done", true).apply()
        }
        refreshWizardUi()
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

    /** قدم Autostart قابل خواندن نیست؛ با بازدید کاربر علامت می‌خورد */
    private fun autostartDone(): Boolean = prefs.getBoolean("autostart_done", false)

    /** قدم باتری: وضعیت واقعی از PowerManager خوانده می‌شود */
    private fun batteryDone(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            false
        }
    }

    /** کارت راه‌اندازی: وضعیت سه قدم را تازه می‌کند */
    private fun refreshWizardUi() {
        val steps = listOf(
            Triple(R.id.tvStepAutostartBadge, R.id.tvStepAutostartState, autostartDone()),
            Triple(R.id.tvStepBatteryBadge, R.id.tvStepBatteryState, batteryDone()),
            Triple(R.id.tvStepAccessibilityBadge, R.id.tvStepAccessibilityState, accessibilityEnabled())
        )
        val numbers = listOf("۱", "۲", "۳")
        var remaining = 0
        for (i in steps.indices) {
            val (badgeId, stateId, done) = steps[i]
            val badge = findViewById<TextView>(badgeId)
            val state = findViewById<TextView>(stateId)
            if (done) {
                badge.text = "✓"
                badge.backgroundTintList = ColorStateList.valueOf(0xFFDFF3E7.toInt())
                badge.setTextColor(0xFF1E7B45.toInt())
                state.text = "انجام شد ✓"
                state.setTextColor(0xFF1E7B45.toInt())
            } else {
                remaining++
                badge.text = numbers[i]
                badge.backgroundTintList = ColorStateList.valueOf(0xFFEFE4FA.toInt())
                badge.setTextColor(0xFF6D28B8.toInt())
                state.text = "تنظیم   ‹"
                state.setTextColor(0xFF8E4FD0.toInt())
            }
        }
        tvWizardProgress.text = if (remaining == 0) "همه‌چیز آماده‌ست ✓" else "" + remaining + " قدم مانده"
    }

    /** بار اول اجرا: با یک توضیح ساده، قدم اول راه‌اندازی را شروع می‌کنیم */
    private fun maybeStartWizard() {
        if (autostartDone() && batteryDone() && accessibilityEnabled()) return
        if (prefs.getBoolean("wizard_prompted", false)) return
        prefs.edit().putBoolean("wizard_prompted", true).apply()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("سه قدم تا آماده شدن 🌙")
            .setMessage(
                "برای اینکه خاموشی خودکار درست کار کنه، سه قدم ساده لازمه:\n" +
                    "۱) شروع خودکار\n۲) محدود نکردن مصرف باتری\n۳) سرویس دسترس‌پذیری\n\n" +
                    "هر قدم رو که انجام بدی تیک می‌خوره و خودت می‌ری سراغ قدم بعدی. الان شروع کنیم؟"
            )
            .setPositiveButton("بریم") { _, _ -> openAutostartSettings() }
            .setNegativeButton("بعداً", null)
            .show()
    }

    /** قدم ۱: صفحه‌ی شروع خودکار MIUI (اگر نبود، جزئیات اپ) */
    private fun openAutostartSettings() {
        waitingAutostart = true
        Toast.makeText(this, "«شروع خودکار» خواب‌موزیک را روشن کن", Toast.LENGTH_LONG).show()
        try {
            val intent = Intent()
            intent.component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + packageName)
                    )
                )
            } catch (_: Exception) {
                waitingAutostart = false
                Toast.makeText(this, "صفحه‌ی شروع خودکار پیدا نشد؛ از تنظیمات گوشی فعالش کن", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** قدم ۲: درخواست «بدون محدودیت» باتری */
    private fun openBatterySettings() {
        if (batteryDone()) {
            Toast.makeText(this, "باتری از قبل بدون محدودیت است", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "گزینه‌ی «اجازه» را بزن تا باتری محدودت نکند", Toast.LENGTH_LONG).show()
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + packageName)
                )
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "صفحه‌ی باتری پیدا نشد", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** قدم ۳: سرویس دسترس‌پذیری */
    private fun openAccessibilitySettings() {
        Toast.makeText(this, "«خواب‌موزیک» را پیدا کن و سرویس «قفل صفحه هوشمند» را روشن کن", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

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

    /** فقط پخش آهنگ بدون خاموشی؛ برای اینکه کاربر صدا را قبل از اجرای اصلی بشنود */
    private fun togglePreview() {
        if (MusicService.isPlaying) {
            stopService(Intent(this, MusicService::class.java))
            Toast.makeText(this, "پخش متوقف شد", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = prefs.getString("music_uri", null)
        if (uri == null) {
            Toast.makeText(this, "اول یک آهنگ انتخاب کن", Toast.LENGTH_SHORT).show()
            return
        }
        warnIfMuted()
        val intent = Intent(this, MusicService::class.java)
        intent.putExtra(MusicService.EXTRA_URI, uri)
        intent.putExtra(MusicService.EXTRA_PREVIEW, true)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "پخش شروع شد؛ در پایان آهنگ هیچ خاموشی انجام نمی‌شود", Toast.LENGTH_LONG).show()
    }

    private fun startAction() {
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