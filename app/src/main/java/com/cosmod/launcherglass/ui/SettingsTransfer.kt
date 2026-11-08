package com.cosmod.launcherglass.ui

import android.content.SharedPreferences
import com.cosmod.launcherglass.BuildConfig
import org.json.JSONObject
import java.io.InputStream

/** Versioned, bounded presets: validate the entire file before touching preferences. */
internal object SettingsTransfer {
    private const val FORMAT = "cherrypick-icons-settings"
    private const val VERSION = 1
    private const val MAX_BYTES = 64 * 1024
    private val defaults get() = SettingsDefaults.portable()

    /** Scoped (folder/dock) glass keys: absence means "inherit the icon value". */
    private fun optionalScoped(key: String) = !ScopedSettingsKeys.isOwnKey(key) && ScopedSettingsKeys.baseOf(key) != null

    fun export(prefs: SharedPreferences): String {
        val values = JSONObject()
        val saved = prefs.all
        for ((key, default) in defaults.toSortedMap()) {
            // Absence means inherit the icon value, and must survive a round trip.
            if (optionalScoped(key) && !saved.containsKey(key)) continue
            values.put(key, saved[key] ?: default)
        }
        return JSONObject().put("format", FORMAT).put("version", VERSION)
            .put("moduleVersion", BuildConfig.VERSION_NAME).put("settings", values).toString(2) + "\n"
    }

    fun read(input: InputStream): Map<String, Any> {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= MAX_BYTES) { "Preset exceeds 64 KB" }
            bytes.write(buffer, 0, count)
        }
        return parse(bytes.toString("UTF-8"))
    }

    fun parse(text: String): Map<String, Any> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Preset exceeds 64 KB" }
        val document = JSONObject(text.removePrefix("\uFEFF"))
        require(document.opt("format") == FORMAT) { "Not a CherryPick preset" }
        require(document.opt("version") == VERSION) { "Unsupported preset version" }
        val source = document.getJSONObject("settings")
        val schema = defaults
        val values = linkedMapOf<String, Any>()
        for (key in source.keys()) {
            val default = schema[key] ?: continue // New optional settings may be ignored by older modules.
            val value = source.get(key)
            when (default) {
                is Boolean -> { require(value is Boolean) { "Invalid $key" }; values[key] = value }
                is Int -> {
                    require(value is Int || value is Long) { "Invalid $key" }
                    val number = (value as Number).toLong()
                    require(number in range(key)) { "Out of range: $key" }
                    values[key] = number.toInt()
                }
            }
        }
        require(values.isNotEmpty()) { "Empty preset" }
        return values
    }

    private fun range(key: String): LongRange {
        val base = ScopedSettingsKeys.baseOf(key) ?: key
        com.cosmod.launcherglass.util.DesktopSizing.ranges[base]?.let {return it.first.toLong()..it.last.toLong()}
        return when (base) {
            SettingsProvider.KEY_COLOR_HUE, SettingsProvider.KEY_LIGHT_HUE, SettingsProvider.KEY_DARK_HUE -> 0L..360L
            SettingsProvider.KEY_BLUR_SATURATION, SettingsProvider.KEY_BLUR_VIBRANCY -> 0L..200L
            SettingsProvider.KEY_LIQUID_DISTORTION, SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_DISTORTION -> 0L..300L
            SettingsProvider.KEY_BLUR_RADIUS -> 0L..50L
            SettingsProvider.KEY_LIVE_FPS -> 1L..60L
            SettingsProvider.KEY_COLOR_DEPTH_BITS -> 8L..24L
            SettingsProvider.KEY_COLOR_DEPTH_STAGE -> 0L..3L
            SettingsProvider.KEY_DOCK_SIDE_PADDING, SettingsProvider.KEY_DOCK_VERTICAL_PADDING -> 0L..SettingsProvider.DOCK_PADDING_MAX.toLong()
            SettingsProvider.KEY_BLUR_MODE, SettingsProvider.KEY_BLUR_STYLE,
            SettingsProvider.KEY_ICON_APPEARANCE, SettingsProvider.KEY_COLOR_MODE -> 0L..1L
            else -> 0L..100L
        }
    }

    fun apply(prefs: SharedPreferences, values: Map<String, Any>) {
        val editor = prefs.edit()
        // Complete replacement of portable settings; retain local state and migration markers.
        for ((key, default) in defaults) {
            val value = values[key] ?: if (optionalScoped(key)) null else default
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                else -> editor.remove(key)
            }
        }
        check(editor.commit()) { "Could not save preset" }
    }
}
