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

/**
 * صفحه اصلی — دقیقاً مطابق طراحی تأییدشده:
 * - هدر با تنها یک دکمه: چرخ‌دنده تنظیمات (تم، پلی‌لیست، موسیقی، هشدار، آمار، دسترسی‌پذیری)
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

    // دکمه بزرگ و ردیف‌ها
    private lateinit var btnBig: LinearLayout
    private lateinit var tvBigMain: TextView
    private lateinit var tvBigSub: TextView
    private lateinit var rowPick: LinearLayout
    private lateinit var rowPickIcon: TextView
    private lateinit var rowPickLabel: TextView
    private lateinit var rowPlaylist: LinearLayout
    private lateinit var rowPlaylistIcon: TextView
    private lateinit var tvPlaylistTitle: TextView
    private lateinit var tvPlaylistSub: TextView
    private lateinit var cardStats: LinearLayout
    private lateinit var statsIcon: TextView
    private lateinit var tvStatsMain: TextView
    private lateinit var tvStatsSub: TextView
    private lateinit var statsDots: LinearLayout
    private lateinit var tvFooter: TextView

    private var lastPlaying = false
    private var seekDragging = false

    private var alertDialog: AlertDialog? = null
    private var alertBar: View? = null
    private var alertAnimator: android.animation.ValueAnimator? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updatePlaybackUi()
            uiHandler.postDelayed(this, 500)
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
        theme = ThemeRepo.byId(prefs.getString("theme_id", "blend"))
        applyTheme(theme)

        val savedUri = prefs.getString("music_uri", null)
        if (savedUri != null) updateMusicInfo(Uri.parse(savedUri))
        refreshPlaylistRow()
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
        rowPick.setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }
        rowPlaylist.setOnClickListener { showPlaylistDialog() }

        btnBig.setOnClickListener {
            if (MusicService.isPlaying) {
                // توقف دستی = زنجیره‌ی امشب کامل نشده
                startService(Intent(this, MusicService::class.java).setAction(MusicService.ACTION_STOP))
                Toast.makeText(this, "پخش متوقف شد", Toast.LENGTH_SHORT).show()
            } else {
                startPlayback()
            }
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
        uiHandler.post(ticker)
        refreshStats()
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(ticker)
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
        btnBig = findViewById(R.id.btnBig)
        tvBigMain = findViewById(R.id.tvBigMain)
        tvBigSub = findViewById(R.id.tvBigSub)
        rowPick = findViewById(R.id.rowPick)
        rowPickIcon = findViewById(R.id.rowPickIcon)
        rowPickLabel = findViewById(R.id.rowPickLabel)
        rowPlaylist = findViewById(R.id.rowPlaylist)
        rowPlaylistIcon = findViewById(R.id.rowPlaylistIcon)
        tvPlaylistTitle = findViewById(R.id.tvPlaylistTitle)
        tvPlaylistSub = findViewById(R.id.tvPlaylistSub)
        cardStats = findViewById(R.id.cardStats)
        statsIcon = findViewById(R.id.statsIcon)
        tvStatsMain = findViewById(R.id.tvStatsMain)
        tvStatsSub = findViewById(R.id.tvStatsSub)
        statsDots = findViewById(R.id.statsDots)
        tvFooter = findViewById(R.id.tvFooter)
        equalizerView.setPlaying(false)
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

    /** به‌روزرسانی زنده: دکمه، چیپ‌ها، نوار پیشرفت و رقص نور */
    private fun updatePlaybackUi() {
        val playing = MusicService.isPlaying
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
            tvBigMain.text = "■   توقف پخش"
            tvBigSub.text = "لغو خاموشی خودکار"
            val dur = MusicService.durationSec
            if (dur > 0) {
                seekBar.max = dur
                tvTimeTotal.text = ThemeRepo.formatSecs(dur)
                if (!seekDragging) seekBar.progress = MusicService.positionSec.coerceIn(0, dur)
            }
            tvTimeElapsed.text = ThemeRepo.formatSecs(MusicService.positionSec)
            tvChipAValue.text = faDigits(ThemeRepo.formatSecs(MusicService.remainingSec)) + " دیگر"
        } else {
            tvPlayerStatus.text = "آماده برای پخش"
            pill(tvPlayPill, theme.pillIdleBg, theme.pillIdleText)
            tvPlayPill.text = "آماده"
            tvBigMain.text = "▶   پخش"
            tvBigSub.text = faDigits(ThemeRepo.formatSecs(idleTotalSec())) + " تا خاموش شدن گوشی"
            if (!seekDragging) {
                seekBar.progress = 0
                tvTimeElapsed.text = "00:00"
            }
            tvChipAValue.text = faDigits(ThemeRepo.formatSecs(idleTotalSec())) + " دیگر"
        }
        tvChipBValue.text = if (playlistNow.size > 1) {
            "پلی‌لیست " + faDigits("" + playlistNow.size) + " آهنگی"
        } else {
            "بعد از پایان موسیقی"
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

        val tvPillAlert = dialog.findViewById<TextView>(R.id.tvPillAlert)
        val tvPillStats = dialog.findViewById<TextView>(R.id.tvPillStats)
        val tvPillAccess = dialog.findViewById<TextView>(R.id.tvPillAccess)

        fun refreshPills() {
            val alertOn = prefs.getBoolean("alert_enabled", true)
            pill(tvPillAlert, if (alertOn) 0xFFD1FAE5.toInt() else 0xFFE5E7EB.toInt(), if (alertOn) 0xFF047857.toInt() else 0xFF6B7280.toInt())
            tvPillAlert.text = if (alertOn) "روشن" else "خاموش"
            val statsOn = prefs.getBoolean("stats_enabled", true)
            pill(tvPillStats, if (statsOn) 0xFFD1FAE5.toInt() else 0xFFE5E7EB.toInt(), if (statsOn) 0xFF047857.toInt() else 0xFF6B7280.toInt())
            tvPillStats.text = if (statsOn) "روشن" else "خاموش"
            val acc = accessibilityEnabled()
            pill(tvPillAccess, if (acc) 0xFFD1FAE5.toInt() else 0xFFFFF4EC.toInt(), if (acc) 0xFF047857.toInt() else 0xFFEA580C.toInt())
            tvPillAccess.text = if (acc) "فعال ✅" else "غیرفعال"
        }
        refreshPills()

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
        dialog.findViewById<LinearLayout>(R.id.rowAccess).setOnClickListener {
            if (accessibilityEnabled()) {
                Toast.makeText(this, "سرویس خاموشی فعال است ✅", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "«خواب‌موزیک» را پیدا کن و سرویس را روشن کن", Toast.LENGTH_LONG).show()
                try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) { }
            }
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
        val container = dialog.findViewById<LinearLayout>(R.id.themeList)

        fun buildRows() {
            container.removeAllViews()
            val currentId = prefs.getString("theme_id", "blend")
            for (th in ThemeRepo.themes) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = android.view.Gravity.CENTER_VERTICAL
                row.setPadding(dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt())
                row.background = roundRect(Color.WHITE, dp(18f))

                val num = TextView(this)
                num.text = th.num
                num.setTextColor(Color.WHITE)
                num.textSize = 14f
                num.gravity = android.view.Gravity.CENTER
                num.background = getDrawable(R.drawable.bg_num_circle)
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
        val container = dialog.findViewById<LinearLayout>(R.id.playlistList)
        val tvTotal = dialog.findViewById<TextView>(R.id.tvPlaylistTotal)
        val btnAdd = dialog.findViewById<TextView>(R.id.btnAddTrack)

        fun refreshTotal(list: List<Track>) {
            var sum = 0
            for (tr in list) sum += tr.durSec
            if (list.isEmpty()) {
                tvTotal.text = "پلی‌لیست خالی است؛ آهنگ اضافه کن تا پشت‌سرهم پخش شود"
            } else {
                tvTotal.text = "مجموع پخش: " + faDigits(ThemeRepo.formatSecs(sum)) + " ← بعد از آخرین آهنگ، گوشی خاموش می‌شود"
            }
            refreshPlaylistRow()
        }

        fun buildRows() {
            container.removeAllViews()
            val list = loadPlaylist()
            for ((idx, tr) in list.withIndex()) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = android.view.Gravity.CENTER_VERTICAL
                row.setPadding(dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt())
                row.background = roundRect(Color.WHITE, dp(18f))

                val num = TextView(this)
                num.text = faDigits("" + (idx + 1))
                num.setTextColor(Color.WHITE)
                num.textSize = 13f
                num.gravity = android.view.Gravity.CENTER
                num.background = getDrawable(R.drawable.bg_num_circle)
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
                pills.addView(miniPill(dur, Color.parseColor("#FFF4EC"), Color.parseColor("#EA580C")))
                val nth = TextView(this)
                nth.text = "آهنگ " + faDigits("" + (idx + 1))
                val nlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                nlp.marginStart = dp(5f).toInt()
                nth.layoutParams = nlp
                pills.addView(miniPill(nth, Color.parseColor("#F3EEFE"), Color.parseColor("#6D28D9")))
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
        refreshPlaylistRow()
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

    private fun refreshPlaylistRow() {
        if (!this::tvPlaylistSub.isInitialized) return
        val list = loadPlaylist()
        if (list.isEmpty()) {
            tvPlaylistSub.text = "چند آهنگ پشت‌سرهم تا خاموشی"
        } else {
            var sum = 0
            for (tr in list) sum += tr.durSec
            tvPlaylistSub.text = faDigits("" + list.size) + " آهنگ • " + faDigits(ThemeRepo.formatSecs(sum)) + " تا خاموشی"
        }
    }

    /* ================= هشدار ۱۰ ثانیه‌ای ================= */

    private fun showAlert(remainingMs: Int) {
        if (isFinishing || isDestroyed) return
        if (alertDialog != null && alertDialog!!.isShowing) return
        val dialog = AlertDialog.Builder(this, R.style.Theme.SleepMusic).create()
        dialog.setContentView(R.layout.dialog_alert)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
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

        dialog.findViewById<TextView>(R.id.btnAlertCancel).setOnClickListener {
            dismissAlert()
            startService(Intent(this, MusicService::class.java).setAction(MusicService.ACTION_CANCEL_SHUTDOWN))
        }
        dialog.findViewById<TextView>(R.id.btnAlertNow).setOnClickListener {
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
        val best = prefs.getInt("best_record", 0)
        tvStatsMain.text = faDigits("" + streak) + " شب پشت‌سرهم با خواب‌موزیک خوابیدی 🌙"
        tvStatsSub.text = "بهترین رکورد: " + faDigits("" + best) + " شب — فقط داخل خود گوشی ذخیره می‌شود"
        buildStatsDots(streak)
    }

    private fun buildStatsDots(streak: Int) {
        statsDots.removeAllViews()
        val letters = mapOf(
            7 to "ش", 1 to "ی", 2 to "د", 3 to "س", 4 to "چ", 5 to "پ", 6 to "ج"
        )
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
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

        cardPlayer.background = roundRect(t.cardBg, dp(26f))
        iconMusicBox.background = roundRect(t.rowIconBg, dp(14f))
        tvMusicLabel.setTextColor(t.textFaint)
        tvSelectedMusic.setTextColor(t.textPrimary)
        seekBar.progressTintList = ColorStateList.valueOf(t.progressColor)
        seekBar.progressBackgroundTintList = ColorStateList.valueOf(t.textFaint)
        seekBar.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        tvTimeElapsed.setTextColor(t.textMuted)
        tvPlayerStatus.setTextColor(t.textFaint)
        tvTimeTotal.setTextColor(t.textMuted)

        chipA.background = roundRect(t.chipABg, dp(18f))
        chipAIcon.background = roundRect(Color.WHITE, dp(12f))
        chipAIcon.setTextColor(t.chipAIcon)
        tvChipALabel.setTextColor(t.textFaint)
        tvChipAValue.setTextColor(t.textPrimary)
        chipB.background = roundRect(t.chipBBg, dp(18f))
        chipBIcon.background = roundRect(Color.WHITE, dp(12f))
        chipBIcon.setTextColor(t.chipBIcon)
        tvChipBLabel.setTextColor(t.textFaint)
        tvChipBValue.setTextColor(t.textPrimary)

        btnBig.background = gradient(t.btnColors, dp(24f))
        btnBig.elevation = dp(6f)
        tvBigMain.setTextColor(Color.WHITE)
        tvBigSub.setTextColor(Color.parseColor("#EAFBF3"))

        rowPick.background = roundRect(t.cardBg, dp(20f))
        rowPickIcon.background = roundRect(t.rowIconBg, dp(14f))
        rowPickLabel.setTextColor(t.textPrimary)
        rowPlaylist.background = roundRect(t.cardBg, dp(20f))
        rowPlaylistIcon.background = roundRect(t.rowIconBg, dp(14f))
        tvPlaylistTitle.setTextColor(t.textPrimary)
        tvPlaylistSub.setTextColor(t.textMuted)

        cardStats.background = roundRect(t.cardBg, dp(20f))
        statsIcon.background = roundRect(Color.parseColor("#FFF0E3"), dp(14f))
        tvStatsMain.setTextColor(t.textPrimary)
        tvStatsSub.setTextColor(t.textMuted)
        tvFooter.setTextColor(t.footer)
        tvSubtitle.setTextColor(Color.parseColor("#F0E3FB"))

        equalizerView.setPalette(t.eqColors)
        if (this::statsDots.isInitialized) buildStatsDots(prefs.getInt("streak_nights", 0))
    }

    /* ================= کمکی‌ها ================= */

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun gradient(colors: IntArray, radiusPx: Float = 0f): GradientDrawable {
        val gd = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        if (radiusPx > 0f) gd.cornerRadius = radiusPx
        return gd
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
        tv.background = roundRect(bg, dp(99f))
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
