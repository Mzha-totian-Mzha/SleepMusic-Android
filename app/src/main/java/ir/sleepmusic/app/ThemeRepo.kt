package ir.sleepmusic.app

import android.graphics.Color

/**
 * شش تم رنگی برنامه — دقیقاً همان رنگ‌های تأییدشده در پیش‌نمایش:
 * ۱ بنفش شبانه، ۲ سبز آرامش، ۳ شب تیره، ۴ غروب گرم، ۵ سبز و یاسی (پیش‌فرض)، ۶ ارکیده
 * تم «ارکیده» از روی عکس خود کاربر (یاسی روشن + دایره‌های بنفش + دکمه‌های سبز) ساخته شده است.
 */
data class AppTheme(
    val id: String,
    val num: String,
    val label: String,
    val emoji: String,
    val statusBar: Int,
    val headerColors: IntArray,
    val bodyBg: Int,
    val cardBg: Int,
    val textPrimary: Int,
    val textMuted: Int,
    val textFaint: Int,
    val btnColors: IntArray,
    val eqColors: IntArray,
    val progressColor: Int,
    val chipABg: Int,
    val chipAIcon: Int,
    val chipBBg: Int,
    val chipBIcon: Int,
    val pillPlayBg: Int,
    val pillPlayText: Int,
    val pillIdleBg: Int,
    val pillIdleText: Int,
    val rowIconBg: Int,
    val rowIconColor: Int,
    val footer: Int
)

object ThemeRepo {

    private fun c(hex: String): Int = Color.parseColor(hex)

    val themes: List<AppTheme> = listOf(
        AppTheme(
            id = "violet",
            num = "۱",
            label = "بنفش شبانه",
            emoji = "🌌",
            statusBar = c("#7C3AED"),
            headerColors = intArrayOf(c("#A78BFA"), c("#8B5CF6"), c("#6D28D9")),
            bodyBg = c("#F5F1FC"),
            cardBg = Color.WHITE,
            textPrimary = c("#2B1E4E"),
            textMuted = c("#7C6FA6"),
            textFaint = c("#B9AED8"),
            btnColors = intArrayOf(c("#8B5CF6"), c("#D946EF"), c("#EC4899")),
            eqColors = intArrayOf(c("#8B5CF6"), c("#D946EF"), c("#F472B6"), c("#34D399")),
            progressColor = c("#8B5CF6"),
            chipABg = c("#F5EFFF"),
            chipAIcon = c("#7C3AED"),
            chipBBg = c("#E7F9F1"),
            chipBIcon = c("#059669"),
            pillPlayBg = c("#FCE7F3"),
            pillPlayText = c("#BE185D"),
            pillIdleBg = c("#EDE9FE"),
            pillIdleText = c("#6D28D9"),
            rowIconBg = c("#EDE9FE"),
            rowIconColor = c("#6D28D9"),
            footer = c("#C4B8E4")
        ),
        AppTheme(
            id = "mint",
            num = "۲",
            label = "سبز آرامش",
            emoji = "🍃",
            statusBar = c("#10B981"),
            headerColors = intArrayOf(c("#6EE7B7"), c("#10B981"), c("#0D9488")),
            bodyBg = c("#F0FAF5"),
            cardBg = Color.WHITE,
            textPrimary = c("#123B2E"),
            textMuted = c("#6B9A85"),
            textFaint = c("#A9CDBB"),
            btnColors = intArrayOf(c("#10B981"), c("#14B8A6")),
            eqColors = intArrayOf(c("#10B981"), c("#2DD4BF"), c("#FBBF24"), c("#FB7185")),
            progressColor = c("#10B981"),
            chipABg = c("#FEF6E7"),
            chipAIcon = c("#D97706"),
            chipBBg = c("#E7F9F1"),
            chipBIcon = c("#059669"),
            pillPlayBg = c("#FEF3C7"),
            pillPlayText = c("#B45309"),
            pillIdleBg = c("#D1FAE5"),
            pillIdleText = c("#047857"),
            rowIconBg = c("#D1FAE5"),
            rowIconColor = c("#047857"),
            footer = c("#A9CDBB")
        ),
        AppTheme(
            id = "night",
            num = "۳",
            label = "شب تیره",
            emoji = "🌙",
            statusBar = c("#3A3153"),
            headerColors = intArrayOf(c("#6D5CA8"), c("#4A3C77"), c("#362D55")),
            bodyBg = c("#241F33"),
            cardBg = c("#2F2942"),
            textPrimary = c("#F1EDFA"),
            textMuted = c("#A79FC4"),
            textFaint = c("#6E6690"),
            btnColors = intArrayOf(c("#F59E0B"), c("#F43F5E")),
            eqColors = intArrayOf(c("#FBBF24"), c("#FB7185"), c("#C084FC"), c("#34D399")),
            progressColor = c("#FBBF24"),
            chipABg = c("#3A3352"),
            chipAIcon = c("#FBBF24"),
            chipBBg = c("#3A3352"),
            chipBIcon = c("#2DD4BF"),
            pillPlayBg = c("#473C63"),
            pillPlayText = c("#E9D5FF"),
            pillIdleBg = c("#3A3352"),
            pillIdleText = c("#FBBF24"),
            rowIconBg = c("#3A3352"),
            rowIconColor = c("#FBBF24"),
            footer = c("#6E6690")
        ),
        AppTheme(
            id = "sunset",
            num = "۴",
            label = "غروب گرم",
            emoji = "🌅",
            statusBar = c("#FF8A3D"),
            headerColors = intArrayOf(c("#FFB56B"), c("#FF8A5E"), c("#FF4D7E")),
            bodyBg = c("#FFF7EF"),
            cardBg = Color.WHITE,
            textPrimary = c("#43302B"),
            textMuted = c("#8A7368"),
            textFaint = c("#C9B4A7"),
            btnColors = intArrayOf(c("#FF8A3D"), c("#FF5E6E"), c("#FF4D7E")),
            eqColors = intArrayOf(c("#FF8A3D"), c("#FF4D7E"), c("#A855F7"), c("#0FB5A6")),
            progressColor = c("#FF8A3D"),
            chipABg = c("#FFF4EC"),
            chipAIcon = c("#FF6A3D"),
            chipBBg = c("#E6FAF7"),
            chipBIcon = c("#0D9488"),
            pillPlayBg = c("#FDE8F1"),
            pillPlayText = c("#E11D74"),
            pillIdleBg = c("#FFF0E3"),
            pillIdleText = c("#EA580C"),
            rowIconBg = c("#FFF0E3"),
            rowIconColor = c("#EA580C"),
            footer = c("#C9B4A7")
        ),
        AppTheme(
            id = "blend",
            num = "۵",
            label = "سبز و یاسی",
            emoji = "🌿",
            statusBar = c("#10B981"),
            headerColors = intArrayOf(c("#34D399"), c("#10B981"), c("#A78BFA"), c("#8B5CF6")),
            bodyBg = c("#F3F8F5"),
            cardBg = Color.WHITE,
            textPrimary = c("#1E3B33"),
            textMuted = c("#6B9588"),
            textFaint = c("#A8C6BC"),
            btnColors = intArrayOf(c("#10B981"), c("#14B8A6"), c("#8B5CF6")),
            eqColors = intArrayOf(c("#10B981"), c("#2DD4BF"), c("#A78BFA"), c("#8B5CF6")),
            progressColor = c("#10B981"),
            chipABg = c("#F3EEFE"),
            chipAIcon = c("#7C3AED"),
            chipBBg = c("#E7F9F1"),
            chipBIcon = c("#059669"),
            pillPlayBg = c("#EDE9FE"),
            pillPlayText = c("#6D28D9"),
            pillIdleBg = c("#D1FAE5"),
            pillIdleText = c("#047857"),
            rowIconBg = c("#EDE9FE"),
            rowIconColor = c("#7C3AED"),
            footer = c("#A8C6BC")
        ),
        AppTheme(
            id = "orchid",
            num = "۶",
            label = "ارکیده",
            emoji = "💜",
            statusBar = c("#7C3AED"),
            headerColors = intArrayOf(c("#D8C9FA"), c("#A78BFA"), c("#7C3AED")),
            bodyBg = c("#F6F1FC"),
            cardBg = Color.WHITE,
            textPrimary = c("#2E2154"),
            textMuted = c("#7A6FA0"),
            textFaint = c("#B4A9D6"),
            btnColors = intArrayOf(c("#7C3AED"), c("#9333EA"), c("#C026D3")),
            eqColors = intArrayOf(c("#7C3AED"), c("#A855F7"), c("#C4B5FD"), c("#FB923C")),
            progressColor = c("#7C3AED"),
            chipABg = c("#F3EEFE"),
            chipAIcon = c("#7C3AED"),
            chipBBg = c("#FFF4EC"),
            chipBIcon = c("#EA580C"),
            pillPlayBg = c("#F3E8FF"),
            pillPlayText = c("#6B21A8"),
            pillIdleBg = c("#FFF4EC"),
            pillIdleText = c("#C2410C"),
            rowIconBg = c("#F3EEFE"),
            rowIconColor = c("#7C3AED"),
            footer = c("#B4A9D6")
        )
    )

    fun byId(id: String?): AppTheme = themes.firstOrNull { it.id == id } ?: themes[4]

    /** تبدیل ارقام لاتین به فارسی برای چیپ‌ها و پیام‌ها */
    fun faDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            if (ch in '0'..'9') {
                sb.append("۰۱۲۳۴۵۶۷۸۹"[ch - '0'])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun formatSecs(total: Int): String {
        val m = total / 60
        val s = total % 60
        val mm = if (m < 10) "0" + m else "" + m
        val ss = if (s < 10) "0" + s else "" + s
        return mm + ":" + ss
    }
}
