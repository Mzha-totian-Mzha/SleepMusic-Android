package ir.sleepmusic.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.widget.Toast

object ActionExecutor {

    /**
     * اکشن نهایی «خاموشی گوشی» به ترتیب اولویت (بدون روت):
     * ۱) حالت «خاموشی واقعی» فعال باشد → باز کردن منوی خاموشی و کلیک روی دکمه خاموش کردن
     * ۲) اگر نشد → قفل کردن صفحه
     */
    fun execute(context: Context, tryPowerMenu: Boolean) {
        // مرحله ۱: خاموشی واقعی از طریق منوی خاموشی سیستم (بدون روت)
        if (tryPowerMenu) {
            val service = LockAccessibilityService.instance
            if (service != null) {
                service.tryPowerOffViaMenu()
                return // سرویس خودش دکمه خاموش کردن را می‌زند؛ اگر نشد صفحه را قفل می‌کند
            }
        }

        // مرحله ۲: حالت جایگزین - قفل کردن صفحه
        if (Build.VERSION.SDK_INT >= 28) {
            val locked = LockAccessibilityService.instance?.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            ) ?: false
            if (!locked) {
                Toast.makeText(
                    context,
                    "سرویس دسترس‌پذیری فعال نیست! تنظیمات > دسترس‌پذیری > قفل صفحه هوشمند",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            Toast.makeText(context, "برای قفل صفحه به اندروید ۹ یا بالاتر نیاز است", Toast.LENGTH_LONG).show()
        }
    }
}