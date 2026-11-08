package com.cosmod.launcherglass.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

class SettingsProvider : ContentProvider() {

    private lateinit var prefs: SharedPreferences
    private var desktopRequestAt=0L

    @Synchronized private fun desktopRequest(consume:Boolean):Boolean {
        val now=android.os.SystemClock.elapsedRealtime()
        if(!consume){desktopRequestAt=now;return true}
        val pending=desktopRequestAt
        desktopRequestAt=0L
        return pending>0L && now-pending in 0L..60_000L
    }

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        SettingsDefaults.initialize(ctx, prefs)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val bundle = Bundle()
        when (method) {
            METHOD_OPEN_DESKTOP -> {
                enforceCaller(allowLauncher=false)
                bundle.putBoolean("pending",desktopRequest(false))
            }
            METHOD_CONSUME_DESKTOP -> {
                enforceCaller(allowLauncher=true)
                bundle.putBoolean("pending",desktopRequest(true))
            }
            METHOD_REPORT_ICON_APPEARANCE -> {
                enforceCaller(allowLauncher = true)
                val support=extras?.getInt(KEY_NATIVE_FLUX_SUPPORT,0) ?: 0
                if(support in 1..2 && prefs.getInt(KEY_NATIVE_FLUX_SUPPORT,0)!=support) {
                    prefs.edit().putInt(KEY_NATIVE_FLUX_SUPPORT,support).apply()
                    context?.contentResolver?.notifyChange(CONTENT_URI,null)
                }
                if (extras?.containsKey(KEY_NATIVE_ICON_DARK) == true) {
                    val dark = extras.getBoolean(KEY_NATIVE_ICON_DARK)
                    if (!prefs.contains(KEY_NATIVE_ICON_DARK) || prefs.getBoolean(KEY_NATIVE_ICON_DARK, false) != dark) {
                        prefs.edit().putBoolean(KEY_NATIVE_ICON_DARK, dark).apply()
                        context?.contentResolver?.notifyChange(CONTENT_URI, null)
                    }
                }
            }
            METHOD_GET_ALL -> {
                com.cosmod.launcherglass.util.DesktopSizing.defaults.forEach {(key,fallback) ->
                    bundle.putInt(key,prefs.getInt(key,fallback))
                }
                bundle.putBoolean(KEY_GPU_EFFECTS,prefs.getBoolean(KEY_GPU_EFFECTS,true))
                for(scope in ScopedSettingsKeys.all){
                    bundle.putBoolean(scope.own,prefs.getBoolean(scope.own,false))
                    for(base in scope.integers){
                        val key=scope.key(base)
                        bundle.putInt(key,prefs.getInt(key,prefs.getInt(base,SettingsDefaults.integer(base))))
                    }
                    for(base in scope.booleans){
                        val key=scope.key(base)
                        bundle.putBoolean(key,prefs.getBoolean(key,prefs.getBoolean(base,SettingsDefaults.boolean(base))))
                    }
                }
                bundle.putBoolean(KEY_PHOTO_WIDGET_BLUR,prefs.getBoolean(KEY_PHOTO_WIDGET_BLUR,false))
                bundle.putBoolean(KEY_PHOTO_WIDGET_TINT,prefs.getBoolean(KEY_PHOTO_WIDGET_TINT,false))
                bundle.putInt(KEY_PHOTO_WIDGET_LIQUID_WIDTH,prefs.getInt(KEY_PHOTO_WIDGET_LIQUID_WIDTH,SettingsDefaults.integer(KEY_PHOTO_WIDGET_LIQUID_WIDTH)))
                bundle.putInt(KEY_PHOTO_WIDGET_LIQUID_DISTORTION,prefs.getInt(KEY_PHOTO_WIDGET_LIQUID_DISTORTION,SettingsDefaults.integer(KEY_PHOTO_WIDGET_LIQUID_DISTORTION)))
                bundle.putBoolean(KEY_FOLDER_ICONS_BLUR_ENABLED,prefs.getBoolean(KEY_FOLDER_ICONS_BLUR_ENABLED,true))
                bundle.putInt(KEY_PHOTO_WIDGET_OPACITY,prefs.getInt(KEY_PHOTO_WIDGET_OPACITY,55))
                bundle.putBoolean(KEY_DEVELOPER_MODE,prefs.getBoolean(KEY_DEVELOPER_MODE,SettingsDefaults.boolean(KEY_DEVELOPER_MODE)))
                bundle.putBoolean(KEY_COLOR_DEPTH_ENABLED,prefs.getBoolean(KEY_COLOR_DEPTH_ENABLED,false))
                bundle.putInt(KEY_COLOR_DEPTH_BITS,prefs.getInt(KEY_COLOR_DEPTH_BITS,8))
                bundle.putInt(KEY_COLOR_DEPTH_STAGE,prefs.getInt(KEY_COLOR_DEPTH_STAGE,3).coerceIn(0,3))
                bundle.putBoolean(KEY_HAZE_ENABLED,prefs.getBoolean(KEY_HAZE_ENABLED,false))
                bundle.putBoolean(KEY_DISABLE_EDGE_OPTIMIZATION,prefs.getBoolean(KEY_DISABLE_EDGE_OPTIMIZATION,SettingsDefaults.boolean(KEY_DISABLE_EDGE_OPTIMIZATION)))
                bundle.putBoolean(KEY_OPTIMIZE_MINI_ICONS,prefs.getBoolean(KEY_OPTIMIZE_MINI_ICONS,SettingsDefaults.boolean(KEY_OPTIMIZE_MINI_ICONS)))
                bundle.putBoolean(KEY_DOCK_ENABLED,prefs.getBoolean(KEY_DOCK_ENABLED,SettingsDefaults.boolean(KEY_DOCK_ENABLED)))
                bundle.putBoolean(KEY_DOCK_AUTO_LENGTH,prefs.getBoolean(KEY_DOCK_AUTO_LENGTH,SettingsDefaults.boolean(KEY_DOCK_AUTO_LENGTH)))
                for(key in listOf(KEY_DOCK_SIDE_PADDING,KEY_DOCK_VERTICAL_PADDING,KEY_DOCK_RADIUS))bundle.putInt(key,prefs.getInt(key,SettingsDefaults.integer(key)))
                bundle.putInt(KEY_ARTWORK_TRANSPARENCY,prefs.getInt(KEY_ARTWORK_TRANSPARENCY,SettingsDefaults.integer(KEY_ARTWORK_TRANSPARENCY)).coerceIn(0,100))
                bundle.putInt(KEY_NATIVE_FLUX_SUPPORT,prefs.getInt(KEY_NATIVE_FLUX_SUPPORT,0))
                if (prefs.contains(KEY_NATIVE_ICON_DARK)) bundle.putBoolean(KEY_NATIVE_ICON_DARK, prefs.getBoolean(KEY_NATIVE_ICON_DARK, false))
                bundle.putInt(KEY_LIGHT_HUE, prefs.getInt(KEY_LIGHT_HUE, SettingsDefaults.integer(KEY_LIGHT_HUE)))
                bundle.putInt(KEY_LIGHT_SATURATION, prefs.getInt(KEY_LIGHT_SATURATION, SettingsDefaults.integer(KEY_LIGHT_SATURATION)))
                bundle.putInt(KEY_LIGHT_BRIGHTNESS, prefs.getInt(KEY_LIGHT_BRIGHTNESS, SettingsDefaults.integer(KEY_LIGHT_BRIGHTNESS)))
                bundle.putInt(KEY_DARK_HUE, prefs.getInt(KEY_DARK_HUE, SettingsDefaults.integer(KEY_DARK_HUE)))
                bundle.putInt(KEY_DARK_SATURATION, prefs.getInt(KEY_DARK_SATURATION, SettingsDefaults.integer(KEY_DARK_SATURATION)))
                bundle.putInt(KEY_DARK_BRIGHTNESS, prefs.getInt(KEY_DARK_BRIGHTNESS, SettingsDefaults.integer(KEY_DARK_BRIGHTNESS)))
                bundle.putInt(KEY_ICON_APPEARANCE, prefs.getInt(KEY_ICON_APPEARANCE, SettingsDefaults.integer(KEY_ICON_APPEARANCE)))
                bundle.putInt(KEY_CLEAR_BRIGHTNESS, prefs.getInt(KEY_CLEAR_BRIGHTNESS, SettingsDefaults.integer(KEY_CLEAR_BRIGHTNESS)))
                bundle.putInt(KEY_COLOR_MODE, prefs.getInt(KEY_COLOR_MODE, SettingsDefaults.integer(KEY_COLOR_MODE)))
                bundle.putInt(KEY_COLOR_HUE, prefs.getInt(KEY_COLOR_HUE, SettingsDefaults.integer(KEY_COLOR_HUE)))
                bundle.putInt(KEY_COLOR_SATURATION, prefs.getInt(KEY_COLOR_SATURATION, SettingsDefaults.integer(KEY_COLOR_SATURATION)))
                bundle.putInt(KEY_COLOR_BRIGHTNESS, prefs.getInt(KEY_COLOR_BRIGHTNESS, SettingsDefaults.integer(KEY_COLOR_BRIGHTNESS)))
                bundle.putBoolean(KEY_ENABLED, prefs.getBoolean(KEY_ENABLED, SettingsDefaults.boolean(KEY_ENABLED)))
                bundle.putBoolean(KEY_BLUR_TONE_ENABLED,prefs.getBoolean(KEY_BLUR_TONE_ENABLED,SettingsDefaults.boolean(KEY_BLUR_TONE_ENABLED)))
                bundle.putInt(KEY_BLUR_VIBRANCY,prefs.getInt(KEY_BLUR_VIBRANCY,SettingsDefaults.integer(KEY_BLUR_VIBRANCY)))
                bundle.putInt(KEY_BLUR_SATURATION, prefs.getInt(KEY_BLUR_SATURATION, SettingsDefaults.integer(KEY_BLUR_SATURATION)))
                bundle.putInt(KEY_BLUR_LIGHTENING, prefs.getInt(KEY_BLUR_LIGHTENING, SettingsDefaults.integer(KEY_BLUR_LIGHTENING)))
                bundle.putBoolean(KEY_BLUR_RADIUS_ENABLED, prefs.getBoolean(KEY_BLUR_RADIUS_ENABLED, SettingsDefaults.boolean(KEY_BLUR_RADIUS_ENABLED)))
                bundle.putBoolean(KEY_BLUR_LOCATIONS_ENABLED, prefs.getBoolean(KEY_BLUR_LOCATIONS_ENABLED, SettingsDefaults.boolean(KEY_BLUR_LOCATIONS_ENABLED)))
                bundle.putInt(KEY_BG_OPACITY, prefs.getInt(KEY_BG_OPACITY, SettingsDefaults.integer(KEY_BG_OPACITY)))
                bundle.putBoolean(KEY_BLUR_ENABLED, prefs.getBoolean(KEY_BLUR_ENABLED, SettingsDefaults.boolean(KEY_BLUR_ENABLED)))
                bundle.putBoolean(KEY_FOLDER_BLUR_ENABLED, prefs.getBoolean(KEY_FOLDER_BLUR_ENABLED, SettingsDefaults.boolean(KEY_FOLDER_BLUR_ENABLED)))
                bundle.putBoolean(KEY_DRAWER_BLUR_ENABLED, prefs.getBoolean(KEY_DRAWER_BLUR_ENABLED, SettingsDefaults.boolean(KEY_DRAWER_BLUR_ENABLED)))
                bundle.putInt(KEY_BLUR_RADIUS, prefs.getInt(KEY_BLUR_RADIUS, SettingsDefaults.integer(KEY_BLUR_RADIUS)))
                bundle.putInt(KEY_BLUR_MODE, prefs.getInt(KEY_BLUR_MODE, SettingsDefaults.integer(KEY_BLUR_MODE)))
                bundle.putInt(KEY_BLUR_STYLE, prefs.getInt(KEY_BLUR_STYLE, SettingsDefaults.integer(KEY_BLUR_STYLE)))
                bundle.putInt(KEY_BLUR_QUALITY,prefs.getInt(KEY_BLUR_QUALITY,SettingsDefaults.integer(KEY_BLUR_QUALITY)))
                bundle.putInt(KEY_LIQUID_WIDTH,prefs.getInt(KEY_LIQUID_WIDTH,SettingsDefaults.integer(KEY_LIQUID_WIDTH)))
                bundle.putInt(KEY_LIQUID_DISTORTION,prefs.getInt(KEY_LIQUID_DISTORTION,SettingsDefaults.integer(KEY_LIQUID_DISTORTION)))
                bundle.putInt(KEY_LIVE_FPS, prefs.getInt(KEY_LIVE_FPS, SettingsDefaults.integer(KEY_LIVE_FPS)))
                bundle.putBoolean(KEY_IDLE_PAUSE, prefs.getBoolean(KEY_IDLE_PAUSE, SettingsDefaults.boolean(KEY_IDLE_PAUSE)))
                bundle.putInt(KEY_GLYPH_STROKE_WIDTH, prefs.getInt(KEY_GLYPH_STROKE_WIDTH, SettingsDefaults.integer(KEY_GLYPH_STROKE_WIDTH)))
                bundle.putInt(KEY_GLYPH_STROKE_BRIGHTNESS, prefs.getInt(KEY_GLYPH_STROKE_BRIGHTNESS, SettingsDefaults.integer(KEY_GLYPH_STROKE_BRIGHTNESS)))
                bundle.putBoolean(KEY_GLYPH_STROKE, prefs.getBoolean(KEY_GLYPH_STROKE, SettingsDefaults.boolean(KEY_GLYPH_STROKE)))
                bundle.putInt(KEY_GLYPH_STROKE_OPACITY, prefs.getInt(KEY_GLYPH_STROKE_OPACITY, SettingsDefaults.integer(KEY_GLYPH_STROKE_OPACITY)))
                bundle.putBoolean(KEY_ICON_STROKE, prefs.getBoolean(KEY_ICON_STROKE, SettingsDefaults.boolean(KEY_ICON_STROKE)))
                bundle.putInt(KEY_STROKE_OPACITY, prefs.getInt(KEY_STROKE_OPACITY, SettingsDefaults.integer(KEY_STROKE_OPACITY)))
                bundle.putBoolean(KEY_GLYPH_SHADOW, prefs.getBoolean(KEY_GLYPH_SHADOW, SettingsDefaults.boolean(KEY_GLYPH_SHADOW)))
                bundle.putInt(KEY_SHADOW_OPACITY, prefs.getInt(KEY_SHADOW_OPACITY, SettingsDefaults.integer(KEY_SHADOW_OPACITY)))
                bundle.putInt(KEY_GRADIENT_OPACITY, prefs.getInt(KEY_GRADIENT_OPACITY, SettingsDefaults.integer(KEY_GRADIENT_OPACITY)))
                bundle.putBoolean(KEY_DESIGN_ENABLED, prefs.getBoolean(KEY_DESIGN_ENABLED, SettingsDefaults.boolean(KEY_DESIGN_ENABLED)))
                bundle.putBoolean(KEY_VOLUME_ENABLED, prefs.getBoolean(KEY_VOLUME_ENABLED, SettingsDefaults.boolean(KEY_VOLUME_ENABLED)))
            }
            METHOD_SET -> {
                enforceCaller()
                extras?.let { b ->
                    val editor = prefs.edit()
                    com.cosmod.launcherglass.util.DesktopSizing.ranges.forEach {(key,range) ->
                        if(b.containsKey(key))editor.putInt(key,b.getInt(key).coerceIn(range))
                    }
                    if(b.containsKey(KEY_GPU_EFFECTS))editor.putBoolean(KEY_GPU_EFFECTS,b.getBoolean(KEY_GPU_EFFECTS))
                    for(scope in ScopedSettingsKeys.all){
                        if(b.containsKey(scope.own))editor.putBoolean(scope.own,b.getBoolean(scope.own))
                        for(base in scope.integers){val key=scope.key(base);if(b.containsKey(key))editor.putInt(key,b.getInt(key))}
                        for(base in scope.booleans){val key=scope.key(base);if(b.containsKey(key))editor.putBoolean(key,b.getBoolean(key))}
                    }
                    if(b.containsKey(KEY_PHOTO_WIDGET_BLUR))editor.putBoolean(KEY_PHOTO_WIDGET_BLUR,b.getBoolean(KEY_PHOTO_WIDGET_BLUR))
                    if(b.containsKey(KEY_PHOTO_WIDGET_TINT))editor.putBoolean(KEY_PHOTO_WIDGET_TINT,b.getBoolean(KEY_PHOTO_WIDGET_TINT))
                    if(b.containsKey(KEY_PHOTO_WIDGET_LIQUID_WIDTH))editor.putInt(KEY_PHOTO_WIDGET_LIQUID_WIDTH,b.getInt(KEY_PHOTO_WIDGET_LIQUID_WIDTH).coerceIn(0,100))
                    if(b.containsKey(KEY_PHOTO_WIDGET_LIQUID_DISTORTION))editor.putInt(KEY_PHOTO_WIDGET_LIQUID_DISTORTION,b.getInt(KEY_PHOTO_WIDGET_LIQUID_DISTORTION).coerceIn(0,300))
                    if(b.containsKey(KEY_FOLDER_ICONS_BLUR_ENABLED))editor.putBoolean(KEY_FOLDER_ICONS_BLUR_ENABLED,b.getBoolean(KEY_FOLDER_ICONS_BLUR_ENABLED))
                    if(b.containsKey(KEY_PHOTO_WIDGET_OPACITY))editor.putInt(KEY_PHOTO_WIDGET_OPACITY,b.getInt(KEY_PHOTO_WIDGET_OPACITY).coerceIn(0,100))
                    if(b.containsKey(KEY_DEVELOPER_MODE))editor.putBoolean(KEY_DEVELOPER_MODE,b.getBoolean(KEY_DEVELOPER_MODE))
                    if(b.containsKey(KEY_COLOR_DEPTH_ENABLED))editor.putBoolean(KEY_COLOR_DEPTH_ENABLED,b.getBoolean(KEY_COLOR_DEPTH_ENABLED))
                    if(b.containsKey(KEY_COLOR_DEPTH_BITS))editor.putInt(KEY_COLOR_DEPTH_BITS,b.getInt(KEY_COLOR_DEPTH_BITS).coerceIn(8,24))
                    if(b.containsKey(KEY_COLOR_DEPTH_STAGE))editor.putInt(KEY_COLOR_DEPTH_STAGE,b.getInt(KEY_COLOR_DEPTH_STAGE).coerceIn(0,3))
                    if(b.containsKey(KEY_HAZE_ENABLED))editor.putBoolean(KEY_HAZE_ENABLED,b.getBoolean(KEY_HAZE_ENABLED))
                    if(b.containsKey(KEY_DISABLE_EDGE_OPTIMIZATION))editor.putBoolean(KEY_DISABLE_EDGE_OPTIMIZATION,b.getBoolean(KEY_DISABLE_EDGE_OPTIMIZATION))
                    if(b.containsKey(KEY_OPTIMIZE_MINI_ICONS))editor.putBoolean(KEY_OPTIMIZE_MINI_ICONS,b.getBoolean(KEY_OPTIMIZE_MINI_ICONS))
                    if(b.containsKey(KEY_DOCK_ENABLED))editor.putBoolean(KEY_DOCK_ENABLED,b.getBoolean(KEY_DOCK_ENABLED))
                    if(b.containsKey(KEY_DOCK_AUTO_LENGTH))editor.putBoolean(KEY_DOCK_AUTO_LENGTH,b.getBoolean(KEY_DOCK_AUTO_LENGTH))
                    for(key in listOf(KEY_DOCK_SIDE_PADDING,KEY_DOCK_VERTICAL_PADDING))if(b.containsKey(key))editor.putInt(key,b.getInt(key).coerceIn(0,DOCK_PADDING_MAX))
                    if(b.containsKey(KEY_DOCK_RADIUS))editor.putInt(KEY_DOCK_RADIUS,b.getInt(KEY_DOCK_RADIUS).coerceIn(0,100))
                    if(b.containsKey(KEY_ARTWORK_TRANSPARENCY))editor.putInt(KEY_ARTWORK_TRANSPARENCY,b.getInt(KEY_ARTWORK_TRANSPARENCY).coerceIn(0,100))
                    if (b.containsKey(KEY_LIGHT_HUE)) editor.putInt(KEY_LIGHT_HUE, b.getInt(KEY_LIGHT_HUE))
                    if (b.containsKey(KEY_LIGHT_SATURATION)) editor.putInt(KEY_LIGHT_SATURATION, b.getInt(KEY_LIGHT_SATURATION))
                    if (b.containsKey(KEY_LIGHT_BRIGHTNESS)) editor.putInt(KEY_LIGHT_BRIGHTNESS, b.getInt(KEY_LIGHT_BRIGHTNESS))
                    if (b.containsKey(KEY_DARK_HUE)) editor.putInt(KEY_DARK_HUE, b.getInt(KEY_DARK_HUE))
                    if (b.containsKey(KEY_DARK_SATURATION)) editor.putInt(KEY_DARK_SATURATION, b.getInt(KEY_DARK_SATURATION))
                    if (b.containsKey(KEY_DARK_BRIGHTNESS)) editor.putInt(KEY_DARK_BRIGHTNESS, b.getInt(KEY_DARK_BRIGHTNESS))
                    if (b.containsKey(KEY_ICON_APPEARANCE)) editor.putInt(KEY_ICON_APPEARANCE, b.getInt(KEY_ICON_APPEARANCE))
                    if (b.containsKey(KEY_CLEAR_BRIGHTNESS)) editor.putInt(KEY_CLEAR_BRIGHTNESS, b.getInt(KEY_CLEAR_BRIGHTNESS))
                    if (b.containsKey(KEY_COLOR_MODE)) editor.putInt(KEY_COLOR_MODE, b.getInt(KEY_COLOR_MODE))
                    if (b.containsKey(KEY_COLOR_HUE)) editor.putInt(KEY_COLOR_HUE, b.getInt(KEY_COLOR_HUE))
                    if (b.containsKey(KEY_COLOR_SATURATION)) editor.putInt(KEY_COLOR_SATURATION, b.getInt(KEY_COLOR_SATURATION))
                    if (b.containsKey(KEY_COLOR_BRIGHTNESS)) editor.putInt(KEY_COLOR_BRIGHTNESS, b.getInt(KEY_COLOR_BRIGHTNESS))
                    if (b.containsKey(KEY_ENABLED)) editor.putBoolean(KEY_ENABLED, b.getBoolean(KEY_ENABLED))
                    if(b.containsKey(KEY_BLUR_TONE_ENABLED))editor.putBoolean(KEY_BLUR_TONE_ENABLED,b.getBoolean(KEY_BLUR_TONE_ENABLED))
                    if(b.containsKey(KEY_BLUR_VIBRANCY))editor.putInt(KEY_BLUR_VIBRANCY,b.getInt(KEY_BLUR_VIBRANCY).coerceIn(0,200))
                    if(b.containsKey(KEY_LIQUID_DISTORTION))editor.putInt(KEY_LIQUID_DISTORTION,b.getInt(KEY_LIQUID_DISTORTION).coerceIn(0,300))
                    if (b.containsKey(KEY_BLUR_SATURATION)) editor.putInt(KEY_BLUR_SATURATION, b.getInt(KEY_BLUR_SATURATION))
                    if (b.containsKey(KEY_BLUR_LIGHTENING)) editor.putInt(KEY_BLUR_LIGHTENING, b.getInt(KEY_BLUR_LIGHTENING))
                    if (b.containsKey(KEY_BLUR_RADIUS_ENABLED)) editor.putBoolean(KEY_BLUR_RADIUS_ENABLED, b.getBoolean(KEY_BLUR_RADIUS_ENABLED))
                    if (b.containsKey(KEY_BLUR_LOCATIONS_ENABLED)) editor.putBoolean(KEY_BLUR_LOCATIONS_ENABLED, b.getBoolean(KEY_BLUR_LOCATIONS_ENABLED))
                    if (b.containsKey(KEY_BG_OPACITY)) editor.putInt(KEY_BG_OPACITY, b.getInt(KEY_BG_OPACITY))
                    if (b.containsKey(KEY_BLUR_ENABLED)) editor.putBoolean(KEY_BLUR_ENABLED, b.getBoolean(KEY_BLUR_ENABLED))
                    if (b.containsKey(KEY_FOLDER_BLUR_ENABLED)) editor.putBoolean(KEY_FOLDER_BLUR_ENABLED, b.getBoolean(KEY_FOLDER_BLUR_ENABLED))
                    if (b.containsKey(KEY_DRAWER_BLUR_ENABLED)) editor.putBoolean(KEY_DRAWER_BLUR_ENABLED, b.getBoolean(KEY_DRAWER_BLUR_ENABLED))
                    if (b.containsKey(KEY_BLUR_RADIUS)) editor.putInt(KEY_BLUR_RADIUS, b.getInt(KEY_BLUR_RADIUS))
                    if (b.containsKey(KEY_BLUR_MODE)) editor.putInt(KEY_BLUR_MODE, b.getInt(KEY_BLUR_MODE))
                    if (b.containsKey(KEY_BLUR_STYLE)) editor.putInt(KEY_BLUR_STYLE, b.getInt(KEY_BLUR_STYLE).coerceIn(0,1))
                    for(key in listOf(KEY_BLUR_QUALITY,KEY_LIQUID_WIDTH))if(b.containsKey(key))editor.putInt(key,b.getInt(key).coerceIn(0,100))
                    if (b.containsKey(KEY_LIVE_FPS)) editor.putInt(KEY_LIVE_FPS, b.getInt(KEY_LIVE_FPS))
                    if (b.containsKey(KEY_IDLE_PAUSE)) editor.putBoolean(KEY_IDLE_PAUSE, b.getBoolean(KEY_IDLE_PAUSE))
                    if (b.containsKey(KEY_GLYPH_STROKE_WIDTH)) editor.putInt(KEY_GLYPH_STROKE_WIDTH, b.getInt(KEY_GLYPH_STROKE_WIDTH))
                    if (b.containsKey(KEY_GLYPH_STROKE_BRIGHTNESS)) editor.putInt(KEY_GLYPH_STROKE_BRIGHTNESS, b.getInt(KEY_GLYPH_STROKE_BRIGHTNESS))
                    if (b.containsKey(KEY_GLYPH_STROKE)) editor.putBoolean(KEY_GLYPH_STROKE, b.getBoolean(KEY_GLYPH_STROKE))
                    if (b.containsKey(KEY_GLYPH_STROKE_OPACITY)) editor.putInt(KEY_GLYPH_STROKE_OPACITY, b.getInt(KEY_GLYPH_STROKE_OPACITY))
                    if (b.containsKey(KEY_ICON_STROKE)) editor.putBoolean(KEY_ICON_STROKE, b.getBoolean(KEY_ICON_STROKE))
                    if (b.containsKey(KEY_STROKE_OPACITY)) editor.putInt(KEY_STROKE_OPACITY, b.getInt(KEY_STROKE_OPACITY))
                    if (b.containsKey(KEY_GLYPH_SHADOW)) editor.putBoolean(KEY_GLYPH_SHADOW, b.getBoolean(KEY_GLYPH_SHADOW))
                    if (b.containsKey(KEY_SHADOW_OPACITY)) editor.putInt(KEY_SHADOW_OPACITY, b.getInt(KEY_SHADOW_OPACITY))
                    if (b.containsKey(KEY_GRADIENT_OPACITY)) editor.putInt(KEY_GRADIENT_OPACITY, b.getInt(KEY_GRADIENT_OPACITY))
                    if (b.containsKey(KEY_DESIGN_ENABLED)) editor.putBoolean(KEY_DESIGN_ENABLED, b.getBoolean(KEY_DESIGN_ENABLED))
                    if (b.containsKey(KEY_VOLUME_ENABLED)) editor.putBoolean(KEY_VOLUME_ENABLED, b.getBoolean(KEY_VOLUME_ENABLED))
                    editor.apply()
                    context?.contentResolver?.notifyChange(CONTENT_URI, null)
                }
            }
        }
        return bundle
    }

    private fun enforceCaller(allowLauncher: Boolean = false) {
        if (Binder.getCallingUid() == Process.myUid()) return
        if (allowLauncher && callingPackage in setOf(
                "com.android.launcher", "com.oppo.launcher", "com.oplus.launcher",
                "com.coloros.launcher", "com.android.launcher3")) return
        throw SecurityException("Only CherryPick can change settings; launchers may report icon appearance.")
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.cosmod.launcherglass.settings"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/settings")
        const val PREFS_NAME = "launcher_monet_prefs"

        const val METHOD_REPORT_ICON_APPEARANCE = "reportIconAppearance"
        const val KEY_NATIVE_ICON_DARK = "native_icon_dark"
        const val KEY_NATIVE_FLUX_SUPPORT = "native_flux_support"
        const val METHOD_GET_ALL = "getAll"
        const val METHOD_OPEN_DESKTOP = "openDesktopDesigner"
        const val METHOD_CONSUME_DESKTOP = "consumeDesktopDesigner"
        const val METHOD_SET = "set"

        const val KEY_LIGHT_HUE = "color_light_hue"
        const val KEY_LIGHT_SATURATION = "color_light_saturation"
        const val KEY_LIGHT_BRIGHTNESS = "color_light_brightness"
        const val KEY_DARK_HUE = "color_dark_hue"
        const val KEY_DARK_SATURATION = "color_dark_saturation"
        const val KEY_DARK_BRIGHTNESS = "color_dark_brightness"
        fun colorKeys(dark: Boolean): List<String> = if (dark)
            listOf(KEY_DARK_HUE, KEY_DARK_SATURATION, KEY_DARK_BRIGHTNESS)
        else listOf(KEY_LIGHT_HUE, KEY_LIGHT_SATURATION, KEY_LIGHT_BRIGHTNESS)

        const val KEY_ICON_APPEARANCE = "icon_appearance"
        const val KEY_CLEAR_BRIGHTNESS = "clear_brightness"
        const val KEY_COLOR_MODE = "color_mode"
        const val KEY_COLOR_HUE = "color_hue"
        const val KEY_COLOR_SATURATION = "color_saturation"
        const val KEY_COLOR_BRIGHTNESS = "color_brightness"

        const val KEY_BOOT_RESTART = "boot_restart"
        const val KEY_BOOT_RESTART_TOAST = "boot_restart_toast"
        const val KEY_ENABLED = "enabled"
        const val KEY_DEVELOPER_MODE = "developer_mode"
        const val KEY_GPU_EFFECTS = "gpu_effects"
        const val KEY_BLUR_TONE_ENABLED = "blur_tone_enabled"
        const val KEY_PHOTO_WIDGET_BLUR = "photo_widget_blur"
        const val KEY_COLOR_DEPTH_ENABLED = "blur_color_depth_enabled"
        const val KEY_COLOR_DEPTH_BITS = "blur_color_depth_bits"
        const val KEY_COLOR_DEPTH_STAGE = "blur_color_depth_stage"
        const val KEY_HAZE_ENABLED = "glass_haze_enabled"
        const val KEY_DISABLE_EDGE_OPTIMIZATION = "disable_edge_optimization"
        const val KEY_OPTIMIZE_MINI_ICONS = "optimize_folder_mini_icons"
        /** Glass dock behind the hotseat icons. Paddings and radius: % of the icon size. */
        const val KEY_DOCK_ENABLED = "dock_enabled"
        const val KEY_DOCK_SIDE_PADDING = "dock_side_padding"
        const val KEY_DOCK_VERTICAL_PADDING = "dock_vertical_padding"
        const val KEY_DOCK_RADIUS = "dock_radius"
        /** On: the dock fits the hotseat icons and animates its width; off: it spans the full hotseat row. */
        const val KEY_DOCK_AUTO_LENGTH = "dock_auto_length"
        const val DOCK_PADDING_MAX = 60
        /** Experimental (developer mode): transparency (0..100 %) of the tint over icons that are a full picture without a plate. */
        const val KEY_ARTWORK_TRANSPARENCY = "artwork_transparency"
        /** Local UI preference of the settings app; never sent to the launcher or exported in presets. */
        const val KEY_MD3_UI = "ui_md3"
        const val KEY_PHOTO_WIDGET_TINT = "photo_widget_tint"
        const val KEY_PHOTO_WIDGET_LIQUID_WIDTH = "photo_widget_liquid_width"
        const val KEY_PHOTO_WIDGET_LIQUID_DISTORTION = "photo_widget_liquid_distortion"
        const val KEY_FOLDER_ICONS_BLUR_ENABLED = "folder_icons_blur_enabled"
        const val KEY_PHOTO_WIDGET_OPACITY = "photo_widget_opacity"
        const val KEY_BLUR_VIBRANCY = "blur_vibrancy"
        const val KEY_BLUR_SATURATION = "blur_saturation"
        const val KEY_BLUR_LIGHTENING = "blur_lightening"
        const val KEY_BLUR_RADIUS_ENABLED = "blur_radius_enabled"
        const val KEY_BLUR_LOCATIONS_ENABLED = "blur_locations_enabled"
        const val KEY_BG_OPACITY = "bg_opacity"
        const val KEY_BLUR_ENABLED = "blur_enabled"
        const val KEY_FOLDER_BLUR_ENABLED = "folder_blur_enabled"
        const val KEY_DRAWER_BLUR_ENABLED = "drawer_blur_enabled"
        const val KEY_BLUR_RADIUS = "blur_radius"
        const val KEY_BLUR_MODE = "blur_mode"
        const val KEY_BLUR_STYLE = "blur_style"
        const val KEY_BLUR_QUALITY = "blur_quality"
        const val KEY_LIQUID_WIDTH = "liquid_width"
        const val KEY_LIQUID_DISTORTION = "liquid_distortion"
        const val BLUR_STYLE_FROSTED = 0
        const val BLUR_STYLE_LIQUID = 1
        const val KEY_LIVE_FPS = "live_blur_fps"
        const val KEY_IDLE_PAUSE = "idle_pause"
        const val KEY_GLYPH_STROKE_WIDTH = "glyph_stroke_width"
        const val KEY_GLYPH_STROKE_BRIGHTNESS = "glyph_stroke_brightness"
        const val KEY_GLYPH_STROKE = "glyph_stroke"
        const val KEY_GLYPH_STROKE_OPACITY = "glyph_stroke_opacity"
        const val KEY_ICON_STROKE = "icon_stroke"
        const val KEY_STROKE_OPACITY = "stroke_opacity"
        const val KEY_GLYPH_SHADOW = "glyph_shadow"
        const val KEY_SHADOW_OPACITY = "shadow_opacity"
        const val KEY_GRADIENT_OPACITY = "gradient_opacity"
        const val KEY_DESIGN_ENABLED = "design_enabled"
        const val KEY_VOLUME_ENABLED = "volume_enabled"

        const val BLUR_MODE_WALLPAPER = 0
        const val BLUR_MODE_LIVE = 1
    }
}
