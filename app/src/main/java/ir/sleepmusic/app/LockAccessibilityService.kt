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

/**
 * خاموشی واقعی بدون روت — نسخه‌ی «حرکتِ کور روی منوی ناخوانا» (v1.13):
 *
 * درسی که از گزارش واقعی گوشی کاربر (شیائومی 11T Pro) گرفتیم:
 *  ۱) منوی خاموشی HyperOS پنجره‌اش را به سرویس دسترس‌پذیری «نشان نمی‌دهد» — بعد از
 *     باز شدن منو، حتی نوار وضعیت هم از لیست پنجره‌ها حذف می‌شود و همه‌چیز NULL است.
 *     نسخه‌های قبلی در این حالت اشتباهاً هیچ حرکتی نمی‌زدند!
 *  ۲) نوارِ وضعیتِ معمولی خودش پنجره‌ی com.android.systemui دارد؛ اگر پنجره‌ی
 *     systemui را بدون چکِ اندازه بپذیریم، به‌جای منو درخت ساعت/باتری را می‌خوانیم
 *     و حرکات اول قبل از باز شدن منو هدر می‌رود.
 *  ۳) پس حرکت‌ها باید «کور» باشند: بعد از باز شدن منو، روی مختصات واقعیِ ناحیه‌ی
 *     آیکون خاموشی (ستون میانی، نوار ۰.۳۴ تا ۰.۵۰ ارتفاع) همه‌ی سبک‌های تعامل
 *     (ضربه، نگه‌داشتن، کشیدن به بالا، نگه‌دار-وبکش، کشیدن افقی چپ) تزریق شود —
 *     بدون هیچ وابستگی به دیدنِ پنجره‌ی منو. ژست‌ها در سطح سیستم تزریق می‌شوند و
 *     روی پنجره‌های ناخوانا هم کار می‌کنند.
 *
 * اگر رامی منو را خوانا نشان دهد (بعضی رام‌ها)، از نودِ واقعی هم استفاده می‌کنیم.
 * همه‌ی کشیدن‌ها فقط به بالا/چپ‌اند تا هرگز ری‌استارت نشود؛ حالت امن قفل صفحه سر جایش است.
 */
class LockAccessibilityService : AccessibilityService() {

    companion object {
        var instance: LockAccessibilityService? = null

        @Volatile
        var powerMenuPending = false

        private val POWER_OFF_KEYWORDS = listOf(
            "خاموش کردن", "خاموشی", "خاموشي", "خاموش",
            "power off", "shut down", "shutdown", "turn off",
            "إيقاف التشغيل", "ايقاف التشغيل", "关机"
        )

        private val RESTART_KEYWORDS = listOf(
            "ری‌استارت", "ری استارت", "ري‌استارت", "شروع مجدد", "راه‌اندازی مجدد", "راه اندازی مجدد",
            "restart", "reboot", "إعادة التشغيل", "重启"
        )

        private val CONFIRM_KEYWORDS = listOf(
            "خاموش کردن", "power off", "shut down", "turn off",
            "تأیید", "تایید", "بله", "yes", "ok"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastAttemptAt = 0L
    private var attemptNo = 0
    private var reOpenCount = 0
    private var dumpedTree = false
    private var dumpedWindows = false
    private var statusBarSkips = 0
    private var sessionStart = 0L

    // منو در ثانیه‌ی ۱ درخواست می‌شود؛ از ۲.۴ ثانیگی به بعد «آماده‌ی حرکت» است
    private var menuReadyAt = 0L

    // هر لحظه فقط یک ژست؛ ژست بعدی تا پایان کامل ژست فعلی شروع نمی‌شود
    @Volatile
    private var gestureBusy = false
    private var gestureStartedAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!powerMenuPending) return
        if (event?.packageName?.toString() != "com.android.systemui") return
        val now = SystemClock.elapsedRealtime()
        if (gestureBusy || now < menuReadyAt || now - lastAttemptAt < 1000) return
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

    fun tryPowerOffViaMenu(): Boolean {
        powerMenuPending = true
        lastAttemptAt = 0
        attemptNo = 0
        reOpenCount = 0
        dumpedTree = false
        dumpedWindows = false
        statusBarSkips = 0
        gestureBusy = false
        sessionStart = SystemClock.elapsedRealtime()
        menuReadyAt = sessionStart + 2400L

        ShutdownLog.clear(this)
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        log("SESSION ${w}x${h} — menu request at 1.0s, actions from 2.4s, fallback=25s")

        // بیدار کردن صفحه
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                val wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "SleepMusic:WakeForShutdown"
                )
                wakeLock.acquire(8000)
                log("WAKE screen was off -> wake lock 8s")
            }
        } catch (_: Exception) { }

        // باز کردن منوی پاور
        handler.removeCallbacks(scanTick)
        handler.postDelayed({
            if (powerMenuPending) {
                val ok = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
                log("MENU open requested, ret=$ok")
            }
        }, 1000)

        // تیک دوره‌ای — شروع بعد از آماده‌شدن منو
        handler.postDelayed(scanTick, 2550)

        // جستجوی دیالوگ تأیید جداگانه
        val confirmTimes = longArrayOf(
            3200, 4200, 5200, 6200, 7200, 8200, 9200, 10200, 11200, 12400, 14000, 16000, 18000, 20000
        )
        for (t in confirmTimes) {
            handler.postDelayed({ if (powerMenuPending) scanForConfirm() }, t)
        }

        // حالت امن — قفل صفحه
        handler.postDelayed({
            if (powerMenuPending) {
                powerMenuPending = false
                handler.removeCallbacks(scanTick)
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                log("FALLBACK lock-screen at 25s — خاموشی خودکار انجام نشد")
            }
        }, 25000)

        return true
    }

    private val scanTick = object : Runnable {
        override fun run() {
            if (!powerMenuPending) return
            if (gestureBusy && SystemClock.elapsedRealtime() - gestureStartedAt > 3000) {
                gestureBusy = false
                log("BUSY stale -> released")
            }
            if (!gestureBusy) tryScan()
            handler.postDelayed(this, 1150)
        }
    }

    private fun tryScan() {
        if (!powerMenuPending || gestureBusy) return
        if (SystemClock.elapsedRealtime() < menuReadyAt) return
        lastAttemptAt = SystemClock.elapsedRealtime()
        val attempt = attemptNo
        attemptNo++
        scanAndAct(attempt)
    }

    private fun scanAndAct(attempt: Int) {
        if (!powerMenuPending) return

        val root = findMenuRoot()
        var powerNode: AccessibilityNodeInfo? = null
        var nodeRect: Rect? = null
        var restartRect: Rect? = null

        if (root != null) {
            try { root.refresh() } catch (_: Exception) { }
            log("SCAN#$attempt menu root READABLE")
            if (!dumpedTree) {
                dumpedTree = true
                dumpTree(root)
            }
            powerNode = findNodeByKeywords(root, POWER_OFF_KEYWORDS)
            if (powerNode != null) {
                nodeRect = Rect()
                powerNode.getBoundsInScreen(nodeRect)
                log(
                    "NODE PWR '${labelOf(powerNode)}' rect=$nodeRect cls=${powerNode.className} " +
                        "click=${powerNode.isClickable} longClick=${powerNode.isLongClickable}"
                )
            } else {
                val rNode = findNodeByKeywords(root, RESTART_KEYWORDS)
                if (rNode != null) {
                    restartRect = Rect()
                    rNode.getBoundsInScreen(restartRect)
                    log("NODE RST rect=$restartRect")
                }
            }
        } else {
            logWindowsOnce()
        }

        // ⚠️ کلیدِ نسخه‌ی ۱.۱۳: حرکت‌ها مستقل از «خوانا بودن منو» اجرا می‌شوند
        val p = powerNode
        val r = nodeRect
        if (p != null && r != null) {
            runNodeStrategy(attempt, p, r)
        } else {
            runBlindStrategy(attempt)
        }

        // بیمه: اگر تا نیمه‌ی راه منوی خوانایی ندیدیم، دو بار دیگر درخواست منو می‌دهیم
        if (root == null && (attempt == 8 || attempt == 16) && reOpenCount < 2) {
            reOpenCount++
            performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            log("MENU re-open #$reOpenCount (insurance)")
        }
    }

    /** مسیر رام‌های خوانا: عملیات روی خودِ نود واقعی */
    private fun runNodeStrategy(attempt: Int, node: AccessibilityNodeInfo, rect: Rect) {
        val h = resources.displayMetrics.heightPixels
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        when (attempt % 5) {
            0 -> {
                val c = findClickableSelfOrAncestor(node)
                val ok = try { c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false } catch (_: Exception) { false }
                log("NODE-CLICK ret=$ok")
                if (ok) return
                val tapped = tapAt(cx, cy, 70)
                if (tapped == true) return
                dragAnchored(rect, attempt)
            }
            1 -> {
                val ok = try { node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) } catch (_: Exception) { false }
                log("NODE-LONGCLICK ret=$ok")
                if (ok) return
                val c = findClickableSelfOrAncestor(node)
                val ok2 = try { c?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) ?: false } catch (_: Exception) { false }
                log("PARENT-LONGCLICK ret=$ok2")
                if (ok2) return
                longPressAt(cx, cy, 800)
            }
            2 -> longPressAt(cx, cy, 700)
            3 -> dragAnchored(rect, attempt)
            else -> holdThenSlide(cx, cy, cx, (h * 0.12f).coerceAtLeast(80f), 450L, 850L)
        }
    }

    /**
     * مسیر اصلیِ 11T Pro: حرکتِ کور روی ناحیه‌ی آیکون خاموشی.
     * چیدمان HyperOS: آیکون‌ها در ستون میانی (x=0.5w)؛ خاموشی بالاتر از ری‌استارت.
     * پس همه‌ی حرکات در ستون میانی و نوار ۰.۳۴ تا ۰.۵۰ ارتفاع است — هیچ حرکتی
     * به ناحیه‌ی پایین (ری‌استارت) نمی‌رود. چرخه‌ی ۱۶تایی تا ۲۵ ثانیه تکرار می‌شود.
     */
    private fun runBlindStrategy(attempt: Int) {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        if (w <= 0 || h <= 0) return
        val xC = w * 0.5f
        val slot = attempt % 16

        when (slot) {
            0 -> tapAt(xC, h * 0.40f, 80)
            1 -> longPressAt(xC, h * 0.40f, 800)
            2 -> longPressAt(xC, h * 0.40f, 1200)
            3 -> dragFromTo(xC, h * 0.42f, xC, (h * 0.14f).coerceAtLeast(80f), 950L)
            4 -> holdThenSlide(xC, h * 0.42f, xC, (h * 0.14f).coerceAtLeast(80f), 450L, 850L)
            5 -> tapAt(xC, h * 0.36f, 80)
            6 -> longPressAt(xC, h * 0.36f, 900)
            7 -> dragFromTo(xC, h * 0.38f, xC, (h * 0.12f).coerceAtLeast(80f), 1000L)
            8 -> tapAt(xC, h * 0.44f, 80)
            9 -> longPressAt(xC, h * 0.44f, 900)
            10 -> dragFromTo(xC, h * 0.46f, xC, (h * 0.14f).coerceAtLeast(80f), 950L)
            11 -> holdThenSlide(xC, h * 0.38f, xC, (h * 0.10f).coerceAtLeast(80f), 700L, 800L)
            12 -> longPressAt(xC, h * 0.40f, 1500)
            13 -> dragFromTo(xC, h * 0.40f, xC, (h * 0.09f).coerceAtLeast(80f), 1200L)
            14 -> dragFromTo(w * 0.62f, h * 0.40f, w * 0.18f, h * 0.40f, 800L) // اسلایدر افقیِ راست‌به‌چپ
            else -> dragFromTo(xC, h * 0.34f, xC, (h * 0.08f).coerceAtLeast(80f), 1000L)
        }
    }

    /** کشیدنِ لنگرشده روی نود واقعی (رام‌های خوانا) — ۴ حالت شروع، فقط به بالا */
    private fun dragAnchored(label: Rect, attempt: Int) {
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

    /** فشار طولانی بدون حرکت — سبک «نگه‌دار تا خاموش شود» */
    private fun longPressAt(x: Float, y: Float, durationMs: Long): Boolean {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        if (w <= 0 || h <= 0) return false
        return try {
            val path = Path()
            path.moveTo(x, y)
            path.lineTo(x + 1f, y)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build()
            log("ACT LPGESTURE (${x.toInt()},${y.toInt()}) ${durationMs}ms")
            dispatchSerialized(gesture, "LPGESTURE")
        } catch (_: Exception) {
            gestureBusy = false
            false
        }
    }

    /** «نگه‌دار و بکش» واقعی: فشار با willContinue، بعد ادامه‌ی همان انگشت به بالا */
    private fun holdThenSlide(sx: Float, sy: Float, ex: Float, ey: Float, holdMs: Long, dragMs: Long): Boolean {
        return try {
            val w = resources.displayMetrics.widthPixels
            val h = resources.displayMetrics.heightPixels
            if (w <= 0 || h <= 0) return false
            val x1 = sx.coerceIn(0f, w.toFloat()); val y1 = sy.coerceIn(0f, h.toFloat())
            val x2 = ex.coerceIn(0f, w.toFloat()); val y2 = ey.coerceIn(0f, h.toFloat())

            val holdPath = Path()
            holdPath.moveTo(x1, y1)
            holdPath.lineTo(x1 + 1f, y1)
            val holdStroke = GestureDescription.StrokeDescription(holdPath, 0L, holdMs, true)
            val g1 = GestureDescription.Builder().addStroke(holdStroke).build()

            log("ACT HOLDSLIDE (${x1.toInt()},${y1.toInt()}) hold=${holdMs}ms drag=${dragMs}ms")
            gestureBusy = true
            gestureStartedAt = SystemClock.elapsedRealtime()
            val ok = dispatchGesture(g1, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    try {
                        val dragPath = Path()
                        dragPath.moveTo(x1 + 1f, y1)
                        dragPath.lineTo(x2, y2)
                        val dragStroke = holdStroke.continueStroke(dragPath, 0L, dragMs, false)
                        val g2 = GestureDescription.Builder().addStroke(dragStroke).build()
                        dispatchGesture(g2, object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gd: GestureDescription?) {
                                gestureBusy = false
                                log("ACT HOLDSLIDE = COMPLETED")
                                scheduleNextScan(300)
                            }
                            override fun onCancelled(gd: GestureDescription?) {
                                gestureBusy = false
                                log("ACT HOLDSLIDE = CANCELLED")
                                scheduleNextScan(300)
                            }
                        }, null)
                    } catch (_: Exception) {
                        gestureBusy = false
                        log("ACT HOLDSLIDE drag-continue EXC")
                        scheduleNextScan(300)
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureBusy = false
                    log("ACT HOLDSLIDE hold = CANCELLED")
                    scheduleNextScan(300)
                }
            }, null)
            if (!ok) { gestureBusy = false; log("ACT HOLDSLIDE dispatch=false") }
            ok
        } catch (_: Exception) {
            gestureBusy = false
            false
        }
    }

    private fun dragFromTo(sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long): Boolean {
        return try {
            val w = resources.displayMetrics.widthPixels
            val h = resources.displayMetrics.heightPixels
            if (w <= 0 || h <= 0) return false
            val x1 = sx.coerceIn(0f, w.toFloat()); val y1 = sy.coerceIn(0f, h.toFloat())
            val x2 = ex.coerceIn(0f, w.toFloat()); val y2 = ey.coerceIn(0f, h.toFloat())

            val path = Path()
            path.moveTo(x1, y1)
            path.lineTo(x2, y2)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build()
            log("ACT SLIDE (${x1.toInt()},${y1.toInt()})->(${x2.toInt()},${y2.toInt()}) ${durationMs}ms")
            dispatchSerialized(gesture, "SLIDE")
        } catch (_: Exception) {
            gestureBusy = false
            false
        }
    }

    /** اجرای محافظت‌شده: تا ژست قبلی تمام نشود، ژست جدیدی فرستاده نمی‌شود */
    private fun dispatchSerialized(gesture: GestureDescription, tag: String): Boolean {
        return try {
            gestureBusy = true
            gestureStartedAt = SystemClock.elapsedRealtime()
            val ok = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureBusy = false
                    log("ACT $tag = COMPLETED")
                    scheduleNextScan(300)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureBusy = false
                    log("ACT $tag = CANCELLED")
                    scheduleNextScan(300)
                }
            }, null)
            if (!ok) { gestureBusy = false; log("ACT $tag dispatch=false") }
            ok
        } catch (_: Exception) {
            gestureBusy = false
            false
        }
    }

    private fun scheduleNextScan(delayMs: Long) {
        if (!powerMenuPending) return
        handler.removeCallbacks(scanTick)
        handler.postDelayed(scanTick, delayMs)
    }

    /**
     * دیالوگ تأییدِ واقعی: در «همه‌ی» پنجره‌ها دنبالش می‌گردیم (بعضی دیالوگ‌ها
     * پکیج غیر systemui دارند). اگر برچسب ری‌استارت همان‌جا باشد یعنی منوی اصلی است.
     */
    private fun scanForConfirm() {
        if (!powerMenuPending || gestureBusy) return
        try {
            for (window in windows) {
                val root = window.root ?: continue
                try { root.refresh() } catch (_: Exception) { }
                if (findNodeByKeywords(root, RESTART_KEYWORDS) != null) continue
                val node = findNodeByKeywords(root, CONFIRM_KEYWORDS) ?: continue
                val clickable = findClickableSelfOrAncestor(node) ?: continue
                val ok = try { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
                log("CONFIRM click ret=$ok")
                if (ok) {
                    powerMenuPending = false
                    handler.removeCallbacks(scanTick)
                    return
                }
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (tapAt(rect.exactCenterX(), rect.exactCenterY(), 60) == true) {
                    powerMenuPending = false
                    handler.removeCallbacks(scanTick)
                }
                return
            }
        } catch (_: Exception) { }
    }

    /** پنجره‌ی systemui فقط اگر «بزرگ» باشد — نوار وضعیت (بلندای ~۸۴px) قبول نمی‌شود */
    private fun findMenuRoot(): AccessibilityNodeInfo? {
        return try {
            val h = resources.displayMetrics.heightPixels
            for (window in windows) {
                val root = window.root ?: continue
                if (root.packageName?.toString() != "com.android.systemui") continue
                val r = Rect()
                root.getBoundsInScreen(r)
                if (r.height() >= h * 0.30f) return root
                if (statusBarSkips < 2) {
                    statusBarSkips++
                    log("SYSUI small root h=${r.height()}px — نوار وضعیت است نه منو -> skipped")
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** یک‌بار بعد از آماده‌شدن منو: فهرست پنجره‌های قابل‌دیدن را ثبت می‌کنیم */
    private fun logWindowsOnce() {
        if (dumpedWindows) return
        dumpedWindows = true
        try {
            val sb = StringBuilder()
            for (window in windows) {
                val r = window.root
                if (r != null) {
                    val b = Rect()
                    r.getBoundsInScreen(b)
                    sb.append("${r.packageName}[").append(b.height()).append("px] ")
                }
            }
            log("WINDOWSCAN after menuReady: $sb")
        } catch (_: Exception) { }
    }

    /** درخت پنجره‌ی منوی خوانا — فقط وقتی منو خوانا باشد اجرا می‌شود */
    private fun dumpTree(root: AccessibilityNodeInfo) {
        try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var checked = 0
            var logged = 0
            while (queue.isNotEmpty() && logged < 26 && checked < 260) {
                val node = queue.removeFirst()
                checked++
                val t = node.text?.toString()?.take(24) ?: ""
                val cd = node.contentDescription?.toString()?.take(24) ?: ""
                if (t.isNotEmpty() || cd.isNotEmpty() || node.isClickable || logged < 12) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    log(
                        "TREE cls=${node.className} t='$t' cd='$cd' rect=$r " +
                            "click=${node.isClickable} longClick=${node.isLongClickable}"
                    )
                    logged++
                }
                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (_: Exception) { null }
                    if (child != null) queue.add(child)
                }
            }
        } catch (_: Exception) { }
    }

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

    private fun tapAt(x: Float, y: Float, durationMs: Long): Boolean? {
        return try {
            val path = Path()
            path.moveTo(x, y)
            path.lineTo(x, y)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build()
            log("ACT TAP (${x.toInt()},${y.toInt()}) ${durationMs}ms")
            dispatchSerialized(gesture, "TAP")
        } catch (_: Exception) {
            gestureBusy = false
            null
        }
    }

    private fun labelOf(node: AccessibilityNodeInfo): String =
        (node.text?.toString() ?: node.contentDescription?.toString() ?: "").take(24)

    private fun log(line: String) {
        val t = if (sessionStart == 0L) 0L else SystemClock.elapsedRealtime() - sessionStart
        ShutdownLog.append(this, "+${t}ms $line")
    }
}
