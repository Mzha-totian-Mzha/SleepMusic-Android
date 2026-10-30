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
 * خاموشی واقعی بدون روت — نسخه‌ی «همه‌روش‌ها + گزارش»:
 *
 * چون از این‌جا نمی‌دانیم منوی خاموشی گوشی کاربر دقیقاً چه شکلی است (دکمه‌ای،
 * نگه‌داشتن، یا کشیدنی) و سه نسخه‌ی حدسی جواب نداد، این نسخه به‌ترتیب «همه‌ی»
 * سبک‌های تعامل را روی خودِ منو امتحان می‌کند و همه‌چیز را در ShutdownLog ثبت
 * می‌کند تا اگر باز هم نشد، از روی گزارش واقعیِ گوشیِ کاربر درستش کنیم:
 *
 *  ۱) کلیک استاندارد روی نودِ «خاموش کردن» (منوهای دکمه‌ای)
 *  ۲) LONG_CLICK نرم‌افزاری روی همان نود (منوهای «نگه‌دار تا خاموش شود»)
 *  ۳) ژستِ فشارِ طولانی بدون حرکت (۷۰۰ms) روی مرکز نود / نقاط حدسی بالای منو
 *  ۴) کشیدن به بالا با ۴ نقطه‌ی شروعِ لنگرشده روی محل واقعی برچسب
 *  ۵) «نگه‌دار و بعد بکش» با ژست دو‌مرحله‌ای واقعی (انگشت بدون بلندشدن)
 *  ۶) کشیدن از بالای برچسب ری‌استارت (دستگیره‌ی خاموشی معمولاً بالای آن است)
 *  ۷) جاروی عمودی از نقاط مختلف وسط صفحه
 *
 * همه‌ی کشیدن‌ها فقط «به بالا» هستند تا هرگز باعث ری‌استارت نشوند.
 * اگر تا ۲۵ ثانیه نشد → حالت امن: قفل صفحه (مثل قبل).
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
    private var sessionStart = 0L

    // ⚠️ هر لحظه فقط یک ژست؛ ژست بعدی تا «پایان کامل» ژست فعلی شروع نمی‌شود
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

    fun tryPowerOffViaMenu(): Boolean {
        powerMenuPending = true
        lastAttemptAt = 0
        attemptNo = 0
        reOpenCount = 0
        dumpedTree = false
        gestureBusy = false
        sessionStart = SystemClock.elapsedRealtime()

        ShutdownLog.clear(this)
        log("SESSION start (fallback=25s)")

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
                log("WAKE screen was off -> wake lock 8s")
            }
        } catch (_: Exception) { }

        // مرحله ۲: باز کردن منوی پاور
        handler.removeCallbacks(scanTick)
        handler.postDelayed({
            if (powerMenuPending) {
                val ok = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
                log("MENU open requested, ret=$ok")
            }
        }, 1000)

        // مرحله ۳: تیک دوره‌ای — هر تلاش فقط بعد از پایان کامل ژست قبلی
        handler.postDelayed(scanTick, 2100)

        // مرحله ۴: جستجوی دیالوگ تأیید جداگانه (منوهایی که بعد از انتخاب، تأیید می‌خواهند)
        val confirmTimes = longArrayOf(
            2600, 3600, 4600, 5600, 6600, 7600, 8600, 9600, 10600, 11600, 13000, 15000, 17000, 19000
        )
        for (t in confirmTimes) {
            handler.postDelayed({ if (powerMenuPending) scanForConfirm() }, t)
        }

        // مرحله ۵: حالت امن — قفل صفحه
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
            handler.postDelayed(this, 1100)
        }
    }

    private fun tryScan() {
        if (!powerMenuPending || gestureBusy) return
        lastAttemptAt = SystemClock.elapsedRealtime()
        val attempt = attemptNo
        attemptNo++
        scanAndAct(attempt)
    }

    private fun scanAndAct(attempt: Int) {
        if (!powerMenuPending) return
        val root = findSystemUiRoot()

        if (root == null) {
            log("SCAN#$attempt root=NULL (menu not visible)")
            if (attempt >= 2 && reOpenCount < 3) {
                reOpenCount++
                performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
                log("MENU re-open #$reOpenCount")
            }
            return
        }
        try { root.refresh() } catch (_: Exception) { }
        log("SCAN#$attempt root=SystemUI")

        if (!dumpedTree) {
            dumpedTree = true
            dumpTree(root)
        }

        val powerNode = findNodeByKeywords(root, POWER_OFF_KEYWORDS)
        if (powerNode != null) {
            val rect = Rect()
            powerNode.getBoundsInScreen(rect)
            log(
                "NODE PWR '${labelOf(powerNode)}' cls=${powerNode.className} " +
                    "rect=$rect click=${powerNode.isClickable} longClick=${powerNode.isLongClickable}"
            )
            runStrategy(attempt, powerNode, rect, null)
            return
        }

        val restartNode = findNodeByKeywords(root, RESTART_KEYWORDS)
        if (restartNode != null) {
            val r = Rect()
            restartNode.getBoundsInScreen(r)
            log(
                "NODE RST '${labelOf(restartNode)}' cls=${restartNode.className} " +
                    "rect=$r click=${restartNode.isClickable} longClick=${restartNode.isLongClickable}"
            )
            runStrategy(attempt, null, null, r)
            return
        }

        log("SCAN#$attempt no text node found -> blind strategy")
        runStrategy(attempt, null, null, null)
    }

    /** چرخه‌ی همه‌روش‌ها: هر نوبت یک استراتژی؛ هیچ حرکتی به پایین نیست (ری‌استارت نشود) */
    private fun runStrategy(attempt: Int, powerNode: AccessibilityNodeInfo?, rect: Rect?, restartRect: Rect?) {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        if (w <= 0 || h <= 0) return
        val slot = attempt % 16
        val cx = rect?.exactCenterX() ?: (w * 0.5f)
        val cy = rect?.exactCenterY() ?: (h * 0.42f)

        when (slot) {
            0 -> { // کلیک استاندارد (منوهای دکمه‌ای)
                if (powerNode != null) {
                    val c = findClickableSelfOrAncestor(powerNode)
                    val ok = c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                    log("ACT#$slot NODE-CLICK ret=$ok")
                    if (ok) return
                    val tapped = tapAt(cx, cy, 70)
                    log("ACT#$slot TAP node ret=$tapped")
                    if (tapped == true) return
                } else {
                    val tapped = tapAt(w * 0.5f, h * 0.42f, 70)
                    log("ACT#$slot TAP blind ret=$tapped")
                    if (tapped == true) return
                }
                dragAnchored(rect, attempt)
            }
            1 -> { // LONG_CLICK نرم‌افزاری (منوهای نگه‌داشتن)
                if (powerNode != null) {
                    val ok = try { powerNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) } catch (_: Exception) { false }
                    log("ACT#$slot NODE-LONGCLICK ret=$ok")
                    if (ok) return
                    val c = findClickableSelfOrAncestor(powerNode)
                    val ok2 = try { c?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) ?: false } catch (_: Exception) { false }
                    log("ACT#$slot PARENT-LONGCLICK ret=$ok2")
                    if (ok2) return
                }
                longPressAt(w * 0.5f, h * 0.40f, 800)
            }
            2 -> longPressAt(cx, cy, 700) // فشار طولانی واقعی روی مرکز نود
            3 -> dragAnchored(rect, attempt)
            4 -> dragAnchored(rect, attempt)
            5 -> dragAnchored(rect, attempt)
            6 -> dragAnchored(rect, attempt)
            7 -> holdThenSlide(cx, cy, cx, (h * 0.12f).coerceAtLeast(80f), 450L, 850L)
            8 -> { // از بالای برچسب ری‌استارت (دستگیره معمولاً همان‌جاست)
                if (restartRect != null) {
                    val sx = restartRect.exactCenterX()
                    val sy = (restartRect.top - 150f).coerceAtLeast(200f)
                    dragFromTo(sx, sy, sx, (h * 0.15f).coerceAtLeast(80f), 950L)
                } else {
                    dragFromTo(w * 0.5f, h * 0.52f, w * 0.5f, (h * 0.14f).coerceAtLeast(80f), 950L)
                }
            }
            9 -> dragFromTo(w * 0.5f, h * 0.46f, w * 0.5f, (h * 0.12f).coerceAtLeast(80f), 950L)
            10 -> dragFromTo(w * 0.5f, h * 0.54f, w * 0.5f, (h * 0.12f).coerceAtLeast(80f), 950L)
            11 -> longPressAt(cx, cy, 900)
            12 -> dragFromTo(w * 0.5f, h * 0.62f, w * 0.5f, (h * 0.12f).coerceAtLeast(80f), 950L)
            13 -> holdThenSlide(cx, cy, cx, (h * 0.10f).coerceAtLeast(80f), 700L, 800L)
            14 -> dragFromTo(w * 0.5f, h * 0.50f, w * 0.5f, (h * 0.09f).coerceAtLeast(80f), 1100L)
            else -> dragFromTo(w * 0.5f, h * 0.42f, w * 0.5f, (h * 0.09f).coerceAtLeast(80f), 1100L)
        }
    }

    /**
     * کشیدنِ لنگرشده روی برچسب «خاموش کردن»: ۴ حالت شروع (روی متن، زیر متن، پایین‌تر،
     * بالای متن) — پایان‌ها بین ۹ تا ۲۰ درصد ارتفاع (ناحیه‌ی رهاسازی). فقط به بالا.
     */
    private fun dragAnchored(label: Rect?, attempt: Int) {
        val h = resources.displayMetrics.heightPixels
        if (h <= 0) return
        if (label == null || label.isEmpty) {
            // بدون برچسب: جارو از نوار میانی
            val ratios = floatArrayOf(0.42f, 0.50f, 0.58f, 0.66f)
            val sy = h * ratios[attempt % ratios.size]
            dragFromTo(resources.displayMetrics.widthPixels * 0.5f, sy,
                resources.displayMetrics.widthPixels * 0.5f, (h * 0.12f).coerceAtLeast(80f), 950L)
            return
        }
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
            log("ACT LPGESTURE (${x.toInt()},${y.toInt()}), ${durationMs}ms")
            dispatchSerialized(gesture, "LPGESTURE")
        } catch (_: Exception) {
            gestureBusy = false
            false
        }
    }

    /**
     * «نگه‌دار و بکش» واقعی: مرحله‌ی اول فشار با willContinue=true؛ بعد از پایان،
     * همان انگشت با continueStroke به سمت بالا ادامه پیدا می‌کند (بدون بلندشدن).
     */
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

    /** اجرای محافظت‌شده: تا ژست قبلی تمام نشود ژست جدیدی فرستاده نمی‌شود؛ نتیجه در گزارش ثبت می‌شود */
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

    /** جستجوی دیالوگ تأیید واقعی: اگر «ری‌استارت» هم همان‌جاست یعنی منوی اصلی است و کاری نمی‌کنیم */
    private fun scanForConfirm() {
        if (!powerMenuPending || gestureBusy) return
        val root = findSystemUiRoot() ?: return
        try { root.refresh() } catch (_: Exception) { }

        if (findNodeByKeywords(root, RESTART_KEYWORDS) != null) return
        val node = findNodeByKeywords(root, CONFIRM_KEYWORDS) ?: return
        val clickable = findClickableSelfOrAncestor(node) ?: return
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

    /** یک‌بار در هر نشست: ساختار واقعی پنجره‌ی منو را در گزارش می‌ریزد تا نقشه‌ی گوشی کاربر را ببینیم */
    private fun dumpTree(root: AccessibilityNodeInfo) {
        try {
            var winCount = 0
            val pkgs = StringBuilder()
            for (window in windows) {
                winCount++
                pkgs.append(window.root?.packageName).append(' ')
            }
            log("WINDOWS count=$winCount pkgs=$pkgs")

            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var checked = 0
            var logged = 0
            while (queue.isNotEmpty() && logged < 24 && checked < 250) {
                val node = queue.removeFirst()
                checked++
                val t = node.text?.toString()?.take(22) ?: ""
                val cd = node.contentDescription?.toString()?.take(22) ?: ""
                if (t.isNotEmpty() || cd.isNotEmpty() || node.isClickable || logged < 12) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    log(
                        "TREE cls=${node.className} t='$t' cd='$cd' rect=$r " +
                            "click=${node.isClickable} longClick=${node.isLongClickable} scroll=${node.isScrollable}"
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

    private fun labelOf(node: AccessibilityNodeInfo): String =
        (node.text?.toString() ?: node.contentDescription?.toString() ?: "").take(24)

    private fun log(line: String) {
        val t = if (sessionStart == 0L) 0L else SystemClock.elapsedRealtime() - sessionStart
        ShutdownLog.append(this, "+${t}ms $line")
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
