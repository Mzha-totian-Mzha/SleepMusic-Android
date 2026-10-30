package ir.sleepmusic.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class LockAccessibilityService : AccessibilityService() {

    companion object {
        var instance: LockAccessibilityService? = null

        // وقتی true باشد، یعنی در حال کار روی منوی خاموشی سیستم هستیم
        @Volatile
        var powerMenuPending = false

        // کلیدواژه‌های دکمه/برچسب خاموشی در زبان‌های مختلف
        private val POWER_OFF_KEYWORDS = listOf(
            "خاموش کردن", "خاموشی", "خاموشي", "خاموش",
            "power off", "shut down", "shutdown", "turn off",
            "إيقاف التشغيل", "ايقاف التشغيل", "关机"
        )

        // کلیدواژه‌های دیالوگ تأیید (بعضی رام‌ها بعد از خاموشی، تأیید می‌خواهند)
        private val CONFIRM_KEYWORDS = listOf(
            "خاموش کردن", "power off", "shut down", "turn off",
            "تأیید", "تایید", "بله", "yes", "ok"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastAttemptAt = 0L
    private var scanCount = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!powerMenuPending) return
        // به‌محض باز شدن پنجره‌ی منوی پاور (SystemUI) تلاش زودهنگام می‌کنیم
        if (event?.packageName?.toString() != "com.android.systemui") return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttemptAt < 800) return
        tryScan()
    }

    override fun onInterrupt() { }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * خاموشی واقعی بدون روت — سازگار با منوی دکمه‌ای و منوی کشیدنی (HyperOS — گوشی کاربر: شیائومی 11T Pro):
     * ۱) اگر صفحه خاموش است، اول بیدارش می‌کنیم
     * ۲) منوی پاور را باز می‌کنیم
     * ۳) تا ۱۸ ثانیه در فواصل کوتاه جستجو می‌کنیم:
     *    الف) اگر دکمه‌ی واقعی «خاموش کردن» بود → کلیک (نود، والد کلیک‌پذیر، ضربه‌ی لمسی)
     *    ب) اگر منو کشیدنی بود → ژست «کشیدن آیکون به بالا» با ۵ نقطه‌ی شروع مختلف و کشیدن آرام
     *       (نقطه‌ی دستگیره‌ی اسلایدر در HyperOS گوشی‌های مختلف فرق دارد؛ باند پهن‌تر = نشستن مطمئن‌تر)
     * ۴) اگر دیالوگ تأیید آمد → روی دکمه‌ی تأیید کلیک می‌کنیم
     * ۵) اگر تا ۲۰ ثانیه خاموش نشد → حداقل صفحه قفل می‌شود (حالت جایگزین امن)
     */
    fun tryPowerOffViaMenu(): Boolean {
        powerMenuPending = true
        lastAttemptAt = 0
        scanCount = 0

        // مرحله ۱: بیدار کردن صفحه
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                val wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "SleepMusic:WakeForShutdown"
                )
                wakeLock.acquire(8000)
            }
        } catch (_: Exception) { }

        // مرحله ۲: باز کردن منوی پاور (کمی صبر برای روشن شدن صفحه)
        handler.postDelayed({
            if (powerMenuPending) performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        }, 1000)

        // مرحله ۳: جستجوی دوره‌ای — کلیک روی دکمه یا کشیدن آیکون به بالا
        val scanTimes = longArrayOf(
            1900, 2700, 3500, 4300, 5100, 5900, 6700, 7500, 8300, 9100, 9900, 10700
        )
        for (t in scanTimes) {
            handler.postDelayed({ if (powerMenuPending) tryScan() }, t)
        }

        // مرحله ۴: جستجوی دیالوگ تأیید (بعد از کلیک/کشیدن)
        val confirmTimes = longArrayOf(
            2600, 3400, 4400, 5600, 6800, 7800, 8800, 9800, 10800, 11800
        )
        for (t in confirmTimes) {
            handler.postDelayed({ if (powerMenuPending) scanForConfirm() }, t)
        }

        // مرحله ۵: حالت جایگزین امن — قفل صفحه
        handler.postDelayed({
            if (powerMenuPending) {
                powerMenuPending = false
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            }
        }, 20000)

        return true
    }

    /** drag start point - 5 spots across the middle band; slider handle position differs per ROM */
    private fun startRatioFor(attempt: Int): Float = when (attempt % 5) {
        0 -> 0.50f
        1 -> 0.56f
        2 -> 0.62f
        3 -> 0.68f
        else -> 0.74f
    }

    /** slower drag = HyperOS slider registers the gesture more reliably */
    private fun durationFor(attempt: Int): Long = 900L + (attempt % 3) * 150L

    private fun tryScan() {
        if (!powerMenuPending) return
        lastAttemptAt = SystemClock.elapsedRealtime()
        val attempt = scanCount
        scanCount++
        scanAndAct(attempt)
    }

    /** یک دور جستجو: اول دکمه‌ی خاموشی، اگر نبود (یا نوبت کشیدن بود) ژست «کشیدن به بالا» */
    private fun scanAndAct(attempt: Int) {
        if (!powerMenuPending) return
        val root = findSystemUiRoot()

        if (root != null) {
            try { root.refresh() } catch (_: Exception) { }

            val node = findNodeByKeywords(root, POWER_OFF_KEYWORDS)
            if (node != null) {
                val rect = Rect()
                node.getBoundsInScreen(rect)

                // منوی دکمه‌ای: کلیک استاندارد (نوبت ۲ و ۴ فقط مخصوص کشیدن است)
                if (attempt != 2 && attempt != 4) {
                    val clickable = findClickableSelfOrAncestor(node)
                    if (clickable != null) {
                        if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                        if (tapAt(rect.exactCenterX(), rect.exactCenterY(), 60)) return
                    }
                }

                // منوی کشیدنی: برچسب «خاموش کردن» معمولاً بالای اسلایدر است؛ از دستگیره تا آنجا می‌کشیم
                dragToPowerOff(rect, startRatioFor(attempt), durationFor(attempt))
                return
            }
        }

        // نودی پیدا نشد: روی بعضی رام‌ها اسلایدر هیچ متنی ندارد → ژست عمومی از وسط صفحه به بالا
        if (root != null) {
            dragToPowerOff(null, startRatioFor(attempt), durationFor(attempt))
        } else if (attempt >= 3) {
            // منو باز نشده یا وسط کار بسته شده؛ دوباره بازش می‌کنیم
            performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        }
    }

    /** جستجوی دیالوگ تأیید: فقط دکمه‌ی کلیک‌پذیرِ «خاموش کردن/تأیید» را کلیک می‌کنیم */
    private fun scanForConfirm() {
        if (!powerMenuPending) return
        val root = findSystemUiRoot() ?: return
        try { root.refresh() } catch (_: Exception) { }

        val node = findNodeByKeywords(root, CONFIRM_KEYWORDS) ?: return
        val clickable = findClickableSelfOrAncestor(node) ?: return
        if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            powerMenuPending = false
            return
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (tapAt(rect.exactCenterX(), rect.exactCenterY(), 60)) {
            powerMenuPending = false
        }
    }

    /**
     * ژست «کشیدن به بالا» برای منوی کشیدنی HyperOS:
     * از محل دستگیره (وسط صفحه) به سمت برچسب «خاموش کردن» (بالای صفحه) می‌کشیم.
     * targetLabel = محل برچسب؛ اگر null باشد به بالای صفحه می‌کشیم.
     * فقط به سمت بالا می‌کشیم تا هیچ‌وقت باعث «ری‌استارت» (کشیدن به پایین) نشود.
     */
    private fun dragToPowerOff(targetLabel: Rect?, startRatio: Float, durationMs: Long): Boolean {
        return try {
            val w = resources.displayMetrics.widthPixels
            val h = resources.displayMetrics.heightPixels
            if (w <= 0 || h <= 0) return false

            val startX = w * 0.5f
            val startY = h * startRatio
            val endX: Float
            val endY: Float
            if (targetLabel != null && !targetLabel.isEmpty && targetLabel.centerY() < h * 0.40f) {
                endX = targetLabel.exactCenterX()
                endY = targetLabel.exactCenterY()
            } else {
                endX = w * 0.5f
                endY = h * 0.08f
            }

            val path = Path()
            path.moveTo(startX, startY)
            path.lineTo(endX, endY)

            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }

    /** جستجوی سطح‌به‌سطح نود بر اساس متن/contentDescription */
    private fun findNodeByKeywords(
        root: AccessibilityNodeInfo,
        keywords: List<String>
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var checked = 0
        while (queue.isNotEmpty() && checked < 400) {
            val node = queue.removeFirst()
            checked++
            val label = ((node.text?.toString() ?: "") + " " +
                (node.contentDescription?.toString() ?: "")).lowercase()
            if (label.isNotEmpty() && keywords.any { label.contains(it) }) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null) queue.add(child)
            }
        }
        return null
    }

    /** از خود نود تا ۷ سطح بالا: اولین والد کلیک‌پذیر */
    private fun findClickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 7) {
            if (current.isClickable) return current
            current = try { current.parent } catch (_: Exception) { null }
            depth++
        }
        return null
    }

    /** ضربه‌ی لمسی روی یک مختصات دقیق */
    private fun tapAt(x: Float, y: Float, durationMs: Long): Boolean {
        return try {
            val path = Path()
            path.moveTo(x, y)
            path.lineTo(x, y)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
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
