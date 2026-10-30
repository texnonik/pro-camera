package com.texnonik.procamera

import android.content.Context
import androidx.core.content.edit

/** Все пользовательские настройки камеры. 0 / -1 означают «авто». */
data class ProSettings(
    val iso: Int = 0,
    val shutterNs: Long = 0L,
    /** -1 = автофокус, 0 = бесконечность, 1 = минимальная дистанция. */
    val focus: Float = -1f,
    val wbMode: Int = WB_AUTO,
    val kelvin: Int = 5500,
    val ev: Int = 0,
    val flash: Int = FLASH_OFF,
    val timer: Int = 0,
    val ratio: Int = RATIO_4_3,
    val grid: Boolean = true,
    val front: Boolean = false,
    /** Выбранный объектив (cameraId) для задних и передних камер; "" = основной. */
    val backCameraId: String = "",
    val frontCameraId: String = "",
) {
    val isAllAuto: Boolean
        get() = iso == 0 && shutterNs == 0L && focus < 0f && wbMode == WB_AUTO && ev == 0

    fun resetToAuto() = copy(iso = 0, shutterNs = 0L, focus = -1f, wbMode = WB_AUTO, ev = 0)

    companion object {
        const val WB_AUTO = 0
        const val WB_INCANDESCENT = 1
        const val WB_FLUORESCENT = 2
        const val WB_DAYLIGHT = 3
        const val WB_CLOUDY = 4
        const val WB_KELVIN = 5

        const val FLASH_OFF = 0
        const val FLASH_AUTO = 1
        const val FLASH_ON = 2

        const val RATIO_4_3 = 0
        /** Кадр с пропорциями экрана телефона, видоискатель на весь экран. */
        const val RATIO_FULL = 1
        const val JPEG_QUALITY = 95

        val ISO_VALUES = listOf(
            50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
            1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 12800,
        )

        private const val SEC = 1_000_000_000L
        val SHUTTER_VALUES = listOf(
            SEC / 8000, SEC / 4000, SEC / 2000, SEC / 1000, SEC / 500, SEC / 250,
            SEC / 125, SEC / 60, SEC / 30, SEC / 15, SEC / 8, SEC / 4, SEC / 2,
            SEC, 2 * SEC, 4 * SEC, 8 * SEC, 15 * SEC, 30 * SEC,
        )

        val WB_NAMES = listOf("Авто", "Лампа", "Флуор.", "Солнце", "Облачно", "Кельвин")
        val TIMER_VALUES = listOf(0, 3, 10)

        fun shutterLabel(ns: Long): String {
            if (ns <= 0L) return "—"
            return if (ns < SEC) {
                "1/${Math.round(SEC.toDouble() / ns)}"
            } else {
                val s = ns.toDouble() / SEC
                if (s % 1.0 == 0.0) "${s.toInt()}\"" else String.format("%.1f\"", s)
            }
        }
    }
}

/** Сохраняет настройки между запусками. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("pro_settings", Context.MODE_PRIVATE)

    fun load(): ProSettings {
        val d = ProSettings()
        return ProSettings(
            iso = prefs.getInt("iso", d.iso),
            shutterNs = prefs.getLong("shutterNs", d.shutterNs),
            focus = prefs.getFloat("focus", d.focus),
            wbMode = prefs.getInt("wbMode", d.wbMode),
            kelvin = prefs.getInt("kelvin", d.kelvin),
            ev = prefs.getInt("ev", d.ev),
            flash = prefs.getInt("flash", d.flash),
            timer = prefs.getInt("timer", d.timer),
            ratio = prefs.getInt("ratio", d.ratio),
            grid = prefs.getBoolean("grid", d.grid),
            front = prefs.getBoolean("front", d.front),
            backCameraId = prefs.getString("backCameraId", d.backCameraId) ?: "",
            frontCameraId = prefs.getString("frontCameraId", d.frontCameraId) ?: "",
        )
    }

    fun save(s: ProSettings) {
        prefs.edit {
            putInt("iso", s.iso)
            putLong("shutterNs", s.shutterNs)
            putFloat("focus", s.focus)
            putInt("wbMode", s.wbMode)
            putInt("kelvin", s.kelvin)
            putInt("ev", s.ev)
            putInt("flash", s.flash)
            putInt("timer", s.timer)
            putInt("ratio", s.ratio)
            putBoolean("grid", s.grid)
            putBoolean("front", s.front)
            putString("backCameraId", s.backCameraId)
            putString("frontCameraId", s.frontCameraId)
        }
    }
}
