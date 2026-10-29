package ir.sleepmusic.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class LockAccessibilityService : AccessibilityService() {

    companion object {
        var instance: LockAccessibilityService? = null

        // وقتی true باشد، یعنی در حال جستجوی دکمه خاموش کردن در منوی سیستم هستیم
        @Volatile
        var powerMenuPending = false

        // عنوان دکمه خاموش کردن در زبان‌های مختلف
        private val POWER_OFF_KEYWORDS = listOf(
            "خاموش کردن", "خاموشی", "خاموشي", "power off", "shut down", "shutdown", "إيقاف التشغيل"
        )
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // هر وقت پنجره‌ای از سیستم (SystemUI) باز شد و منتظر منوی خاموشی بودیم، دنبال دکمه بگرد
        if (powerMenuPending && event?.packageName?.toString() == "com.android.systemui") {
            scanAndClick()
        }
    }

    override fun onInterrupt() { }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * خاموشی واقعی بدون روت:
     * ۱) اگر صفحه خاموش است، اول آن را بیدار می‌کنیم
     * ۲) منوی خاموشی سیستم را باز می‌کنیم
     * ۳) خودمان روی دکمه «خاموش کردن» کلیک می‌کنیم
     * ۴) اگر تا ۸ ثانیه دکمه پیدا نشد، به‌جایش صفحه را قفل می‌کنیم (حالت جایگزین)
     */
    fun tryPowerOffViaMenu(): Boolean {
        powerMenuPending = true

        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                val wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "SleepMusic:WakeForShutdown"
                )
                wakeLock.acquire(6000)
            }
        } catch (_: Exception) { }

        // کمی صبر برای روشن شدن صفحه، بعد باز کردن منوی خاموشی
        handler.postDelayed({
            if (powerMenuPending) performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        }, 1200)

        // منو کمی طول می‌کشد تا باز شود؛ در فواصل مختلف چند بار دنبال دکمه می‌گردیم
        handler.postDelayed({ if (powerMenuPending) scanAndClick() }, 2200)
        handler.postDelayed({ if (powerMenuPending) scanAndClick() }, 3200)
        // برای رام‌هایی که به‌جای کلیک، نگه‌داشتن می‌خواهند
        handler.postDelayed({ if (powerMenuPending) scanAndClick(longClick = true) }, 4600)
        handler.postDelayed({ if (powerMenuPending) scanAndClick() }, 5800)

        // حالت جایگزین: اگر هیچ دکمه‌ای پیدا نشد → حداقل صفحه را قفل کن
        handler.postDelayed({
            if (powerMenuPending) {
                powerMenuPending = false
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            }
        }, 8000)

        return true
    }

    private fun scanAndClick(longClick: Boolean = false) {
        if (!powerMenuPending) return
        val root = findSystemUiRoot() ?: return
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var checked = 0
        while (queue.isNotEmpty() && checked < 300) {
            val node = queue.removeFirst()
            checked++
            val label = ((node.text?.toString() ?: "") + " " +
                (node.contentDescription?.toString() ?: "")).lowercase()
            if (POWER_OFF_KEYWORDS.any { label.contains(it) }) {
                val clicked = if (longClick) {
                    node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) ||
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                        node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                }
                if (clicked) return
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
    }

    private fun findSystemUiRoot(): AccessibilityNodeInfo? {
        return try {
            for (window in windows) {
                val root = window.root ?: continue
                if (root.packageName?.toString() == "com.android.systemui") return root
            }
            rootInActiveWindow?.takeIf {
                it.packageName?.toString() == "com.android.systemui"
            }
        } catch (_: Exception) {
            null
        }
    }
}