package ir.sleepmusic.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * راه‌اندازی اولیه — قبل از ورود به صفحه‌ی اصلی برنامه:
 * قدم ۱: شروع خودکار (Autostart) → قدم ۲: محدود نکردن مصرف باتری → قدم ۳: دسترس‌پذیری
 * هر قدم که تمام شد، خودش می‌رود سراغ قدم بعدی؛ بعد از هر سه قدم وارد برنامه می‌شویم.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var cardStep1: View
    private lateinit var cardStep2: View
    private lateinit var cardStep3: View
    private lateinit var badge1: TextView
    private lateinit var badge2: TextView
    private lateinit var badge3: TextView
    private lateinit var tvState1: TextView
    private lateinit var tvState2: TextView
    private lateinit var tvState3: TextView
    private lateinit var tvOnboardHint: TextView
    private lateinit var btnEnter: Button

    private var waitingAutostart = false
    private var autoAdvancedBattery = false
    private var autoAdvancedAccessibility = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        prefs = getSharedPreferences("sleep_music", Context.MODE_PRIVATE)

        cardStep1 = findViewById(R.id.stepAutostart)
        cardStep2 = findViewById(R.id.stepBattery)
        cardStep3 = findViewById(R.id.stepAccessibility)
        badge1 = findViewById(R.id.tvStepAutostartBadge)
        badge2 = findViewById(R.id.tvStepBatteryBadge)
        badge3 = findViewById(R.id.tvStepAccessibilityBadge)
        tvState1 = findViewById(R.id.tvStepAutostartState)
        tvState2 = findViewById(R.id.tvStepBatteryState)
        tvState3 = findViewById(R.id.tvStepAccessibilityState)
        tvOnboardHint = findViewById(R.id.tvOnboardHint)
        btnEnter = findViewById(R.id.btnEnter)

        // قدم‌ها به ترتیب باز می‌شوند؛ قدم بعدی فقط بعد از تمام شدن قدم قبلی
        cardStep1.setOnClickListener { if (!step1Done()) openAutostartSettings() }
        cardStep2.setOnClickListener { if (step1Done() && !step2Done()) openBatterySettings() }
        cardStep3.setOnClickListener { if (step2Done() && !step3Done()) openAccessibilitySettings() }

        btnEnter.setOnClickListener { finishOnboarding(true) }
        findViewById<View>(R.id.tvSkip).setOnClickListener { finishOnboarding(false) }
    }

    override fun onResume() {
        super.onResume()
        // وقتی از صفحه‌ی شروع خودکار برگشت، قدم ۱ را «انجام‌شده» علامت می‌زنیم
        if (waitingAutostart) {
            waitingAutostart = false
            prefs.edit().putBoolean("autostart_done", true).apply()
        }
        refreshUi()

        // پیشروی خودکار: قدم تمام شد → قدم بعدی خودش باز می‌شود
        if (step1Done() && !step2Done() && !autoAdvancedBattery) {
            autoAdvancedBattery = true
            uiHandler.postDelayed({
                if (!isFinishing && !step2Done()) openBatterySettings()
            }, 600)
        } else if (step2Done() && !step3Done() && !autoAdvancedAccessibility) {
            autoAdvancedAccessibility = true
            uiHandler.postDelayed({
                if (!isFinishing && !step3Done()) openAccessibilitySettings()
            }, 600)
        }
    }

    private fun step1Done(): Boolean = prefs.getBoolean("autostart_done", false)

    private fun step2Done(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun step3Done(): Boolean = LockAccessibilityService.instance != null

    private fun refreshUi() {
        val s1 = step1Done()
        val s2 = step2Done()
        val s3 = step3Done()

        markDone(badge1, tvState1, s1, "۱")
        markDone(badge2, tvState2, s2, "۲")
        markDone(badge3, tvState3, s3, "۳")

        // قدم‌های هنوز باز نشده کم‌رنگ می‌شوند
        cardStep2.alpha = if (s1) 1f else 0.45f
        cardStep3.alpha = if (s2) 1f else 0.45f

        if (s1 && s2 && s3) {
            tvOnboardHint.text = "همه‌چیز آماده‌ست ✓ حالا می‌تونی وارد برنامه بشی"
            btnEnter.visibility = View.VISIBLE
        } else {
            val remaining = listOf(s1, s2, s3).count { !it }
            tvOnboardHint.text = "" + remaining + " قدم مانده؛ هر قدم که تموم شد خودش می‌ره قدم بعدی"
            btnEnter.visibility = View.GONE
        }
    }

    private fun markDone(badge: TextView, state: TextView, done: Boolean, number: String) {
        if (done) {
            badge.text = "✓"
            badge.backgroundTintList = ColorStateList.valueOf(0xFFDFF3E7.toInt())
            badge.setTextColor(0xFF1E7B45.toInt())
            state.text = "انجام شد ✓"
            state.setTextColor(0xFF1E7B45.toInt())
        } else {
            badge.text = number
            badge.backgroundTintList = ColorStateList.valueOf(0xFFEFE4FA.toInt())
            badge.setTextColor(0xFF6D28B8.toInt())
            state.text = "تنظیم   ‹"
            state.setTextColor(0xFF8E4FD0.toInt())
        }
    }

    private fun finishOnboarding(allDone: Boolean) {
        prefs.edit().putBoolean("onboarding_done", true).apply()
        if (allDone) {
            Toast.makeText(this, "همه‌چیز آماده است؛ خواب‌موزیک آماده‌ی کاره", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "هر وقت خواستی از دکمه‌ی «راه‌اندازی» می‌تونی تنظیم کنی", Toast.LENGTH_LONG).show()
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /** قدم ۱: صفحه‌ی شروع خودکار MIUI */
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
        if (step2Done()) return
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
        if (step3Done()) return
        Toast.makeText(this, "«خواب‌موزیک» را پیدا کن و سرویس «قفل صفحه هوشمند» را روشن کن", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "صفحه‌ی دسترس‌پذیری پیدا نشد", Toast.LENGTH_SHORT).show()
        }
    }
}
