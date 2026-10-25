// What the instrument remembers between runs: the channel plan and gain it
// pushes on connect, and the display choices. GPL-3.0-or-later.
package com.onebody.widscan58

import android.content.Context
import com.onebody.widscan58.core.Bands
import com.onebody.widscan58.core.DeviceCommands
import com.onebody.widscan58.core.DeviceSettings
import com.onebody.widscan58.core.GainMode
import com.onebody.widscan58.core.LoPlan

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("widscan58", Context.MODE_PRIVATE)

    /** The board is put in this state on every connect. */
    fun deviceSettings(): DeviceSettings = DeviceSettings.of(
        channels = channels,
        mode = loPlan,
        gainMode = gainMode,
        gain = gain,
        enabled = enabledMask,
    )

    val channels: List<String>
        get() = sp.getString("channels", "")!!.split(',').filter { it.isNotEmpty() }.ifEmpty { Bands.names("R") }

    /** Store a plan the device can tune; the band chips and the editor both write here. */
    fun setChannels(names: List<String>) = put("channels", Bands.normalize(names).joinToString(","))

    /** Which channel ids of the plan the device measures, as an 8-bit mask. */
    var enabledMask: Int
        get() = sp.getInt("enabled", 0xff) and 0xff
        set(value) = sp.edit().putInt("enabled", value and 0xff).apply()

    var loPlan: LoPlan
        get() = LoPlan.entries.firstOrNull { it.name == sp.getString("loPlan", LoPlan.GROUP.name) } ?: LoPlan.GROUP
        set(value) = sp.edit().putString("loPlan", value.name).apply()

    var gainMode: GainMode
        get() = GainMode.entries.firstOrNull { it.name == sp.getString("gainMode", GainMode.MANUAL.name) }
            ?: GainMode.MANUAL
        set(value) = sp.edit().putString("gainMode", value.name).apply()

    val gain: Int get() = sp.getInt("gain", 45)

    /** The ceiling is the device's own when it reports one; manual gain never leaves it. */
    fun setGain(gain: Int, max: Int = GAIN_MAX) =
        sp.edit().putInt("gain", gain.coerceIn(0, max.coerceAtLeast(0))).apply()

    var dbMin: Int
        get() = sp.getInt("dbMin", -85)
        set(value) = sp.edit().putInt("dbMin", value).apply()
    var dbMax: Int
        get() = sp.getInt("dbMax", -20)
        set(value) = sp.edit().putInt("dbMax", value).apply()
    var showMaxHold: Boolean
        get() = sp.getBoolean("showMax", true)
        set(value) = sp.edit().putBoolean("showMax", value).apply()
    var showAverage: Boolean
        get() = sp.getBoolean("showAvg", true)
        set(value) = sp.edit().putBoolean("showAvg", value).apply()

    /** The history card's window; the store's ring is deep enough for the widest choice. */
    var historySeconds: Int
        get() = sp.getInt("historySeconds", 30)
        set(value) = sp.edit().putInt("historySeconds", value).apply()

    /** The EMA when the device reports one, the raw RSSI when it does not. */
    var historySmoothed: Boolean
        get() = sp.getBoolean("historyEma", true)
        set(value) = sp.edit().putBoolean("historyEma", value).apply()

    private fun put(key: String, value: String) = sp.edit().putString(key, value).apply()

    companion object {
        /** The ceiling used until the board reports its own `gain_max`. */
        const val GAIN_MAX = DeviceCommands.GAIN_FALLBACK_MAX
    }
}
