package ir.sleepmusic.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * گزارش خطایابی خاموشی خودکار:
 * سرویس دسترس‌پذیری هر تلاش را اینجا ثبت می‌کند — باز شدن منو، پیدا شدن برچسب‌ها با
 * مختصات دقیق، هر ژست ارسالی و نتیجه‌اش. ردیف «گزارش خاموشی» در تنظیمات این فایل را
 * نشان می‌دهد تا اگر خاموشی روی گوشی انجام نشد، دقیقاً معلوم شود کجای کار می‌لنگد
 * (به‌جای حدس زدن).
 */
object ShutdownLog {

    private const val FILE_NAME = "shutdown_report.txt"
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun append(context: Context, line: String) {
        try {
            val f = File(context.filesDir, FILE_NAME)
            if (f.length() > 32_000) trim(f)
            f.appendText("${fmt.format(Date())} | $line\n")
        } catch (_: Exception) { }
    }

    @Synchronized
    fun clear(context: Context) {
        try { File(context.filesDir, FILE_NAME).delete() } catch (_: Exception) { }
    }

    @Synchronized
    fun read(context: Context): String = try {
        val f = File(context.filesDir, FILE_NAME)
        if (f.exists() && f.length() > 0) {
            f.readText()
        } else {
            "هنوز گزارشی ثبت نشده است.\n" +
                "یک بار اجازه بده پخش تا پایان برسد تا خاموشی خودکار تلاش شود،\n" +
                "بعد دوباره اینجا بیا."
        }
    } catch (_: Exception) {
        "خطا در خواندن گزارش"
    }

    private fun trim(f: File) {
        try {
            val lines = f.readLines()
            f.writeText(lines.takeLast(200).joinToString("\n"))
        } catch (_: Exception) { }
    }
}
