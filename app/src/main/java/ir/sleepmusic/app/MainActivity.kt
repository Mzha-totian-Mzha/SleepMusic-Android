package ir.sleepmusic.app

import android.Manifest
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * صفحه اصلی — دقیقاً مطابق طراحی تأییدشده:
 * - هدر با تنها یک دکمه: چرخ‌دنده تنظیمات (تم، پلی‌لیست، موسیقی، هشدار، آمار، حالت شب خودکار)
 * - کارت پلیر با رقص نور تم‌دار، نوار پیشرفت قابل کشیدن (راست‌به‌چپ) و چیپ زمان خاموش شدن
 * - دکمه بزرگ پخش: بعد از پایان موسیقی (یا آخرین آهنگ پلی‌لیست) گوشی خاموش می‌شود
 * - هشدار ۱۰ ثانیه‌ای + محو شدن صدا؛ آمار شب‌های آرام پایین صفحه
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var theme: AppTheme

    // هدر و بدنه
    private lateinit var rootScroll: android.widget.ScrollView
    private lateinit var headerFrame: android.widget.FrameLayout
    private lateinit var tvSubtitle: TextView

    // کارت پلیر
    private lateinit var cardPlayer: LinearLayout
    private lateinit var iconMusicBox: TextView
    private lateinit var tvMusicLabel: TextView
    private lateinit var tvSelectedMusic: TextView
    private lateinit var tvPlayPill: TextView
    private lateinit var equalizerView: EqualizerView
    private lateinit var seekBar: SeekBar
    private lateinit var tvTimeElapsed: TextView
    private lateinit var tvPlayerStatus: TextView
    private lateinit var tvTimeTotal: TextView
    private lateinit var chipA: LinearLayout
    private lateinit var chipAIcon: TextView
    private lateinit var tvChipALabel: TextView
    private lateinit var tvChipAValue: TextView
    private lateinit var chipB: LinearLayout
    private lateinit var chipBIcon: TextView
    private lateinit var tvChipBLabel: TextView
    private lateinit var tvChipBValue: TextView

    // دکمه‌های سه‌حالته + توقف کامل
    private lateinit var btnMain: LinearLayout
    private lateinit var tvBtnMain: TextView
    private lateinit var btnStopAll: LinearLayout
    private lateinit var cardStats: LinearLayout
    private lateinit var statsIcon: TextView
    private lateinit var tvStatsMain: TextView
    private lateinit var statsDots: LinearLayout
    private lateinit var tvFooter: TextView

    // جمله‌های انگیزشی چرخشی پایین صفحه — به جای متن ثابت (درخواست کاربر)
    private val motivation = listOf(
        "شب بخیر، خواب خوش 🌙",
        "امشب هم آرام بخواب 😴",
        "نفسی عمیق بکش و رها شو 🌌",
        "موسیقی تا آخر؛ بعدش گوشی خودش می‌خوابد 🌙",
        "خواب‌موزیک همین‌جا کنار توست 💜"
    )
    private var footerIdx = 0
    private val footerHandler = Handler(Looper.getMainLooper())
    private val footerTicker = object : Runnable {
        override fun run() {
            footerIdx = (footerIdx + 1) % motivation.size
            tvFooter.animate().alpha(0f).setDuration(300).withEndAction {
                tvFooter.text = motivation[footerIdx]
                tvFooter.animate().alpha(1f).setDuration(300).start()
            }.start()
            footerHandler.postDelayed(this, 4000)
        }
    }

    private var lastPlaying = false
    private var seekDragging = false

    private var alertDialog: AlertDialog? = null
    private var alertBar: View? = null
    private var alertAnimator: android.animation.ValueAnimator? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updatePlaybackUi()
            checkAlertPolling()
            uiHandler.postDelayed(this, 300)
        }
    }

    // انتخاب یک آهنگ (ردیف انتخاب موسیقی)
    private val pickAudio = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            prefs.edit().putString("music_uri", uri.toString()).apply()
            updateMusicInfo(uri)
            Toast.makeText(this, "آهنگ انتخاب شد", Toast.LENGTH_SHORT).show()
        }
    }

    // افزودن چند آهنگ به پلی‌لیست
    private val pickAudioMulti = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) {
            val added = ArrayList<Track>()
            for (uri in uris) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) { }
                added.add(Track(uri.toString(), trackName(uri), 0))
            }
            val list = loadPlaylist()
            list.addAll(added)
            fillDurationsAsync(list)
            Toast.makeText(this, faDigits("" + added.size) + " آهنگ به پلی‌لیست اضافه شد", Toast.LENGTH_SHORT).show()
        }
    }

    private val notifPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    private val recordAudioLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    // دریافت خبر هشدار ۱۰ ثانیه‌ای از سرویس
    private val alertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra("state") ?: return
            if (state == "show") {
                val remainingMs = intent.getIntExtra("remaining_ms", 10000)
                runOnUiThread { showAlert(remainingMs) }
            } else if (state == "done") {
                runOnUiThread { dismissAlert() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("sleep_music", Context.MODE_PRIVATE)

        // راه‌اندازی اولیه: بار اول، قبل از ورود به صفحه‌ی اصلی، مجوزها را می‌گیریم
        if (!prefs.getBoolean("onboarding_done", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        bindViews()
        theme = effectiveTheme()
        applyTheme(theme)

        val savedUri = prefs.getString("music_uri", null)
        if (savedUri != null) updateMusicInfo(Uri.parse(savedUri))
        refreshStats()

        if (Build.VERSION.SDK_INT >= 33) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }

        btnMain.setOnClickListener { onMainButton() }
        btnStopAll.setOnClickListener {
            startService(Intent(this, MusicService::class.java).setAction(MusicService.ACTION_STOP))
            Toast.makeText(this, "توقف کامل شد؛ خاموشی امشب لغو شد", Toast.LENGTH_SHORT).show()
        }

        seekBar.max = 100
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) { }
            override fun onStartTrackingTouch(sb: SeekBar?) { seekDragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seekDragging = false
                val i = Intent(this@MainActivity, MusicService::class.java)
                i.action = MusicService.ACTION_SEEK
                i.putExtra(MusicService.EXTRA_SEEK_MS, (sb?.progress ?: 0) * 1000)
                try { startService(i) } catch (_: Exception) { }
            }
        })

        val filter = IntentFilter(MusicService.ALERT_ACTION)
        ContextCompat.registerReceiver(this, alertReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        MusicService.appInForeground = true
        maybeRefreshNightTheme()
        uiHandler.post(ticker)
        refreshStats()
        footerHandler.removeCallbacks(footerTicker)
        footerHandler.postDelayed(footerTicker, 4000)
    }

    override fun onPause() {
        super.onPause()
        MusicService.appInForeground = false
        uiHandler.removeCallbacks(ticker)
        footerHandler.removeCallbacks(footerTicker)
    }

    /** تغییر حالت روشن/تاریک گوشی وسط کار برنامه — بدون ساختن دوباره‌ی اکتیویتی */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        maybeRefreshNightTheme()
    }

    /** گوشی در حالت تاریک است؟ */
    private fun isSystemDarkMode(): Boolean {
        val mask = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mask == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** تم مؤثر: اگر «حالت شب خودکار» روشن باشد و گوشی تاریک باشد → تم «شب تیره»؛ وگرنه تم خود کاربر */
    private fun effectiveTheme(): AppTheme {
        val saved = ThemeRepo.byId(prefs.getString("theme_id", "blend"))
        return if (prefs.getBoolean("night_auto_enabled", false) && isSystemDarkMode()) ThemeRepo.byId("night") else saved
    }

    /** اگر تم مؤثر عوض شده، همین حالا اعمال کن (بدون بستن برنامه) */
    private fun maybeRefreshNightTheme() {
        if (!prefs.getBoolean("night_auto_enabled", false)) return
        val t = effectiveTheme()
        if (t.id != theme.id) {
            theme = t
            applyTheme(t)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(alertReceiver) } catch (_: Exception) { }
        dismissAlert()
        super.onDestroy()
    }

    private fun bindViews() {
        rootScroll = findViewById(R.id.rootScroll)
        headerFrame = findViewById(R.id.headerFrame)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        cardPlayer = findViewById(R.id.cardPlayer)
        iconMusicBox = findViewById(R.id.iconMusicBox)
        tvMusicLabel = findViewById(R.id.tvMusicLabel)
        tvSelectedMusic = findViewById(R.id.tvSelectedMusic)
        tvPlayPill = findViewById(R.id.tvPlayPill)
        equalizerView = findViewById(R.id.equalizerView)
        seekBar = findViewById(R.id.seekBar)
        tvTimeElapsed = findViewById(R.id.tvTimeElapsed)
        tvPlayerStatus = findViewById(R.id.tvPlayerStatus)
        tvTimeTotal = findViewById(R.id.tvTimeTotal)
        chipA = findViewById(R.id.chipA)
        chipAIcon = findViewById(R.id.chipAIcon)
        tvChipALabel = findViewById(R.id.tvChipALabel)
        tvChipAValue = findViewById(R.id.tvChipAValue)
        chipB = findViewById(R.id.chipB)
        chipBIcon = findViewById(R.id.chipBIcon)
        tvChipBLabel = findViewById(R.id.tvChipBLabel)
        tvChipBValue = findViewById(R.id.tvChipBValue)
        btnMain = findViewById(R.id.btnMain)
        tvBtnMain = findViewById(R.id.tvBtnMain)
        btnStopAll = findViewById(R.id.btnStopAll)
        cardStats = findViewById(R.id.cardStats)
        statsIcon = findViewById(R.id.statsIcon)
        tvStatsMain = findViewById(R.id.tvStatsMain)
        statsDots = findViewById(R.id.statsDots)
        tvFooter = findViewById(R.id.tvFooter)
        equalizerView.setPlaying(false)
        pressScale(btnMain); pressScale(btnStopAll); pressScale(cardStats)
        findViewById<View>(R.id.btnSettings).let { pressScale(it) }
    }

    /** دکمه بزرگ: پخش (تک‌آهنگ یا پلی‌لیست) + خاموشی بعد از پایان */
    private fun startPlayback() {
        if (!accessibilityEnabled()) {
            Toast.makeText(this, "برای خاموشی خودکار، اول سرویس دسترس‌پذیری را روشن کن", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, OnboardingActivity::class.java))
            return
        }
        val intent = Intent(this, MusicService::class.java)
        val playlist = loadPlaylist()
        if (playlist.isNotEmpty()) {
            intent.putExtra(MusicService.EXTRA_MODE, MusicService.MODE_PLAYLIST)
        } else {
            val uri = prefs.getString("music_uri", null)
            if (uri == null) {
                Toast.makeText(this, "اول یک آهنگ انتخاب کن", Toast.LENGTH_SHORT).show()
                return
            }
            intent.putExtra(MusicService.EXTRA_MODE, MusicService.MODE_SINGLE)
            intent.putExtra(MusicService.EXTRA_URI, uri)
        }
        warnIfMuted()
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "پخش شروع شد؛ درست بعد از پایان موسیقی گوشی خاموش می‌شود", Toast.LENGTH_LONG).show()
    }

    /** به‌روزرسانی زنده: دکمه‌ی یکپارچه، چیپ‌ها، نوار پیشرفت و رقص نور */
    private fun updatePlaybackUi() {
        val playing = MusicService.isPlaying
        val paused = MusicService.isPaused
        if (playing != lastPlaying) {
            lastPlaying = playing
            equalizerView.setPlaying(playing, MusicService.audioSessionId)
        }
        val playlistNow = loadPlaylist()
        if (playing) {
            val playlistActive = MusicService.modeLive == MusicService.MODE_PLAYLIST && MusicService.trackCount > 0
            tvPlayerStatus.text = if (playlistActive && MusicService.trackCount > 1) {
                "آهنگ " + faDigits("" + (MusicService.trackIndex + 1)) + " از " + faDigits("" + MusicService.trackCount) + " در حال پخش…"
            } else {
                "در حال پخش…"
            }
            pill(tvPlayPill, theme.pillPlayBg, theme.pillPlayText)
            tvPlayPill.text = "در حال پخش"
            val dur = MusicService.durationSec
            if (dur > 0) {
                if (seekBar.max != dur) seekBar.max = dur
                tvTimeTotal.text = ThemeRepo.formatSecs(dur)
                if (!seekDragging) seekBar.progress = MusicService.positionSec.coerceIn(0, dur)
            }
            tvTimeElapsed.text = ThemeRepo.formatSecs(MusicService.positionSec)
            tvChipAValue.text = faDigits(ThemeRepo.formatSecs(MusicService.remainingSec)) + " دیگر"
            styleMainBtn("pause")
            btnStopAll.visibility = View.VISIBLE
        } else if (paused) {
            tvPlayerStatus.text = "در مکث — خاموشی هم معطل مانده"
            pill(tvPlayPill, theme.pillIdleBg, theme.pillIdleText)
            tvPlayPill.text = "مکث"
            // نوار پیشرفت و چیپ زمان همین‌جا فریز می‌مانند؛ دست نمی‌زنیم
            tvChipAValue.text = faDigits(ThemeRepo.formatSecs(MusicService.remainingSec)) + " دیگر"
            styleMainBtn("resume")
            btnStopAll.visibility = View.VISIBLE
        } else {
            tvPlayerStatus.text = "آماده برای پخش"
            pill(tvPlayPill, theme.pillIdleBg, theme.pillIdleText)
            tvPlayPill.text = "آماده"
            if (!seekDragging) {
                seekBar.progress = 0
                tvTimeElapsed.text = "00:00"
            }
            tvChipAValue.text = faDigits(ThemeRepo.formatSecs(idleTotalSec())) + " دیگر"
            styleMainBtn("play")
            btnStopAll.visibility = View.GONE
        }
        tvChipBValue.text = if (playlistNow.size > 1) {
            "پلی‌لیست " + faDigits("" + playlistNow.size) + " آهنگی"
        } else {
            "بعد از پایان موسیقی"
        }
    }

    /** دکمه‌ی یکپارچه: لمس بر اساس حالت فعلی پخش تصمیم می‌گیرد */
    private fun onMainButton() {
        when {
            MusicService.isPlaying -> {
                startService(Intent(this, MusicService::class.java).setAction(MusicService.ACTION_PAUSE))
                Toast.makeText(this, "در مکث؛ چیپ «زمان خاموش شدن» فریز شد", Toast.LENGTH_SHORT).show()
            }
            MusicService.isPaused -> {
                startService(Intent(this, MusicService::class.java).setAction(MusicService.ACTION_RESUME))
                Toast.makeText(this, "ادامه از همان ثانیه؛ شمارش خاموشی هم ادامه پیدا کرد", Toast.LENGTH_SHORT).show()
            }
            else -> startPlayback()
        }
    }

    /** حالت بصری دکمه‌ی یکپارچه: پخش ⇄ مکث ⇄ ادامه از همان ثانیه — با خط دور تم */
    private fun styleMainBtn(state: String) {
        btnMain.background = gradient(theme.btnColors, dp(18f), dp(2f).toInt(), borderColor())
        when (state) {
            "pause" -> tvBtnMain.text = "⏸ مکث"
            "resume" -> tvBtnMain.text = "▶ ادامه از " + faDigits(ThemeRepo.formatSecs(MusicService.positionSec))
            else -> tvBtnMain.text = "▶ پخش"
        }
    }

    private fun idleTotalSec(): Int {
        val pl = loadPlaylist()
        if (pl.isNotEmpty()) {
            var sum = 0
            for (tr in pl) sum += tr.durSec
            if (sum > 0) return sum
        }
        val stored = prefs.getInt("music_duration_sec", 0)
        return stored
    }

    /** نام و مدت زمان فایل انتخاب‌شده را در کارت پلیر نشان می‌دهد */
    private fun updateMusicInfo(uri: Uri) {
        tvSelectedMusic.text = trackName(uri)
        tvPlayerStatus.text = "آماده برای پخش"
        Thread {
            var durationSec = 0
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                val ms = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
                retriever.release()
                durationSec = (ms / 1000).toInt()
            } catch (_: Exception) { }
            runOnUiThread {
                prefs.edit().putInt("music_duration_sec", durationSec).apply()
                tvTimeTotal.text = if (durationSec > 0) ThemeRepo.formatSecs(durationSec) else "--:--"
                if (!MusicService.isPlaying) {
                    tvChipAValue.text = faDigits(ThemeRepo.formatSecs(idleTotalSec())) + " دیگر"
                }
            }
        }.start()
    }

    private fun trackName(uri: Uri): String {
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "آهنگ انتخاب شد"
    }

    private fun accessibilityEnabled(): Boolean = LockAccessibilityService.instance != null

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

    /* ================= پنجره تنظیمات (چرخ‌دنده ⚙️) ================= */

    private fun showSettingsDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_settings)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // تم روی همه‌ی پنجره‌ها — تنظیمات هم با پالت تم فعلی رندر می‌شود
        dialog.findViewById<LinearLayout>(R.id.dlgSettingsRoot)!!.background = roundRectStroke(theme.cardBg, dp(29f))
        dialog.findViewById<TextView>(R.id.dlgSettingsTitle)!!.setTextColor(theme.progressColor)
        dialog.findViewById<TextView>(R.id.dlgSettingsSub)!!.setTextColor(theme.textMuted)
        dialog.findViewById<TextView>(R.id.btnCloseSettings)!!.setTextColor(theme.progressColor)
        val tvPillTheme = dialog.findViewById<TextView>(R.id.tvPillTheme)
        pill(tvPillTheme, theme.rowIconBg, theme.rowIconColor)
        tvPillTheme.text = "۹ تم"
        val tvPillAlert = dialog.findViewById<TextView>(R.id.tvPillAlert)
        val tvPillStats = dialog.findViewById<TextView>(R.id.tvPillStats)
        val tvPillNight = dialog.findViewById<TextView>(R.id.tvPillNight)

        fun refreshPills() {
            val alertOn = prefs.getBoolean("alert_enabled", true)
            pill(tvPillAlert, if (alertOn) 0xFFD1FAE5.toInt() else 0xFFE5E7EB.toInt(), if (alertOn) 0xFF047857.toInt() else 0xFF6B7280.toInt())
            tvPillAlert.text = if (alertOn) "روشن" else "خاموش"
            val statsOn = prefs.getBoolean("stats_enabled", true)
            pill(tvPillStats, if (statsOn) 0xFFD1FAE5.toInt() else 0xFFE5E7EB.toInt(), if (statsOn) 0xFF047857.toInt() else 0xFF6B7280.toInt())
            tvPillStats.text = if (statsOn) "روشن" else "خاموش"
            val nightOn = prefs.getBoolean("night_auto_enabled", false)
            pill(tvPillNight, if (nightOn) 0xFFD1FAE5.toInt() else 0xFFE5E7EB.toInt(), if (nightOn) 0xFF047857.toInt() else 0xFF6B7280.toInt())
            tvPillNight.text = if (nightOn) "روشن" else "خاموش"
        }
        refreshPills()

        // خط دور همه ردیف‌ها + رنگ دایره‌های شماره از خود تم (دیگر بنفش ثابت نیست)
        for (rowId in intArrayOf(R.id.rowTheme, R.id.rowPlaylist, R.id.rowMusic, R.id.rowAlert, R.id.rowStats, R.id.rowNight)) {
            val row = dialog.findViewById<LinearLayout>(rowId)
            row.background = roundRectStroke(Color.WHITE, dp(18f))
            (row.getChildAt(0) as? TextView)?.background = ovalGradient(theme.btnColors)
        }

        dialog.findViewById<LinearLayout>(R.id.rowTheme).setOnClickListener {
            dialog.dismiss()
            showThemeDialog()
        }
        dialog.findViewById<LinearLayout>(R.id.rowPlaylist).setOnClickListener {
            dialog.dismiss()
            showPlaylistDialog()
        }
        dialog.findViewById<LinearLayout>(R.id.rowMusic).setOnClickListener {
            dialog.dismiss()
            pickAudio.launch(arrayOf("audio/*"))
        }
        dialog.findViewById<LinearLayout>(R.id.rowAlert).setOnClickListener {
            val next = !prefs.getBoolean("alert_enabled", true)
            prefs.edit().putBoolean("alert_enabled", next).apply()
            refreshPills()
        }
        dialog.findViewById<LinearLayout>(R.id.rowStats).setOnClickListener {
            val next = !prefs.getBoolean("stats_enabled", true)
            prefs.edit().putBoolean("stats_enabled", next).apply()
            cardStats.visibility = if (next) View.VISIBLE else View.GONE
            refreshPills()
        }
        dialog.findViewById<LinearLayout>(R.id.rowNight).setOnClickListener {
            val next = !prefs.getBoolean("night_auto_enabled", false)
            prefs.edit().putBoolean("night_auto_enabled", next).apply()
            refreshPills()
            maybeRefreshNightTheme()
        }
        dialog.findViewById<TextView>(R.id.btnCloseSettings).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /* ================= پنجره انتخاب تم (۶ تم) ================= */

    private fun showThemeDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_theme)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.findViewById<LinearLayout>(R.id.dlgThemeRoot)!!.background = roundRectStroke(theme.cardBg, dp(29f))
        dialog.findViewById<TextView>(R.id.dlgThemeTitle)!!.setTextColor(theme.progressColor)
        dialog.findViewById<TextView>(R.id.dlgThemeSub)!!.setTextColor(theme.textMuted)
        dialog.findViewById<TextView>(R.id.btnCloseTheme)!!.setTextColor(theme.progressColor)
        val container = dialog.findViewById<LinearLayout>(R.id.themeList)

        fun buildRows() {
            container.removeAllViews()
            val currentId = prefs.getString("theme_id", "blend")
            for (th in ThemeRepo.themes) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = android.view.Gravity.CENTER_VERTICAL
                row.setPadding(dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt())
                row.background = roundRectStroke(Color.WHITE, dp(18f))

                val num = TextView(this)
                num.text = th.num
                num.setTextColor(Color.WHITE)
                num.textSize = 14f
                num.gravity = android.view.Gravity.CENTER
                num.background = ovalGradient(th.btnColors)
                num.layoutParams = LinearLayout.LayoutParams(dp(32f).toInt(), dp(32f).toInt())
                row.addView(num)

                val mid = LinearLayout(this)
                mid.orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginStart = dp(10f).toInt()
                mid.layoutParams = lp

                val title = TextView(this)
                title.text = th.num + ". " + th.label + " " + th.emoji
                title.setTextColor(Color.parseColor("#1F2937"))
                title.textSize = 12.5f
                title.setTypeface(null, android.graphics.Typeface.BOLD)
                mid.addView(title)

                val dots = LinearLayout(this)
                dots.orientation = LinearLayout.HORIZONTAL
                val dlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                dlp.topMargin = dp(5f).toInt()
                dots.layoutParams = dlp
                for (colr in th.eqColors) {
                    val dot = View(this)
                    dot.background = circle(colr)
                    val vlp = LinearLayout.LayoutParams(dp(12f).toInt(), dp(12f).toInt())
                    vlp.marginEnd = dp(4f).toInt()
                    dot.layoutParams = vlp
                    dots.addView(dot)
                }
                mid.addView(dots)
                row.addView(mid)

                val end = TextView(this)
                if (th.id == currentId) {
                    end.text = "✓"
                    end.setTextColor(Color.parseColor("#16A34A"))
                    end.textSize = 16f
                } else {
                    end.text = "‹"
                    end.setTextColor(Color.parseColor("#C4B5FD"))
                    end.textSize = 14f
                }
                row.addView(end)

                row.setOnClickListener {
                    prefs.edit().putString("theme_id", th.id).apply()
                    theme = th
                    applyTheme(th)
                    buildRows()
                }
                val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                rlp.topMargin = dp(7f).toInt()
                container.addView(row, rlp)
            }
        }
        buildRows()
        dialog.findViewById<TextView>(R.id.btnCloseTheme).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /* ================= پنجره پلی‌لیست خواب ================= */

    private fun showPlaylistDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_playlist)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.findViewById<LinearLayout>(R.id.dlgPlaylistRoot)!!.background = roundRectStroke(theme.cardBg, dp(29f))
        dialog.findViewById<TextView>(R.id.dlgPlaylistTitle)!!.setTextColor(theme.progressColor)
        dialog.findViewById<TextView>(R.id.dlgPlaylistSub)!!.setTextColor(theme.textMuted)
        dialog.findViewById<TextView>(R.id.btnClosePlaylist)!!.setTextColor(theme.progressColor)
        val container = dialog.findViewById<LinearLayout>(R.id.playlistList)
        val tvTotal = dialog.findViewById<TextView>(R.id.tvPlaylistTotal)
        val btnAdd = dialog.findViewById<TextView>(R.id.btnAddTrack)
        tvTotal.setTextColor(theme.progressColor)
        btnAdd.setTextColor(theme.progressColor)

        fun refreshTotal(list: List<Track>) {
            var sum = 0
            for (tr in list) sum += tr.durSec
            if (list.isEmpty()) {
                tvTotal.text = "پلی‌لیست خالی است؛ آهنگ اضافه کن تا پشت‌سرهم پخش شود"
            } else {
                tvTotal.text = "مجموع پخش: " + faDigits(ThemeRepo.formatSecs(sum)) + " ← بعد از آخرین آهنگ، گوشی خاموش می‌شود"
            }
        }

        fun buildRows() {
            container.removeAllViews()
            val list = loadPlaylist()
            for ((idx, tr) in list.withIndex()) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = android.view.Gravity.CENTER_VERTICAL
                row.setPadding(dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt())
                row.background = roundRectStroke(Color.WHITE, dp(18f))

                val num = TextView(this)
                num.text = faDigits("" + (idx + 1))
                num.setTextColor(Color.WHITE)
                num.textSize = 13f
                num.gravity = android.view.Gravity.CENTER
                num.background = ovalGradient(theme.btnColors)
                num.layoutParams = LinearLayout.LayoutParams(dp(30f).toInt(), dp(30f).toInt())
                row.addView(num)

                val mid = LinearLayout(this)
                mid.orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginStart = dp(10f).toInt()
                mid.layoutParams = lp

                val name = TextView(this)
                name.text = tr.name
                name.setTextColor(Color.parseColor("#1F2937"))
                name.textSize = 11.5f
                name.setTypeface(null, android.graphics.Typeface.BOLD)
                name.maxLines = 1
                name.ellipsize = android.text.TextUtils.TruncateAt.END
                mid.addView(name)

                val pills = LinearLayout(this)
                pills.orientation = LinearLayout.HORIZONTAL
                val plp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                plp.topMargin = dp(4f).toInt()
                pills.layoutParams = plp
                val dur = TextView(this)
                dur.text = if (tr.durSec > 0) faDigits(ThemeRepo.formatSecs(tr.durSec)) else "…"
                pills.addView(miniPill(dur, theme.chipABg, theme.chipAIcon))
                val nth = TextView(this)
                nth.text = "آهنگ " + faDigits("" + (idx + 1))
                val nlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                nlp.marginStart = dp(5f).toInt()
                nth.layoutParams = nlp
                pills.addView(miniPill(nth, theme.rowIconBg, theme.rowIconColor))
                mid.addView(pills)
                row.addView(mid)

                val del = TextView(this)
                del.text = "✕"
                del.setTextColor(Color.parseColor("#E11D48"))
                del.textSize = 14f
                del.setPadding(dp(8f).toInt(), dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt())
                del.setOnClickListener {
                    val list2 = loadPlaylist()
                    if (idx < list2.size) {
                        list2.removeAt(idx)
                        savePlaylist(list2)
                        buildRows()
                        refreshTotal(list2)
                    }
                }
                row.addView(del)

                val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                rlp.topMargin = dp(7f).toInt()
                container.addView(row, rlp)
            }
            refreshTotal(list)
        }

        btnAdd.setOnClickListener {
            pickAudioMulti.launch(arrayOf("audio/*"))
        }
        // وقتی انتخاب فایل‌ها برگشت، پنجره را تازه می‌کنیم
        playlistRefresh = { buildRows() }
        buildRows()
        dialog.findViewById<TextView>(R.id.btnClosePlaylist).setOnClickListener {
            playlistRefresh = null
            dialog.dismiss()
        }
        dialog.setOnDismissListener { playlistRefresh = null }
        dialog.show()
        if (playlistPendingRefresh) {
            playlistPendingRefresh = false
            buildRows()
        }
    }

    private var playlistRefresh: (() -> Unit)? = null
    private var playlistPendingRefresh = false

    private fun loadPlaylist(): MutableList<Track> {
        val raw = prefs.getString("playlist_json", null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Track>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Track(o.optString("u", ""), o.optString("n", "آهنگ"), o.optInt("d", 0)))
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun savePlaylist(list: List<Track>) {
        try {
            val arr = JSONArray()
            for (tr in list) {
                val o = JSONObject()
                o.put("u", tr.uri)
                o.put("n", tr.name)
                o.put("d", tr.durSec)
                arr.put(o)
            }
            prefs.edit().putString("playlist_json", arr.toString()).apply()
        } catch (_: Exception) { }
    }

    /** مدت آهنگ‌های تازه‌اضافه را در پس‌زمینه می‌خواند و ذخیره می‌کند */
    private fun fillDurationsAsync(list: MutableList<Track>) {
        Thread {
            for ((idx, tr) in list.withIndex()) {
                if (tr.durSec > 0) continue
                var dur = 0
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(this, Uri.parse(tr.uri))
                    val ms = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                    retriever.release()
                    dur = (ms / 1000).toInt()
                } catch (_: Exception) { }
                if (idx < list.size) list[idx] = tr.copy(durSec = dur)
            }
            runOnUiThread {
                savePlaylist(list)
                if (playlistRefresh != null) playlistRefresh?.invoke() else playlistPendingRefresh = true
            }
        }.start()
    }

    /* ================= هشدار ۱۰ ثانیه‌ای ================= */

    /**
     * پاپ‌آپ هشدار بدون واسطه‌ی برادکست: از عدد زنده‌ی «alertRemainingMs» سرویس تغذیه می‌شود.
     * تا اگر برادکست روی بعضی رام‌ها گم شد، پاپ‌آپ باز هم از راه برسد؛
     * و اگر هشدار لغو/تمام شد، خودش بسته می‌شود.
     */
    private fun checkAlertPolling() {
        val arm = MusicService.alertRemainingMs
        val showing = alertDialog != null && alertDialog!!.isShowing
        if (arm > 0 && !showing) {
            showAlert(arm.toInt().coerceIn(1, 10000))
        } else if (arm <= 0 && showing) {
            dismissAlert()
        }
    }

    private fun showAlert(remainingMs: Int) {
        if (isFinishing || isDestroyed) return
        if (alertDialog != null && alertDialog!!.isShowing) return
        val dialog = AlertDialog.Builder(this, R.style.Theme_SleepMusic).create()
        dialog.setContentView(R.layout.dialog_alert)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.findViewById<LinearLayout>(R.id.dlgAlertRoot)!!.background = roundRectStroke(theme.cardBg, dp(22f))
        dialog.findViewById<TextView>(R.id.dlgAlertTitle)!!.setTextColor(theme.textPrimary)
        dialog.findViewById<TextView>(R.id.dlgAlertSub)!!.setTextColor(theme.textMuted)
        dialog.findViewById<TextView>(R.id.btnAlertCancel)!!.background = gradient(theme.btnColors, dp(14f), dp(2f).toInt(), borderColor())
        dialog.findViewById<TextView>(R.id.btnAlertNow)!!.setTextColor(theme.textMuted)
        val bar = dialog.findViewById<View>(R.id.alertBar)
        alertBar = bar
        bar?.scaleX = 1f
        bar?.pivotX = 0f
        alertAnimator?.cancel()
        alertAnimator = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            duration = remainingMs.toLong().coerceAtLeast(1000L)
            addUpdateListener { animator ->
                bar?.scaleX = animator.animatedValue as Float
            }
        }
        alertAnimator?.start()

        dialog.findViewById<TextView>(R.id.btnAlertCancel)!!.setOnClickListener {
            dismissAlert()
            startService(Intent(this, MusicService::class.java).setAction(MusicService.ACTION_CANCEL_SHUTDOWN))
        }
        dialog.findViewById<TextView>(R.id.btnAlertNow)!!.setOnClickListener {
            dismissAlert()
            startService(Intent(this, MusicService::class.java).setAction(MusicService.ACTION_SHUTDOWN_NOW))
        }
        dialog.setOnDismissListener {
            alertAnimator?.cancel()
            alertAnimator = null
        }
        alertDialog = dialog
        dialog.show()
    }

    private fun dismissAlert() {
        alertAnimator?.cancel()
        alertAnimator = null
        try { alertDialog?.dismiss() } catch (_: Exception) { }
        alertDialog = null
        alertBar = null
    }

    /* ================= آمار شب‌های آرام ================= */

    private fun refreshStats() {
        if (!this::cardStats.isInitialized) return
        cardStats.visibility = if (prefs.getBoolean("stats_enabled", true)) View.VISIBLE else View.GONE
        val streak = prefs.getInt("streak_nights", 0)
        tvStatsMain.text = faDigits("" + streak) + " شب پشت‌سرهم با خواب‌موزیک خوابیدی 🌙"
        buildStatsDots(streak)
    }

    private fun buildStatsDots(streak: Int) {
        statsDots.removeAllViews()
        val letters: Map<Int, String> = mapOf(
            7 to "ش", 1 to "ی", 2 to "د", 3 to "س", 4 to "چ", 5 to "پ", 6 to "ج"
        )
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = midnight(Calendar.getInstance())
        var lastDay: Calendar? = null
        val lastRaw = prefs.getString("last_success_day", "")
        if (lastRaw != null && streak > 0) {
            try {
                val cal = Calendar.getInstance()
                cal.time = fmt.parse(lastRaw)!!
                lastDay = midnight(cal)
            } catch (_: Exception) { }
        }
        for (offset in 6 downTo 0) {
            val day = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -offset) }
            var filled = false
            if (lastDay != null && streak > 0) {
                val first = (lastDay.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -(streak - 1)) }
                filled = !day.before(first) && !day.after(lastDay)
            }
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = android.view.Gravity.CENTER
            val clp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            col.layoutParams = clp

            val dot = View(this)
            if (filled) {
                dot.background = circleGradient(intArrayOf(Color.parseColor("#FB923C"), Color.parseColor("#F43F5E")))
            } else {
                dot.background = circleHollow(theme.textFaint)
            }
            val dlp = LinearLayout.LayoutParams(dp(17f).toInt(), dp(17f).toInt())
            dot.layoutParams = dlp
            col.addView(dot)

            val letter = TextView(this)
            letter.text = letters[day.get(Calendar.DAY_OF_WEEK)] ?: ""
            letter.textSize = 8f
            letter.setTypeface(null, android.graphics.Typeface.BOLD)
            letter.setTextColor(if (filled) Color.parseColor("#EA580C") else theme.textFaint)
            val llp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            llp.topMargin = dp(3f).toInt()
            letter.layoutParams = llp
            col.addView(letter)
            statsDots.addView(col)
        }
    }

    private fun midnight(cal: Calendar): Calendar {
        val c = cal.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c
    }

    /* ================= اعمال تم ================= */

    private fun applyTheme(t: AppTheme) {
        window.statusBarColor = t.statusBar
        window.navigationBarColor = t.bodyBg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
        }

        rootScroll.setBackgroundColor(t.bodyBg)
        headerFrame.background = gradient(t.headerColors)

        cardPlayer.background = roundRectStroke(t.cardBg, dp(26f))
        iconMusicBox.background = roundRectStroke(t.rowIconBg, dp(14f))
        tvMusicLabel.setTextColor(t.textMuted)
        tvSelectedMusic.setTextColor(t.textPrimary)
        seekBar.progressTintList = ColorStateList.valueOf(t.progressColor)
        seekBar.progressBackgroundTintList = ColorStateList.valueOf(t.textFaint)
        seekBar.thumb = thumbDrawable(t)
        tvTimeElapsed.setTextColor(t.textMuted)
        tvPlayerStatus.setTextColor(t.textMuted)
        tvTimeTotal.setTextColor(t.textMuted)

        chipA.background = roundRectStroke(t.chipABg, dp(18f))
        chipAIcon.background = roundRectStroke(Color.WHITE, dp(12f))
        chipAIcon.setTextColor(t.chipAIcon)
        tvChipALabel.setTextColor(t.textFaint)
        tvChipAValue.setTextColor(t.textPrimary)
        chipB.background = roundRectStroke(t.chipBBg, dp(18f))
        chipBIcon.background = roundRectStroke(Color.WHITE, dp(12f))
        chipBIcon.setTextColor(t.chipBIcon)
        tvChipBLabel.setTextColor(t.textFaint)
        tvChipBValue.setTextColor(t.textPrimary)

        styleMainBtn(if (MusicService.isPlaying) "pause" else if (MusicService.isPaused) "resume" else "play")
        btnStopAll.visibility = if (MusicService.isPlaying || MusicService.isPaused) View.VISIBLE else View.GONE

        cardStats.background = roundRectStroke(t.cardBg, dp(20f))
        statsIcon.background = roundRectStroke(Color.parseColor("#FFF0E3"), dp(14f))
        tvStatsMain.setTextColor(t.textPrimary)
        tvFooter.setTextColor(t.textMuted)
        tvSubtitle.setTextColor(Color.parseColor("#F0E3FB"))

        equalizerView.setPalette(t.eqColors)
        if (this::statsDots.isInitialized) buildStatsDots(prefs.getInt("streak_nights", 0))
    }

    /* ================= کمکی‌ها ================= */

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** افکت لمس: کوچک شدن ملایم کارت هنگام فشردن */
    private fun pressScale(v: View) {
        v.setOnTouchListener { vv, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    vv.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    vv.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    private fun gradient(
        colors: IntArray,
        radiusPx: Float = 0f,
        strokeW: Int = 0,
        strokeColor: Int = 0
    ): GradientDrawable {
        val gd = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        if (radiusPx > 0f) gd.cornerRadius = radiusPx
        if (strokeW > 0) gd.setStroke(strokeW, strokeColor)
        return gd
    }

    /** رنگ خط دور کارت‌ها و دکمه‌ها — از رنگ خود تم؛ واضح ولی نه خیلی کلفت */
    private fun borderColor(): Int = Color.argb(140, Color.red(theme.progressColor), Color.green(theme.progressColor), Color.blue(theme.progressColor))

    /** مستطیل گرد با خط دور ۲dp به رنگ تم — درخواست کاربر: دور تا دور همه کارت‌ها */
    private fun roundRectStroke(color: Int, radiusPx: Float): GradientDrawable {
        val gd = roundRect(color, radiusPx)
        gd.setStroke(dp(2f).toInt(), borderColor())
        return gd
    }

    /** دسته‌ی سفید ۲۰dp با دور رنگی تم — روی کارت سفید کاملا دیده می‌شود */
    private fun thumbDrawable(t: AppTheme): GradientDrawable {
        val gd = GradientDrawable()
        gd.shape = GradientDrawable.OVAL
        gd.setColor(Color.WHITE)
        gd.setSize(dp(20f).toInt(), dp(20f).toInt())
        gd.setStroke(dp(2.5f).toInt(), t.progressColor)
        return gd
    }

    private fun ovalGradient(colors: IntArray): GradientDrawable {
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            shape = GradientDrawable.OVAL
        }
    }

    private fun roundRect(color: Int, radiusPx: Float): GradientDrawable {
        val gd = GradientDrawable()
        gd.shape = GradientDrawable.RECTANGLE
        gd.setColor(color)
        gd.cornerRadius = radiusPx
        return gd
    }

    private fun circle(color: Int): GradientDrawable {
        val gd = GradientDrawable()
        gd.shape = GradientDrawable.OVAL
        gd.setColor(color)
        return gd
    }

    private fun circleGradient(colors: IntArray): GradientDrawable {
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            shape = GradientDrawable.OVAL
        }
    }

    private fun circleHollow(color: Int): GradientDrawable {
        val gd = GradientDrawable()
        gd.shape = GradientDrawable.OVAL
        gd.setColor(Color.TRANSPARENT)
        gd.setStroke(dp(2f).toInt(), color)
        return gd
    }

    private fun pill(tv: TextView, bg: Int, fg: Int) {
        val gd = roundRect(bg, dp(99f))
        gd.setStroke(dp(1.5f).toInt(), borderColor())
        tv.background = gd
        tv.setTextColor(fg)
    }

    private fun miniPill(tv: TextView, bg: Int, fg: Int): TextView {
        tv.background = roundRect(bg, dp(99f))
        tv.setTextColor(fg)
        tv.textSize = 8f
        tv.setTypeface(null, android.graphics.Typeface.BOLD)
        tv.setPadding(dp(7f).toInt(), dp(3f).toInt(), dp(7f).toInt(), dp(3f).toInt())
        return tv
    }

    private fun faDigits(s: String): String = ThemeRepo.faDigits(s)
}

/** یک آهنگ از پلی‌لیست */
data class Track(
    val uri: String,
    val name: String,
    val durSec: Int
)
