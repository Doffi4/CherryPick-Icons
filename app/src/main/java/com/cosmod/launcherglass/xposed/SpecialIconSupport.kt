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
    private val clocks=setOf("com.coloros.alarmclock","com.oplus.alarmclock","com.oneplus.deskclock","com.google.android.deskclock","com.android.deskclock","com.android.alarmclock")
    private val calendars=setOf("com.coloros.calendar","com.oplus.calendar","com.android.calendar","com.google.android.calendar")
    private val packages=WeakHashMap<Drawable,String>()
    private val methods=HashSet<Method>()
    private val failures=HashSet<String>()
    private val components=WeakHashMap<Any,String>()
    private val viewLogs=HashSet<String>()
    private var plate:Bitmap?=null
    fun packageOf(drawable:Drawable)=packages[drawable]
    fun dynamic(drawable:Drawable)=packages[drawable] in clocks || packages[drawable] in calendars
    fun isContainer(drawable:Drawable)=drawable.javaClass.name.let {
        it=="com.android.launcher3.folder.FolderPreviewDrawable" || it=="com.android.launcher3.folder.big.stacked.StackedDrawable"
    }
    fun tick(drawable:Drawable):Long?=when(packages[drawable]){
        in clocks -> System.currentTimeMillis()/1000L
        in calendars -> System.currentTimeMillis()/60_000L
        else -> null
    }
    fun rememberMask(mask:Bitmap){plate=mask}
    private fun component(item:Any):String {
        components[item]?.let {return it}
        val pkg=(NativeIconAccess.call(item,"getTargetComponent") as? ComponentName)?.packageName ?: ""
        // A workspace item can be drawn before asynchronous binding fills its intent.
        if(pkg.isNotEmpty())components[item]=pkg
        return pkg
    }
    fun bind(view:View,isRecording:()->Boolean,render:(Drawable,Canvas)->Boolean){
        val tag=view.tag ?: return
        val pkg=component(tag)
        if(view.width>view.height && viewLogs.add("${tag.javaClass.name}:$pkg:${view.width}x${view.height}")) {
            val icon=NativeIconAccess.call(view,"getIcon") as? Drawable
            val fancy=NativeIconAccess.call(view,"getOriginFancyDrawable") as? Drawable
            GlassLogger.i("SpecialIcons","Wide host: tag=${tag.javaClass.name}, package=$pkg, icon=${icon?.javaClass?.name}/${icon?.bounds}, fancy=${fancy?.javaClass?.name}/${fancy?.bounds}")
        }
        if(pkg !in clocks && pkg !in calendars && pkg!="com.geteit.andwobble")return
        val drawables=LinkedHashSet<Drawable>()
        for(name in listOf("getIcon","getOriginFancyDrawable")) {
            (NativeIconAccess.call(view,name) as? Drawable)?.let {drawables.add(it)}
        }
        (NativeIconAccess.field(view,"mLayerDrawable") as? Drawable)?.let {drawables.add(it)}
        if(view is android.widget.TextView)view.compoundDrawables.filterNotNull().forEach {drawables.add(it)}
        for(drawable in drawables)bind(drawable,tag,isRecording,render)
    }
    /** Folder previews have item metadata, but no BubbleTextView to bind from. */
    fun bind(drawable:Drawable,item:Any,isRecording:()->Boolean,render:(Drawable,Canvas)->Boolean):Boolean {
        // A stacked group may inherit its first child's tag. Its native animator
        // must continue drawing individual children, including previous/current lists.
        if(isContainer(drawable)) {packages.remove(drawable);return false}
        val pkg=component(item)
        if(pkg !in clocks && pkg !in calendars && pkg!="com.geteit.andwobble")return false
        if(packages[drawable]==pkg)return true
        packages[drawable]=pkg
        val method=drawable.javaClass.getMethod("draw",Canvas::class.java)
        if(!methods.add(method))return true
        GlassLogger.i("SpecialIcons","Native override: $pkg -> ${drawable.javaClass.name}, draw=${method.declaringClass.name}")
        ModuleHooks.hookMethod(method,object:XC_MethodHook(PRIORITY_HIGHEST){
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
