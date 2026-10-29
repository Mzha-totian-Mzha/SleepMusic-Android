package ir.sleepmusic.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Rect
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

        // عنوان دکمه خاموش کردن در زبان‌های مختلف (فارسی/انگلیسی/عربی/چینی)
        private val POWER_OFF_KEYWORDS = listOf(
            "خاموش کردن", "خاموشی", "خاموشي", "خاموش",
            "power off", "shut down", "shutdown", "power",
            "إيقاف التشغيل", "ايقاف التشغيل", "关机"
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
     * ۳) خودمان روی دکمه «خاموش کردن» کلیک می‌کنیم:
     *     - اول خودِ نود، بعد والدِ کلیک‌پذیر، و در نهایت ضربه‌ی لمسی (Gesture) روی مختصات دکمه
     *     - چون روی MIUI بعضی دکمه‌ها کلیک استاندارد accessibility را به accessibility نمی‌دهند
     * ۴) اگر تا ۸ ثانیه دکمه پیدا نشد، به‌جایش صفحه را قفل می‌کنیم (حالت جایگزین امن)
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
        }, 1000)

        // منو کمی طول می‌کشد تا باز شود؛ در فواصل مختلف چند بار دنبال دکمه می‌گردیم
        handler.postDelayed({ if (powerMenuPending) scanAndClick() }, 1700)
        handler.postDelayed({ if (powerMenuPending) scanAndClick() }, 2300)
        handler.postDelayed({ if (powerMenuPending) scanAndClick() }, 3000)
        // برای رام‌هایی که به‌جای کلیک، نگه‌داشتن می‌خواهند
        handler.postDelayed({ if (powerMenuPending) scanAndClick(longClick = true) }, 4400)
        handler.postDelayed({ if (powerMenuPending) scanAndClick() }, 5600)

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
        try { root.refresh() } catch (_: Exception) { }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var checked = 0
        while (queue.isNotEmpty() && checked < 400) {
            val node = queue.removeFirst()
            checked++
            val label = ((node.text?.toString() ?: "") + " " +
                (node.contentDescription?.toString() ?: "")).lowercase()
            if (POWER_OFF_KEYWORDS.any { label.contains(it) }) {
                val clicked = if (longClick) {
                    clickNode(node, longClick = true)
                } else {
                    clickNode(node)
                }
                if (clicked) {
                    powerMenuPending = false
                    return
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
    }

    /**
     * تلاش برای کلیک روی نود به سه روش پشت سر هم:
     * ۱) کلیک مستقیم روی خود نود (اگر کلیک‌پذیر باشد)
     * ۲) بالا رفتن تا ۶ سطح والد تا اولین والدِ کلیک‌پذیر پیدا شود و کلیک شود
     * ۳) ضربه‌ی لمسی (dispatchGesture) روی مرکز نود — روی MIUI خیلی وقت‌ها تنها همین کار می‌کند
     */
    private fun clickNode(node: AccessibilityNodeInfo, longClick: Boolean = false): Boolean {
        // روش ۱ و ۲: کلیک استاندارد
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 7) {
            val clickable = current.isClickable
            if (clickable) {
                val action = if (longClick) {
                    AccessibilityNodeInfo.ACTION_LONG_CLICK
                } else {
                    AccessibilityNodeInfo.ACTION_CLICK
                }
                if (current.performAction(action)) return true
            }
            current = try { current.parent } catch (_: Exception) { null }
            depth++
        }

        // روش ۳: ضربه‌ی لمسی روی مختصات دکمه
        return tapNodeCenter(node, longClick)
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo, longClick: Boolean): Boolean {
        return try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.isEmpty || rect.width() <= 0 || rect.height() <= 0) return false
            val cx = rect.exactCenterX()
            val cy = rect.exactCenterY()
            val gesture = if (longClick) {
                GestureDescription.Builder()
                    .addStroke(
                        GestureDescription.StrokeDescription(
                            android.graphics.Path().apply {
                                moveTo(cx, cy); lineTo(cx, cy)
                            }, 0L, 700L
                        )
                    )
                    .build()
            } else {
                GestureDescription.Builder()
                    .addStroke(
                        GestureDescription.StrokeDescription(
                            android.graphics.Path().apply {
                                moveTo(cx, cy); lineTo(cx, cy)
                            }, 0L, 60L
                        )
                    )
                    .build()
            }
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
