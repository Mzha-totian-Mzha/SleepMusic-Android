package ir.sleepmusic.app

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.media.audiofx.Visualizer
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * ویژوالایزر «موج پیوسته‌ی گرادیانی» (Continuous Gradient Waveform) روی پس‌زمینه‌ی سفید،
 * دقیقاً مثل عکس مرجع: بدون قاب تیره، بدون هسته‌ی سفید؛ فقط موج رنگی:
 * - نه میله و نه فاصله؛ ستون‌های چسبیده به هم + هموارسازی دوبل → موج نرم و یکپارچه
 * - گرادیان شفق قطبی (Aurora) در طول موج: بنفش → فیروزه‌ای → سبز، با لبه‌های نرم و محو
 * - حرکت با هموارسازی زمانی به خودِ صدای موسیقی چسبیده و روان است
 * از Visualizer اندروید استفاده می‌کند (فقط تحلیل زنده موج؛ هیچ صدایی ضبط یا ذخیره نمی‌شود).
 * اگر مجوز یا دیتای ویژوآلایزر نبود، خودش با یک موج آرام و نرم نفس می‌کشد.
 */
class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val POINTS = 128      // ستون‌های چسبیده به هم؛ زیاد تا موج «پیوسته» دیده شود
        private const val FRAME_MS = 33L    // حدود ۳۰ فریم در ثانیه → حرکت روان
        private const val SMOOTHING = 0.15f // ضریب نرمی حرکت (کمتر = آروم‌تر، مناسب فضای قبل خواب)

        // پالت شفق قطبی (Aurora) مطابق عکس مرجع، از چپ به راست:
        private val AURORA = intArrayOf(
            0xFFC9A6FF.toInt(), // بنفش کمرنگ
            0xFF93B4FF.toInt(), // آبی یاسی روشن
            0xFF54E0FF.toInt(), // آبی فیروزه‌ای
            0xFF35E9C4.toInt(), // فیروزه‌ای مایل به سبز
            0xFF43E97B.toInt()  // سبز زمردی ملایم
        )
    }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePath = Path()

    private val current = FloatArray(POINTS) // ارتفاع هموارشده‌ی فعلی هر ستون
    private val target = FloatArray(POINTS)  // ارتفاع هدف از موج واقعی صدا
    private val scratch = FloatArray(POINTS) // بافر هموارسازی مکانی

    private var visualizer: Visualizer? = null
    private var waveform: ByteArray? = null
    private var playing = false
    private var frame = 0L
    private var shaderWidth = -1

    init {
        glowPaint.maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
    }

    private val animator = object : Runnable {
        override fun run() {
            frame++
            updateWave()
            invalidate()
            postDelayed(this, FRAME_MS)
        }
    }

    /** شروع/توقف رقص نور؛ sessionId از سرویس پخش می‌آید (۰ = بدون ویژوآلایزر) */
    fun setPlaying(active: Boolean, sessionId: Int = 0) {
        playing = active
        if (active && sessionId != 0) attachVisualizer(sessionId) else detachVisualizer()
        removeCallbacks(animator)
        post(animator)
    }

    private fun attachVisualizer(sessionId: Int) {
        detachVisualizer()
        try {
            val v = Visualizer(sessionId)
            // نرخ نمونه‌برداری در listener تنظیم می‌شود: getMaxCaptureRate() / 2 → تعقیب زنده و نرمِ موج صدا
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, bytes: ByteArray?, samplingRate: Int) {
                    waveform = bytes
                }
                override fun onFftDataCapture(v: Visualizer?, bytes: ByteArray?, samplingRate: Int) { }
            }, Visualizer.getMaxCaptureRate() / 2, true, false)
            v.enabled = true
            visualizer = v
        } catch (_: Exception) {
            // مجوز داده نشده یا جلسه معتبر نیست → حالت انیمیشن آزاد
            visualizer = null
        }
    }

    private fun detachVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) { }
        visualizer = null
        waveform = null
    }

    override fun onDetachedFromWindow() {
        detachVisualizer()
        removeCallbacks(animator)
        super.onDetachedFromWindow()
    }

    /** ارتفاع هدف هر ستون از موج واقعی صدا + هموارسازی مکانی (موج پیوسته، نه میله‌های جدا) */
    private fun updateWave() {
        val wf = waveform
        if (playing && wf != null && wf.size > POINTS) {
            for (i in 0 until POINTS) {
                // هر ستون، اوج دامنه‌ی یک بازه‌ی کوچک از موج صوتی است
                val start = i * wf.size / POINTS
                val end = (((i + 1) * wf.size / POINTS) - 1).coerceAtLeast(start)
                var peak = 0
                var j = start
                while (j <= end) {
                    val a = abs(wf[j].toInt() - 128)
                    if (a > peak) peak = a
                    j++
                }
                val v = (peak / 128f).pow(0.7f) // فشرده‌سازی نرم؛ موج متراکم و جمع‌وجور، حتی در صداهای آروم
                target[i] = (0.08f + 0.92f * v).coerceIn(0.08f, 1f)
            }
            spatialSmooth()
            spatialSmooth()
        } else {
            // موج آرامِ نفس‌کش در حالت سکون یا وقتی مجوز ویژوآلایزر داده نشده
            for (i in 0 until POINTS) {
                val x = i / (POINTS - 1f)
                val travel = 0.5f + 0.5f * sin(frame * 0.03f + x * 6.2f)
                val shape = 0.5f + 0.5f * sin(x * 2.4f + 1.3f)
                target[i] = if (playing) 0.12f + 0.45f * travel * shape else 0.08f + 0.12f * travel * shape
            }
            spatialSmooth()
        }

        // حرکت نرم به سمت هدف → هماهنگ و روان با خودِ موسیقی
        for (i in 0 until POINTS) {
            current[i] += (target[i] - current[i]) * SMOOTHING
        }
    }

    /** فیلتر میانگین همسایه‌ها (۱،۲،۱) روی ارتفاع‌ها → دندانه‌ها محو می‌شوند و موج نرم و پیوسته می‌شود */
    private fun spatialSmooth() {
        System.arraycopy(target, 0, scratch, 0, POINTS)
        for (i in 0 until POINTS) {
            val l = scratch[max(i - 1, 0)]
            val r = scratch[min(i + 1, POINTS - 1)]
            target[i] = (l + 2f * scratch[i] + r) / 4f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val mid = h / 2f

        // گرادیان شفق قطبی در طول موج: بنفش کمرنگ → فیروزه‌ای → سبز زمردی (مثل عکس مرجع)
        if (shaderWidth != width) {
            shaderWidth = width
            val shader = LinearGradient(0f, 0f, w, 0f, AURORA, null, Shader.TileMode.CLAMP)
            bodyPaint.shader = shader
            glowPaint.shader = shader
        }

        val maxHalf = h * 0.40f
        val colW = w / POINTS

        // ۱) لایه‌ی لبه‌ی نرم (Glow): موجِ محو و کمی پهن‌تر → لبه‌ها روی سفید نرم و محو دیده می‌شوند
        buildWavePath(w, mid, maxHalf, expand = h * 0.02f)
        glowPaint.alpha = 70
        canvas.drawPath(wavePath, glowPaint)

        // ۲) بدنه‌ی موج پیوسته: ستون‌های چسبیده به هم، بدون هیچ فاصله‌ای
        var x = 0f
        for (i in 0 until POINTS) {
            val c = current[i].coerceIn(0.03f, 1f)
            val half = maxHalf * c
            canvas.drawRect(x, mid - half, x + colW, mid + half, bodyPaint)
            x += colW
        }
    }

    /** ساخت مسیر بسته‌ی موج (لبه‌ی قرینه‌ی بالا و پایین) برای هاله‌ی نرم */
    private fun buildWavePath(w: Float, mid: Float, maxHalf: Float, expand: Float) {
        wavePath.reset()
        val colW = w / POINTS
        wavePath.moveTo(0f, mid - maxHalf * current[0].coerceIn(0.03f, 1f) - expand)
        for (i in 1 until POINTS) {
            wavePath.lineTo(i * colW, mid - maxHalf * current[i].coerceIn(0.03f, 1f) - expand)
        }
        for (i in POINTS - 1 downTo 0) {
            wavePath.lineTo(i * colW, mid + maxHalf * current[i].coerceIn(0.03f, 1f) + expand)
        }
        wavePath.close()
    }
}