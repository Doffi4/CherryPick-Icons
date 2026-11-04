package com.cosmod.launcherglass.xposed

import android.content.ComponentName
import android.graphics.*
import android.graphics.drawable.*
import android.view.View
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.*
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Discover the actual native draw override, including OEM dynamic drawables. */
internal object SpecialIconSupport {
    private val clocks=setOf("com.coloros.alarmclock","com.android.deskclock","com.android.alarmclock")
    private val calendars=setOf("com.coloros.calendar","com.android.calendar","com.google.android.calendar")
    private val packages=WeakHashMap<Drawable,String>()
    private val methods=HashSet<Method>()
    private val failures=HashSet<String>()
    private val components=WeakHashMap<Any,String>()
    private var plate:Bitmap?=null
    fun packageOf(drawable:Drawable)=packages[drawable]
    fun tick(drawable:Drawable):Long?=when(packages[drawable]){
        in clocks -> System.currentTimeMillis()/1000L
        in calendars -> System.currentTimeMillis()/60_000L
        else -> null
    }
    fun rememberMask(mask:Bitmap){plate=mask}
    private fun component(item:Any):String {
        components[item]?.let {return it}
        val pkg=try {(XposedHelpers.callMethod(item,"getTargetComponent") as? ComponentName)?.packageName ?: ""}catch(_:Throwable){""}
        if(pkg.isNotEmpty())components[item]=pkg
        return pkg
    }
    fun bind(view:View,isRecording:()->Boolean,render:(Drawable,Canvas)->Boolean){
        val tag=view.tag ?: return
        val pkg=component(tag)
        if(pkg !in clocks && pkg !in calendars && pkg!="com.geteit.andwobble")return
        val drawables=LinkedHashSet<Drawable>()
        for(name in listOf("getIcon","getIconDisplay","getOriginFancyDrawable"))try {
            (XposedHelpers.callMethod(view,name) as? Drawable)?.let {drawables.add(it)}
        }catch(_:Throwable){}
        try {(XposedHelpers.getObjectField(view,"mLayerDrawable") as? Drawable)?.let {drawables.add(it)}}catch(_:Throwable){}
        for(drawable in drawables)bind(drawable,tag,isRecording,render)
    }
    /** Folder previews have item metadata, but no BubbleTextView to bind from. */
    fun bind(drawable:Drawable,item:Any,isRecording:()->Boolean,render:(Drawable,Canvas)->Boolean):Boolean {
        val pkg=component(item)
        if(pkg !in clocks && pkg !in calendars && pkg!="com.geteit.andwobble")return false
        packages[drawable]=pkg
        val method=drawable.javaClass.getMethod("draw",Canvas::class.java)
        if(!methods.add(method))return true
        GlassLogger.i("SpecialIcons","Native override: $pkg -> ${drawable.javaClass.name}, draw=${method.declaringClass.name}")
        XposedBridge.hookMethod(method,object:XC_MethodHook(PRIORITY_HIGHEST){
            override fun beforeHookedMethod(param:MethodHookParam){
                if(!PrefsHelper.enabled || isRecording())return
                val icon=param.thisObject as? Drawable ?: return
                if(!packages.containsKey(icon))return
                val canvas=param.args.firstOrNull() as? Canvas ?: return
                try {if(render(icon,canvas))param.result=null}
                catch(t:Throwable){if(failures.add(icon.javaClass.name+":"+t.message))GlassLogger.e("SpecialIcons","Native material failed",t)}
            }
        })
        return true
    }
    fun legacyLayers(bitmap:Bitmap):ProcessedIcon {
        val size=bitmap.width
        val mask=plate?.takeUnless {it.isRecycled} ?: Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888).also {output ->
            val native=AdaptiveIconDrawable(ColorDrawable(Color.WHITE),ColorDrawable(Color.TRANSPARENT))
            native.setBounds(0,0,size,size)
            Canvas(output).drawPath(native.iconMask,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE})
        }
        val glyph=Bitmap.createBitmap(mask.width,mask.height,Bitmap.Config.ARGB_8888)
        val w=mask.width*.70f;val h=w*bitmap.height/bitmap.width
        val rect=RectF((mask.width-w)/2,(mask.height-h)/2,(mask.width+w)/2,(mask.height+h)/2)
        // A photographic/legacy plate is artwork, not an extractable mono glyph.
        // Keep its details above the material instead of tinting it into a slab.
        val target=Canvas(glyph)
        target.drawBitmap(com.cosmod.launcherglass.util.WallpaperHelper.forCanvas(bitmap,target),null,rect,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {alpha=150})
        return ProcessedIcon(glyph,mask,PrefsHelper.iconPalette.background)
    }
}
