package com.cosmod.launcherglass.util

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CompletableFuture

/** Resolve verified OEM contracts; ambiguous shapes keep the native fallback. */
internal object LauncherMethodResolver {
    data class WallpaperFactory(val subscribe:Method,val frame:Method,val release:Method)

    fun wallpaperFactory(type:Class<*>,bitmap:Class<*>):WallpaperFactory? {
        val listeners=type.declaredMethods.filter {method ->
            !Modifier.isStatic(method.modifiers) && method.returnType==Void.TYPE &&
                method.parameterCount==1 && method.parameterTypes[0].isInterface &&
                method.parameterTypes[0].methods.any {frame -> frame.returnType==Void.TYPE && frame.parameterTypes.any {it==bitmap}}
        }
        val named=listeners.filter {it.name.startsWith("add") && it.name.endsWith("Listener")}
        val subscribe=if(named.isNotEmpty())named.singleOrNull() else listeners.singleOrNull {method ->
            method.name=="a" && listeners.any {it.name=="c" && it.parameterTypes.contentEquals(method.parameterTypes)}
        }
        subscribe ?: return null
        val frame=subscribe.parameterTypes[0].methods.singleOrNull {it.returnType==Void.TYPE && it.parameterTypes.any {arg -> arg==bitmap}} ?: return null
        val release=type.declaredMethods.singleOrNull {method ->
            !Modifier.isStatic(method.modifiers) && method.returnType==Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) &&
                method.name==(if(subscribe.name=="a")"b" else "release")
        } ?: return null
        return WallpaperFactory(subscribe,frame,release)
    }

    fun snapshotRecorder(type:Class<*>,view:Class<*>):Method? = type.declaredMethods.singleOrNull {
        it.name.startsWith("convertViewToBitmapInMainExecutor\$lambda\$") && Modifier.isStatic(it.modifiers) &&
            it.returnType==Void.TYPE && it.parameterTypes.contentEquals(arrayOf(
                Runnable::class.java,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,CompletableFuture::class.java,view))
    }

    fun isViewBackground(method:Method,view:Class<*>)=method.parameterCount==1 ||
        (method.parameterCount==2 && view.isAssignableFrom(method.parameterTypes[1]))

    fun splitIconMethod(type:Class<*>,bitmap:Class<*>,paint:Class<*>,canvas:Class<*>,rect:Class<*>):Method? = try {
        if(type.getDeclaredField("mBitmap").type!=bitmap || type.getDeclaredField("mSubLayerBitmap").type!=bitmap ||
            type.getDeclaredField("mPaintSubLayer").type!=paint)null
        else type.getDeclaredMethod("drawSubLayerInternal",canvas,rect).takeIf {it.returnType==Void.TYPE}
    }catch(_:ReflectiveOperationException){null}
}
