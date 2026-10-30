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

        // برچسب «ری‌استارت» — دستگیره‌ی خاموشی معمولاً دقیقاً بالای آن است؛
        // همچنین وجود آن یعنی «منوی اصلی» جلوی ماست نه دیالوگ تأیید
        private val RESTART_KEYWORDS = listOf(
            "ری‌استارت", "ری استارت", "ري‌استارت", "شروع مجدد", "راه‌اندازی مجدد", "راه اندازی مجدد",
            "restart", "reboot", "إعادة التشغيل", "重启"
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
    private var reOpenCount = 0

    // ⚠️ نکته‌ی حیاتی (ریشه‌ی ایراد نسخه‌های قبل):
    // سرویس دسترس‌پذیری در هر لحظه فقط «یک» ژست را اجرا می‌کند؛ dispatchGesture بعدی،
    // ژستِ در حال اجرا را «لغو» می‌کند. قبلاً فواصلِ تلاش‌ها (۸۰۰ms) از مدتِ کشیدن
    // (۹۰۰ تا ۱۲۰۰ms) کمتر بود؛ یعنی هر کشیدن وسط راه با تلاش بعدی لغو می‌شد و
    // اسلایدر HyperOS هرگز تا ناحیه‌ی رهاسازی کامل نمی‌شد. حالا هر ژست تا پایانِ کامل
    // اجرا می‌شود و فقط بعد از آن ژست بعدی شروع می‌شود.
    @Volatile
    private var gestureBusy = false
    private var gestureStartedAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!powerMenuPending) return
        // به‌محض باز شدن پنجره‌ی منوی پاور (SystemUI) تلاش زودهنگام می‌کنیم
        if (event?.packageName?.toString() != "com.android.systemui") return
        val now = SystemClock.elapsedRealtime()
        if (gestureBusy || now - lastAttemptAt < 1000) return
        tryScan()
    }

    override fun onInterrupt() { }

    override fun onDestroy() {
        powerMenuPending = false
        gestureBusy = false
        handler.removeCallbacksAndMessages(null)
        instance = null
        super.onDestroy()
    }

    /**
     * خاموشی واقعی بدون روت — سازگار با منوی دکمه‌ای و منوی کشیدنی (HyperOS — گوشی کاربر: شیائومی 11T Pro):
     * ۱) اگر صفحه خاموش است، اول بیدارش می‌کنیم
     * ۲) منوی پاور را باز می‌کنیم
     * ۳) با تیکِ دوره‌ای جستجو می‌کنیم — هر تلاش فقط وقتی شروع می‌شود که ژستِ قبلی «کامل» شده باشد:
     *    الف) اگر برچسب «خاموش کردن» پیدا شد → نوبت اول فقط کلیک (منوی دکمه‌ای)؛ بعد از آن
     *       ژست «کشیدن به بالا» که نقطه‌ی شروعش از روی محل واقعی همان برچسب لنگر می‌شود
     *       (۴ حالت مختلف: روی متن، زیر متن، کمی پایین‌تر، بالای متن)
     *    ب) اگر فقط برچسب «ری‌استارت» پیدا شد → دستگیره‌ی خاموشی معمولاً بالای آن است
     *    ج) اگر هیچ متنی نبود → جاروی عمودی متراکم از نقاط مختلف نوار میانی
     *    همه‌ی کشیدن‌ها فقط «به بالا» هستند تا هرگز باعث ری‌استارت (کشیدن به پایین) نشوند
     * ۴) اگر دیالوگ تأیید جداگانه آمد (منویی که ری‌استارت ندارد) → روی دکمه‌ی تأیید کلیک می‌کنیم
     * ۵) اگر تا ۲۰ ثانیه خاموش نشد → حداقل صفحه قفل می‌شود (حالت جایگزین امن)
     */
    fun tryPowerOffViaMenu(): Boolean {
        powerMenuPending = true
        lastAttemptAt = 0
        scanCount = 0
        reOpenCount = 0
        gestureBusy = false

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
        handler.removeCallbacks(scanTick)
        handler.postDelayed({
            if (powerMenuPending) performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        }, 1000)

        // مرحله ۳: تیکِ دوره‌ای — هر دور فقط بعد از پایانِ کاملِ ژستِ قبلی
        handler.postDelayed(scanTick, 2100)

        // مرحله ۴: جستجوی دیالوگ تأیید (بعد از کلیک موفق در منوهای دکمه‌ای)
        val confirmTimes = longArrayOf(
            2600, 3600, 4600, 5600, 6600, 7600, 8600, 9600, 10600, 11600, 13000, 15000
        )
        for (t in confirmTimes) {
            handler.postDelayed({ if (powerMenuPending) scanForConfirm() }, t)
        }

        // مرحله ۵: حالت جایگزین امن — قفل صفحه
        handler.postDelayed({
            if (powerMenuPending) {
                powerMenuPending = false
                handler.removeCallbacks(scanTick)
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            }
        }, 20000)

        return true
    }

    /** تیک دوره‌ای: دور جدید فقط وقتی که دست خالی است؛ اگر کال‌بکِ پایان ژست گم شده بود، بعد از ۲.۵ ثانیه دست را آزاد می‌کنیم */
    private val scanTick = object : Runnable {
        override fun run() {
            if (!powerMenuPending) return
            if (gestureBusy && SystemClock.elapsedRealtime() - gestureStartedAt > 2500) {
                gestureBusy = false
            }
            if (!gestureBusy) tryScan()
            handler.postDelayed(this, 1100)
        }
    }

    private fun tryScan() {
        if (!powerMenuPending || gestureBusy) return
        lastAttemptAt = SystemClock.elapsedRealtime()
        val attempt = scanCount
        scanCount++
        scanAndAct(attempt)
    }

    /** یک دور جستجو: اول برچسب خاموش، بعد برچسب ری‌استارت، و اگر هیچ‌کدام نبود جاروی عمودی */
    private fun scanAndAct(attempt: Int) {
        if (!powerMenuPending) return
        val root = findSystemUiRoot()

        if (root == null) {
            // منو بسته شده (یا هنوز باز نشده) — حداکثر ۳ بار دوباره بازش می‌کنیم؛
            // بیشتر از این نه، چون شاید گوشی همین الان در حال خاموش شدن باشد
            if (attempt >= 2 && reOpenCount < 3) {
                reOpenCount++
                performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            }
            return
        }
        try { root.refresh() } catch (_: Exception) { }

        val powerNode = findNodeByKeywords(root, POWER_OFF_KEYWORDS)
        if (powerNode != null) {
            val rect = Rect()
            powerNode.getBoundsInScreen(rect)

            // فقط نوبتِ اول: کلیکِ استاندارد (برای منوهای دکمه‌ای قدیمی)
            if (attempt == 0) {
                val clickable = findClickableSelfOrAncestor(powerNode)
                if (clickable != null) {
                    if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                    if (tapAt(rect.exactCenterX(), rect.exactCenterY(), 70)) return
                }
            }

            // منوی کشیدنی: کشیدنِ لنگرشده به محل واقعی برچسب/دستگیره
            dragAnchoredToLabel(rect, attempt)
            return
        }

        val restartNode = findNodeByKeywords(root, RESTART_KEYWORDS)
        if (restartNode != null) {
            val r = Rect()
            restartNode.getBoundsInScreen(r)
            // دستگیره‌ی خاموشی معمولاً دقیقاً بالای برچسب ری‌استارت است
            val h = resources.displayMetrics.heightPixels
            val cx = r.exactCenterX()
            val sy = (r.top - 150f).coerceAtLeast(200f)
            dragFromTo(cx, sy, cx, (h * 0.15f).coerceAtLeast(80f), 950L)
            return
        }

        // نودی پیدا نشد: روی بعضی رام‌ها اسلایدر هیچ متنی ندارد → جاروی عمودی متراکم
        genericSweepDrag(attempt)
    }

    /**
     * کشیدنِ لنگرشده: نقطه‌ی شروع از روی محل واقعی برچسب «خاموش کردن» انتخاب می‌شود،
     * نه از نقاط حدسی. ۴ حالت پوشش داده می‌شود تا هر جا که متن باشد (روی دستگیره،
     * زیر دستگیره یا بالای آن) شروعِ کشیدن روی دستگیره بنشیند.
     * پایان‌ها هم متنوع‌اند (۱۲ تا ۲۰ درصد ارتفاع) چون محل دقیق ناحیه‌ی رهاسازی از رام to رام فرق دارد.
     * همه فقط به سمت بالا — تا هیچ‌وقت ری‌استارت (کشیدن به پایین) فعال نشود.
     */
    private fun dragAnchoredToLabel(label: Rect, attempt: Int) {
        val h = resources.displayMetrics.heightPixels
        if (h <= 0) return
        val cx = label.exactCenterX()
        when (attempt % 4) {
            0 -> dragFromTo(cx, label.bottom + 90f, cx, (h * 0.20f).coerceAtLeast(80f), 950L)
            1 -> dragFromTo(cx, label.exactCenterY(), cx, (h * 0.12f).coerceAtLeast(80f), 1100L)
            2 -> dragFromTo(cx, label.exactCenterY() + 220f, cx, (label.top - 500f).coerceAtLeast(80f), 1000L)
            else -> dragFromTo(cx, label.top - 60f, cx, (h * 0.16f).coerceAtLeast(80f), 1000L)
        }
    }

    /** جاروی عمودی: شروع از نقاط مختلف نوار میانی (۰.۴۲ تا ۰.۷۰ ارتفاع) تا نزدیک بالای صفحه */
    private fun genericSweepDrag(attempt: Int) {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        if (w <= 0 || h <= 0) return
        val ratios = floatArrayOf(0.42f, 0.50f, 0.58f, 0.66f, 0.46f, 0.54f, 0.62f, 0.70f)
        val sy = h * ratios[attempt % ratios.size]
        dragFromTo(w * 0.5f, sy, w * 0.5f, (h * 0.12f).coerceAtLeast(80f), 950L)
    }

    /**
     * اجرای محافظت‌شده‌ی ژست: تا ژستِ قبلی «کامل» نشده، ژست جدیدی فرستاده نمی‌شود.
     * پایان ژست (موفق یا لغوشده) دور بعد را ۳۰۰ms بعد شروع می‌کند — سریع‌تر از تیکِ اصلی، ولی بدون هم‌پوشانی.
     */
    private fun dragFromTo(sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long): Boolean {
        return try {
            val w = resources.displayMetrics.widthPixels
            val h = resources.displayMetrics.heightPixels
            if (w <= 0 || h <= 0) return false

            val x1 = sx.coerceIn(0f, w.toFloat())
            val y1 = sy.coerceIn(0f, h.toFloat())
            val x2 = ex.coerceIn(0f, w.toFloat())
            val y2 = ey.coerceIn(0f, h.toFloat())

            val path = Path()
            path.moveTo(x1, y1)
            path.lineTo(x2, y2)

            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            gestureBusy = true
            gestureStartedAt = SystemClock.elapsedRealtime()
            val ok = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureBusy = false
                    scheduleNextScan(300)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureBusy = false
                    scheduleNextScan(300)
                }
            }, null)
            if (!ok) gestureBusy = false
            ok
        } catch (_: Exception) {
            gestureBusy = false
            false
        }
    }

    /** بعد از تمام‌شدن یک ژست، دور بعد را زودتر از تیکِ اصلی شروع کن (بدون هم‌پوشانی) */
    private fun scheduleNextScan(delayMs: Long) {
        if (!powerMenuPending) return
        handler.removeCallbacks(scanTick)
        handler.postDelayed(scanTick, delayMs)
    }

    /**
     * جستجوی دیالوگ تأیید: فقط وقتی «دیالوگ تأییدِ واقعی» جلوی ماست کلیک می‌کنیم.
     * تشخیص: اگر برچسب «ری‌استارت» هم همان‌جا باشد یعنی این منوی اصلی است —
     * قبلاً همین‌جا اشتباه می‌شد و کلیک روی خودِ منوی اصلی، کل عملیات را رها می‌کرد.
     */
    private fun scanForConfirm() {
        if (!powerMenuPending || gestureBusy) return
        val root = findSystemUiRoot() ?: return
        try { root.refresh() } catch (_: Exception) { }

        if (findNodeByKeywords(root, RESTART_KEYWORDS) != null) return
        val node = findNodeByKeywords(root, CONFIRM_KEYWORDS) ?: return
        val clickable = findClickableSelfOrAncestor(node) ?: return
        if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            powerMenuPending = false
            handler.removeCallbacks(scanTick)
            return
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (tapAt(rect.exactCenterX(), rect.exactCenterY(), 60)) {
            powerMenuPending = false
            handler.removeCallbacks(scanTick)
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
