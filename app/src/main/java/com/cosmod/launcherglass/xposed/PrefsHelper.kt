package com.cosmod.launcherglass.xposed

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.cosmod.launcherglass.ui.SettingsDefaults
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.LiveBlurEngine
import com.cosmod.launcherglass.util.WallpaperHelper

object PrefsHelper {

    @Volatile var enabled: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_ENABLED)
    @Volatile var gpuEffects: Boolean = true
        private set
    @Volatile var bgOpacity: Int = SettingsDefaults.integer(SettingsProvider.KEY_BG_OPACITY)
    @Volatile var blurEnabled: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_BLUR_ENABLED)
    @Volatile var colorDepthBits=24
        private set
    @Volatile var colorDepthStage=ColorDepthEffect.FINISHED_PLATE
        private set
    @Volatile var hazeEnabled=false
        private set
    @Volatile var disableEdgeOptimization=SettingsDefaults.boolean(SettingsProvider.KEY_DISABLE_EDGE_OPTIMIZATION)
        private set
    @Volatile var optimizeMiniIcons=SettingsDefaults.boolean(SettingsProvider.KEY_OPTIMIZE_MINI_ICONS)
        private set
    @Volatile var dockEnabled=false
        private set
    @Volatile var dockSidePadding=SettingsDefaults.integer(SettingsProvider.KEY_DOCK_SIDE_PADDING)
        private set
    @Volatile var dockVerticalPadding=SettingsDefaults.integer(SettingsProvider.KEY_DOCK_VERTICAL_PADDING)
        private set
    @Volatile var dockRadius=SettingsDefaults.integer(SettingsProvider.KEY_DOCK_RADIUS)
        private set
    @Volatile var dockAutoLength=SettingsDefaults.boolean(SettingsProvider.KEY_DOCK_AUTO_LENGTH)
        private set
    /** Transparency (0..100 %) of the tinted picture of plate-less icons; adjustable in developer mode. */
    @Volatile var artworkTransparency=SettingsDefaults.integer(SettingsProvider.KEY_ARTWORK_TRANSPARENCY)
    @Volatile var artworkDetection=false
        private set
    @Volatile var folderBlurEnabled: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_FOLDER_BLUR_ENABLED)
    @Volatile var folderIconsBlurEnabled=true
    @Volatile var photoWidgetBlur=false
    @Volatile var photoWidgetTint=false
    @Volatile var photoWidgetLiquidWidth=40
    @Volatile var photoWidgetLiquidDistortion=50
    @Volatile var photoWidgetOpacity=55
        private set
    @Volatile var drawerBlurEnabled: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_DRAWER_BLUR_ENABLED)
    @Volatile var blurRadius: Int = SettingsDefaults.integer(SettingsProvider.KEY_BLUR_RADIUS)
    @Volatile var blurMode: Int = SettingsDefaults.integer(SettingsProvider.KEY_BLUR_MODE)
    @Volatile var blurStyle: Int = SettingsDefaults.integer(SettingsProvider.KEY_BLUR_STYLE)
    @Volatile var blurQuality=SettingsDefaults.integer(SettingsProvider.KEY_BLUR_QUALITY)
    @Volatile var liquidWidth=SettingsDefaults.integer(SettingsProvider.KEY_LIQUID_WIDTH)
    @Volatile var liquidDistortion=SettingsDefaults.integer(SettingsProvider.KEY_LIQUID_DISTORTION)
    val liquidGlass:Boolean get()=blurStyle==SettingsProvider.BLUR_STYLE_LIQUID && android.os.Build.VERSION.SDK_INT>=33
    @Volatile var liveFps: Int = SettingsDefaults.integer(SettingsProvider.KEY_LIVE_FPS)
    @Volatile var idlePause: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_IDLE_PAUSE)
    @Volatile var iconStroke: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_ICON_STROKE)
    @Volatile var strokeOpacity: Int = SettingsDefaults.integer(SettingsProvider.KEY_STROKE_OPACITY)
    @Volatile var glyphStroke = false
    @Volatile var glyphStrokeOpacity = 40
    @Volatile var glyphStrokeWidth = 35
    @Volatile var glyphStrokeBrightness = 45
    @Volatile var glyphShadow: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_GLYPH_SHADOW)
    @Volatile var shadowOpacity: Int = SettingsDefaults.integer(SettingsProvider.KEY_SHADOW_OPACITY)
    @Volatile var gradientOpacity: Int = SettingsDefaults.integer(SettingsProvider.KEY_GRADIENT_OPACITY)

    @Volatile var iconPalette = com.cosmod.launcherglass.util.IconPalette.from(0, 0, 0, 0)

    @Volatile var blurVibrancy = 0
        private set
    private var blurSaturation = 100
    internal fun plateColor(color:Int=iconPalette.background,saturation:Int=blurSaturation)=com.cosmod.launcherglass.util.PlateTone.saturation(color,saturation)
    @Volatile var blurLightening = 50
        private set
    @Volatile var blurColorFilter: android.graphics.ColorMatrixColorFilter? = null
    private var initialized = false
    @Volatile private var loaded=false

    internal fun backdropFilter(vibrancy:Int,palette:com.cosmod.launcherglass.util.IconPalette):android.graphics.ColorMatrixColorFilter? {
        val colorize=palette.custom && !palette.clear
        return if (!colorize || vibrancy>=100) null else {
                    val matrix = android.graphics.ColorMatrix()
                    if (colorize) {
                        // Replace source chroma instead of tinting a colored
                        // wallpaper. Plate saturation changes the overlay color
                        // separately, independently of this chroma filter.
                        matrix.setSaturation(0f)
                        val hue = android.graphics.Color.HSVToColor(floatArrayOf(palette.tintHue.toFloat(),1f,1f))
                        val strength = palette.tintSaturation / 100f
                        val tint = android.graphics.ColorMatrix().apply { setScale(
                            1f-strength+strength*android.graphics.Color.red(hue)/255f,
                            1f-strength+strength*android.graphics.Color.green(hue)/255f,
                            1f-strength+strength*android.graphics.Color.blue(hue)/255f,1f) }
                        matrix.postConcat(tint)
                    }
                    // Vibrancy restores source chroma that a custom tint may
                    // otherwise replace. Blend the complete legacy transform
                    // toward identity, preserving its old behavior at zero.
                    val keep=(vibrancy/100f).coerceIn(0f,1f)
                    if(keep>0f){
                        val identity=android.graphics.ColorMatrix().array
                        val values=matrix.array
                        for(i in values.indices)values[i]=values[i]*(1f-keep)+identity[i]*keep
                        matrix.set(values)
                    }
                    android.graphics.ColorMatrixColorFilter(matrix)
                }
    }
    fun init(context: Context) {
        if (initialized) return
        initialized = true

        loadPrefs(context)
        for(delay in listOf(250L,750L,1500L,3000L))Handler(Looper.getMainLooper()).postDelayed({if(!loaded)loadPrefs(context)},delay)
        context.registerComponentCallbacks(object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(configuration: android.content.res.Configuration) {
                val dark = configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                loadPrefs(context, dark)
            }
            override fun onLowMemory() {}
        })

        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    loadPrefs(context)
                }
            }
            context.contentResolver.registerContentObserver(SettingsProvider.CONTENT_URI, true, observer)
            GlassLogger.i(
                "PrefsHelper",
                "Initialized & ContentObserver registered: bgOpacity=$bgOpacity%, blur=$blurEnabled(mode=$blurMode, fps=$liveFps, idlePause=$idlePause, r=$blurRadius)"
            )
        } catch (t: Throwable) {
            GlassLogger.e("PrefsHelper", "Failed to register ContentObserver", t)
        }
    }

    fun loadPrefs(context: Context, systemDark: Boolean? = null) {
        try {
            val bundle = context.contentResolver.call(
                SettingsProvider.CONTENT_URI,
                SettingsProvider.METHOD_GET_ALL,
                null,
                null
            ) ?: return

            val newEnabled = bundle.getBoolean(SettingsProvider.KEY_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_ENABLED))
            val toneEnabled=bundle.getBoolean(SettingsProvider.KEY_BLUR_TONE_ENABLED,true)
            val newBgOpacity = if (toneEnabled)
                bundle.getInt(SettingsProvider.KEY_BG_OPACITY, SettingsDefaults.integer(SettingsProvider.KEY_BG_OPACITY)).coerceIn(0,100) else 0
            val newBlurEnabled = bundle.getBoolean(SettingsProvider.KEY_BLUR_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_BLUR_ENABLED))
            val locationBlurEnabled = bundle.getBoolean(SettingsProvider.KEY_BLUR_LOCATIONS_ENABLED,true)
            val newFolderBlurEnabled = bundle.getBoolean(SettingsProvider.KEY_FOLDER_BLUR_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_FOLDER_BLUR_ENABLED))
            val newFolderIconsBlurEnabled=locationBlurEnabled && bundle.getBoolean(SettingsProvider.KEY_FOLDER_ICONS_BLUR_ENABLED,true)
            val newDrawerBlurEnabled = locationBlurEnabled && bundle.getBoolean(SettingsProvider.KEY_DRAWER_BLUR_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_DRAWER_BLUR_ENABLED))
            val newPhotoWidgetBlur=bundle.getBoolean(SettingsProvider.KEY_PHOTO_WIDGET_BLUR,false)
            val newPhotoWidgetOpacity=bundle.getInt(SettingsProvider.KEY_PHOTO_WIDGET_OPACITY,55).coerceIn(0,100)
            val newPhotoWidgetTint=bundle.getBoolean(SettingsProvider.KEY_PHOTO_WIDGET_TINT,false)
            val developer=bundle.getBoolean(SettingsProvider.KEY_DEVELOPER_MODE,false)
            val newGpuEffects=!developer || bundle.getBoolean(SettingsProvider.KEY_GPU_EFFECTS,true)
            val backendChanged=newGpuEffects!=gpuEffects
            val newColorDepth=if(developer && android.os.Build.VERSION.SDK_INT>=33 && bundle.getBoolean(SettingsProvider.KEY_COLOR_DEPTH_ENABLED,false))bundle.getInt(SettingsProvider.KEY_COLOR_DEPTH_BITS,8).coerceIn(8,24)else 24
            val newDepthStage=bundle.getInt(SettingsProvider.KEY_COLOR_DEPTH_STAGE,ColorDepthEffect.FINISHED_PLATE).coerceIn(0,3)
            val newHaze=developer && android.os.Build.VERSION.SDK_INT>=33 && bundle.getBoolean(SettingsProvider.KEY_HAZE_ENABLED,false)
            val depthChanged=newColorDepth!=colorDepthBits || newDepthStage!=colorDepthStage
            val processingDepthChanged=depthChanged && ((colorDepthBits<24 && colorDepthStage<=ColorDepthEffect.AFTER_BLUR) ||
                (newColorDepth<24 && newDepthStage<=ColorDepthEffect.AFTER_BLUR))
            val hazeChanged=newHaze!=hazeEnabled
            val newDisableEdgeOptimization=bundle.getBoolean(SettingsProvider.KEY_DISABLE_EDGE_OPTIMIZATION,SettingsDefaults.boolean(SettingsProvider.KEY_DISABLE_EDGE_OPTIMIZATION))
            val newOptimizeMiniIcons=bundle.getBoolean(SettingsProvider.KEY_OPTIMIZE_MINI_ICONS,SettingsDefaults.boolean(SettingsProvider.KEY_OPTIMIZE_MINI_ICONS))
            val defaultArtwork=SettingsDefaults.integer(SettingsProvider.KEY_ARTWORK_TRANSPARENCY)
            val newArtworkTransparency=if(developer)bundle.getInt(SettingsProvider.KEY_ARTWORK_TRANSPARENCY,defaultArtwork).coerceIn(0,100) else defaultArtwork
            val artworkChanged=newArtworkTransparency!=artworkTransparency || developer!=artworkDetection
            if(developer!=artworkDetection)IconBitmapProcessor.clearCache()
            val newDockEnabled=bundle.getBoolean(SettingsProvider.KEY_DOCK_ENABLED,false)
            val wasDockEnabled=dockEnabled
            fun dockValue(key:String,max:Int)=bundle.getInt(key,SettingsDefaults.integer(key)).coerceIn(0,max)
            val newDockSide=dockValue(SettingsProvider.KEY_DOCK_SIDE_PADDING,SettingsProvider.DOCK_PADDING_MAX)
            val newDockVertical=dockValue(SettingsProvider.KEY_DOCK_VERTICAL_PADDING,SettingsProvider.DOCK_PADDING_MAX)
            val newDockRadius=dockValue(SettingsProvider.KEY_DOCK_RADIUS,100)
            val newDockAutoLength=bundle.getBoolean(SettingsProvider.KEY_DOCK_AUTO_LENGTH,SettingsDefaults.boolean(SettingsProvider.KEY_DOCK_AUTO_LENGTH))
            val dockChanged=newDockEnabled!=dockEnabled || newDockSide!=dockSidePadding || newDockVertical!=dockVerticalPadding || newDockRadius!=dockRadius ||
                newDockAutoLength!=dockAutoLength
            val newPhotoWidgetWidth=bundle.getInt(SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_WIDTH,SettingsDefaults.integer(SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_WIDTH)).coerceIn(0,100)
            val newPhotoWidgetDistortion=bundle.getInt(SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_DISTORTION,50).coerceIn(0,300)
            val widgetChanged=newPhotoWidgetBlur!=photoWidgetBlur || newPhotoWidgetOpacity!=photoWidgetOpacity || newPhotoWidgetTint!=photoWidgetTint ||
                newPhotoWidgetWidth!=photoWidgetLiquidWidth || newPhotoWidgetDistortion!=photoWidgetLiquidDistortion
            val newBlurRadius = if (bundle.getBoolean(SettingsProvider.KEY_BLUR_RADIUS_ENABLED,true))
                bundle.getInt(SettingsProvider.KEY_BLUR_RADIUS, SettingsDefaults.integer(SettingsProvider.KEY_BLUR_RADIUS)).coerceIn(0,50) else 0
            val newBlurMode = bundle.getInt(SettingsProvider.KEY_BLUR_MODE, SettingsDefaults.integer(SettingsProvider.KEY_BLUR_MODE))
            val newBlurStyle = bundle.getInt(SettingsProvider.KEY_BLUR_STYLE, SettingsDefaults.integer(SettingsProvider.KEY_BLUR_STYLE)).coerceIn(0,1)
            val blurStyleChanged=newBlurStyle!=blurStyle
            val newBlurQuality=if(developer)bundle.getInt(SettingsProvider.KEY_BLUR_QUALITY,SettingsDefaults.integer(SettingsProvider.KEY_BLUR_QUALITY)).coerceIn(0,100)else 0
            val newLiquidWidth=bundle.getInt(SettingsProvider.KEY_LIQUID_WIDTH,SettingsDefaults.integer(SettingsProvider.KEY_LIQUID_WIDTH)).coerceIn(0,100)
            val newLiquidDistortion=bundle.getInt(SettingsProvider.KEY_LIQUID_DISTORTION,SettingsDefaults.integer(SettingsProvider.KEY_LIQUID_DISTORTION)).coerceIn(0,300)
            val geometryChanged=newLiquidWidth!=liquidWidth || newLiquidDistortion!=liquidDistortion
            val qualityChanged=newBlurQuality!=blurQuality
            val newLiveFps = bundle.getInt(SettingsProvider.KEY_LIVE_FPS, SettingsDefaults.integer(SettingsProvider.KEY_LIVE_FPS))
            val newIdlePause = bundle.getBoolean(SettingsProvider.KEY_IDLE_PAUSE, SettingsDefaults.boolean(SettingsProvider.KEY_IDLE_PAUSE))
            val designEnabled = bundle.getBoolean(SettingsProvider.KEY_DESIGN_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_DESIGN_ENABLED))
            val newIconStroke = designEnabled && bundle.getBoolean(SettingsProvider.KEY_ICON_STROKE, SettingsDefaults.boolean(SettingsProvider.KEY_ICON_STROKE))
            val newStrokeOpacity = bundle.getInt(SettingsProvider.KEY_STROKE_OPACITY, SettingsDefaults.integer(SettingsProvider.KEY_STROKE_OPACITY))
            val newGlyphStroke = designEnabled && bundle.getBoolean(SettingsProvider.KEY_GLYPH_STROKE, false)
            val newGlyphStrokeOpacity = bundle.getInt(SettingsProvider.KEY_GLYPH_STROKE_OPACITY, 40).coerceIn(0,100)
            val newGlyphStrokeWidth = bundle.getInt(SettingsProvider.KEY_GLYPH_STROKE_WIDTH,35).coerceIn(0,100)
            val newGlyphStrokeBrightness = bundle.getInt(SettingsProvider.KEY_GLYPH_STROKE_BRIGHTNESS,45).coerceIn(0,100)
            val glyphOutlineChanged = newGlyphStroke != glyphStroke || newGlyphStrokeOpacity != glyphStrokeOpacity ||
                newGlyphStrokeWidth != glyphStrokeWidth || newGlyphStrokeBrightness != glyphStrokeBrightness
            val newGlyphShadow = designEnabled && bundle.getBoolean(SettingsProvider.KEY_GLYPH_SHADOW, SettingsDefaults.boolean(SettingsProvider.KEY_GLYPH_SHADOW))
            val newShadowOpacity = bundle.getInt(SettingsProvider.KEY_SHADOW_OPACITY, SettingsDefaults.integer(SettingsProvider.KEY_SHADOW_OPACITY))
            val newGradientOpacity = if (designEnabled && bundle.getBoolean(SettingsProvider.KEY_VOLUME_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_VOLUME_ENABLED)))
                bundle.getInt(SettingsProvider.KEY_GRADIENT_OPACITY, SettingsDefaults.integer(SettingsProvider.KEY_GRADIENT_OPACITY)) else 0
            val configuration = android.content.res.Configuration(context.resources.configuration)
            if (systemDark != null) {
                configuration.uiMode = (configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (systemDark) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
            }
            val darkIcons = NativeIconAppearance.read(context, configuration)
                ?: (systemDark ?: com.cosmod.launcherglass.util.IconPalette.isSystemDark(context))
            val fluxSupport=FluxIconSupport.read(context)
            if(fluxSupport!=FluxIconSupport.UNKNOWN && bundle.getInt(SettingsProvider.KEY_NATIVE_FLUX_SUPPORT,0)!=fluxSupport) {
                context.contentResolver.call(SettingsProvider.CONTENT_URI,SettingsProvider.METHOD_REPORT_ICON_APPEARANCE,
                    null,android.os.Bundle().apply {putInt(SettingsProvider.KEY_NATIVE_FLUX_SUPPORT,fluxSupport)})
            }
            if (!bundle.containsKey(SettingsProvider.KEY_NATIVE_ICON_DARK) ||
                bundle.getBoolean(SettingsProvider.KEY_NATIVE_ICON_DARK) != darkIcons) {
                context.contentResolver.call(SettingsProvider.CONTENT_URI, SettingsProvider.METHOD_REPORT_ICON_APPEARANCE,
                    null, android.os.Bundle().apply { putBoolean(SettingsProvider.KEY_NATIVE_ICON_DARK, darkIcons) })
            }
            val clearIcons = bundle.getInt(SettingsProvider.KEY_ICON_APPEARANCE, 1) == 0
            val colorKeys = SettingsProvider.colorKeys(darkIcons)
            val newPalette = com.cosmod.launcherglass.util.IconPalette.from(
                bundle.getInt(SettingsProvider.KEY_COLOR_MODE, 0),
                bundle.getInt(colorKeys[0], SettingsDefaults.integer(colorKeys[0])),
                bundle.getInt(colorKeys[1], SettingsDefaults.integer(colorKeys[1])),
                if (clearIcons) bundle.getInt(SettingsProvider.KEY_CLEAR_BRIGHTNESS, SettingsDefaults.integer(SettingsProvider.KEY_CLEAR_BRIGHTNESS))
                else bundle.getInt(colorKeys[2], SettingsDefaults.integer(colorKeys[2])), darkIcons, clearIcons)
            val newBlurVibrancy=if(toneEnabled)bundle.getInt(SettingsProvider.KEY_BLUR_VIBRANCY,0).coerceIn(0,200)else 0
            val vibrancyChanged=newBlurVibrancy!=blurVibrancy
            val newBlurSaturation = if (toneEnabled)
                bundle.getInt(SettingsProvider.KEY_BLUR_SATURATION,100).coerceIn(0,200) else 100
            val newBlurLightening = if (toneEnabled)
                bundle.getInt(SettingsProvider.KEY_BLUR_LIGHTENING,50).coerceIn(0,100) else 50
            val shadowToneChanged = newBlurLightening != blurLightening
            val blurColorsChanged = newBlurSaturation != blurSaturation || newBlurLightening != blurLightening
            val paletteChanged = newPalette != iconPalette
            val masterChanged = newEnabled != enabled

            if (newBgOpacity != bgOpacity || newEnabled != enabled) {
                IconBitmapProcessor.clearCache()
                com.cosmod.launcherglass.util.GlyphShadowHelper.clearCache()
            }
            if (newBlurRadius != blurRadius) {
                WallpaperHelper.invalidate()
                LiveBlurEngine.invalidate()
            }
            if (newBlurMode != blurMode) {
                LiveBlurEngine.invalidate()
            }
            val folderEnabledChanged=newFolderBlurEnabled!=folderBlurEnabled
            val materialChanged=backendChanged || newBgOpacity!=bgOpacity || newBlurEnabled!=blurEnabled || newBlurRadius!=blurRadius ||
                newFolderBlurEnabled!=folderBlurEnabled || newFolderIconsBlurEnabled!=folderIconsBlurEnabled || newDrawerBlurEnabled!=drawerBlurEnabled ||
                newIconStroke != iconStroke || newStrokeOpacity != strokeOpacity ||
                newGlyphShadow != glyphShadow || newShadowOpacity != shadowOpacity ||
                newGradientOpacity != gradientOpacity || glyphOutlineChanged || paletteChanged ||
                blurColorsChanged || blurStyleChanged || geometryChanged || vibrancyChanged || widgetChanged || depthChanged || hazeChanged || newDisableEdgeOptimization!=disableEdgeOptimization || newOptimizeMiniIcons!=optimizeMiniIcons || artworkChanged

            if (blurColorsChanged || paletteChanged || vibrancyChanged) {
                blurSaturation = newBlurSaturation
                blurLightening = newBlurLightening
                blurColorFilter=backdropFilter(newBlurVibrancy,newPalette)
            }
            iconPalette = newPalette
            enabled = newEnabled
            gpuEffects=newGpuEffects
            bgOpacity = newBgOpacity
            blurEnabled = newBlurEnabled
            colorDepthBits=newColorDepth
            colorDepthStage=newDepthStage;hazeEnabled=newHaze
            disableEdgeOptimization=newDisableEdgeOptimization
            optimizeMiniIcons=newOptimizeMiniIcons
            artworkTransparency=newArtworkTransparency;artworkDetection=developer
            dockEnabled=newDockEnabled;dockSidePadding=newDockSide;dockVerticalPadding=newDockVertical;dockRadius=newDockRadius
            dockAutoLength=newDockAutoLength
            folderBlurEnabled = newFolderBlurEnabled
            folderIconsBlurEnabled=newFolderIconsBlurEnabled
            drawerBlurEnabled = newDrawerBlurEnabled
            photoWidgetBlur=newPhotoWidgetBlur;photoWidgetOpacity=newPhotoWidgetOpacity;photoWidgetTint=newPhotoWidgetTint
            photoWidgetLiquidWidth=newPhotoWidgetWidth;photoWidgetLiquidDistortion=newPhotoWidgetDistortion
            blurRadius = newBlurRadius
            blurMode = newBlurMode
            blurStyle = newBlurStyle
            blurVibrancy=newBlurVibrancy
            blurQuality=newBlurQuality;liquidWidth=newLiquidWidth;liquidDistortion=newLiquidDistortion
            if(blurStyleChanged || qualityChanged || vibrancyChanged || processingDepthChanged || backendChanged)WallpaperHelper.invalidate()
            liveFps = newLiveFps
            idlePause = newIdlePause
            iconStroke = newIconStroke
            strokeOpacity = newStrokeOpacity
            glyphStroke = newGlyphStroke
            glyphStrokeOpacity = newGlyphStrokeOpacity
            glyphStrokeWidth = newGlyphStrokeWidth
            glyphStrokeBrightness = newGlyphStrokeBrightness
            glyphShadow = newGlyphShadow
            shadowOpacity = newShadowOpacity
            gradientOpacity = newGradientOpacity
            val folderChanged=FolderSettings.update(bundle)
            if(folderChanged || (FolderSettings.own && folderEnabledChanged))WallpaperHelper.invalidate()
            val dockSettingsChanged=DockSettings.update(bundle)
            if(dockSettingsChanged || (DockSettings.own && newDockEnabled!=wasDockEnabled))WallpaperHelper.invalidate()
            WallpaperHelper.onProcessingSettingsChanged()
            if(shadowToneChanged) WallpaperHelper.invalidate()

            // Publish all new values before invalidating recorded materials.
            // In particular, a contour toggle must rebuild the overlay itself,
            // not only update the wallpaper sampling RenderNode.
            if (materialChanged || folderChanged) {
                IconGlassHook.invalidateShaders()
                IconGlassHook.invalidateFolderPreviews()
                IconGlassHook.invalidateActiveIcons()
            }
            if (paletteChanged || artworkChanged) {
                DynamicIconForeground.clear()
                IconBitmapProcessor.clearTintCache()
                IconMaterialRenderer.clear()
                IconGlassHook.invalidateFolderPreviews()
                IconGlassHook.invalidateActiveIcons()
            }
            if (masterChanged || backendChanged) {
                IconGlassHook.onIconThemeChanged()
                IconGlassHook.reloadNativeIcons()
            }

            if(enabled){
                ModuleHooks.setEnabled(true)
                com.cosmod.launcherglass.util.LauncherWallpaperCapture.onModuleEnabled()
            }
            LiveBlurEngine.onPrefsChanged()
            NativeSurfaceLive.onSettingsChanged()
            FolderMaterialHook.onSettingsChanged()
            LiftedBackdrop.onSettingsChanged()
            MultiIconMaterialHook.onSettingsChanged()
            PhotoWidgetMaterial.onSettingsChanged()
            if(dockChanged || dockSettingsChanged || materialChanged || masterChanged)DockMaterial.onSettingsChanged()
            if(!enabled){
                IconGlassHook.onModuleDisabled()
                com.cosmod.launcherglass.util.LauncherWallpaperCapture.onModuleDisabled()
                ModuleHooks.setEnabled(false)
            }
            loaded=true

            GlassLogger.i(
                "PrefsHelper",
                "Preferences loaded: enabled=$enabled, bgOpacity=$bgOpacity%, blur=$blurEnabled(mode=$blurMode, fps=$liveFps, idlePause=$idlePause, r=$blurRadius), stroke=$iconStroke($strokeOpacity%), glyphShadow=$glyphShadow($shadowOpacity%), gradient=$gradientOpacity%, palette=$iconPalette"
            )
        } catch (t: Throwable) {
            GlassLogger.e("PrefsHelper", "Failed to load preferences", t)
        }
    }
}
