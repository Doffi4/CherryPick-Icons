package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture

@Suppress("UNUSED_PARAMETER")
class LauncherMethodResolverTest {
    class Bitmap
    class Paint
    class Canvas
    class Rect
    open class View
    class CellLayout:View()
    class ConvertParams
    interface Listener { fun onBitmap(bitmap:Bitmap,flags:Int) }

    class NamedFactory {
        fun removeBlurBitmapListener(listener:Listener) {}
        fun addBlurBitmapListener(listener:Listener) {}
        fun release(recycle:Boolean) {}
        fun setEnabled(enabled:Boolean) {}
    }
    class ObfuscatedFactory {
        fun c(listener:Listener) {}
        fun b(recycle:Boolean) {}
        fun a(listener:Listener) {}
    }
    class UnknownFactory {
        fun x(listener:Listener) {}
        fun y(listener:Listener) {}
        fun release(recycle:Boolean) {}
    }
    class AmbiguousFactory {
        fun addFirstListener(listener:Listener) {}
        fun addSecondListener(listener:Listener) {}
        fun release(recycle:Boolean) {}
    }

    @Test fun namedFactorySubscribesInsteadOfRemovingListener() {
        val api=LauncherMethodResolver.wallpaperFactory(NamedFactory::class.java,Bitmap::class.java)!!
        assertEquals("addBlurBitmapListener",api.subscribe.name)
        assertEquals("release",api.release.name)
        assertEquals("onBitmap",api.frame.name)
    }
    @Test fun obfuscatedFactoryUsesVerifiedAddRemovePair() {
        val api=LauncherMethodResolver.wallpaperFactory(ObfuscatedFactory::class.java,Bitmap::class.java)!!
        assertEquals("a",api.subscribe.name)
        assertEquals("b",api.release.name)
    }
    @Test fun unknownOrAmbiguousFactoryFallsBack() {
        assertNull(LauncherMethodResolver.wallpaperFactory(UnknownFactory::class.java,Bitmap::class.java))
        assertNull(LauncherMethodResolver.wallpaperFactory(AmbiguousFactory::class.java,Bitmap::class.java))
    }

    object Snapshot16 {
        @JvmStatic fun `convertViewToBitmapInMainExecutor$lambda$4`(task:Runnable,w:Int,h:Int,result:CompletableFuture<*>,view:View) {}
    }
    object Snapshot17 {
        @JvmStatic fun `convertViewToBitmapInMainExecutor$lambda$5`(task:Runnable,w:Int,h:Int,result:CompletableFuture<*>,view:View) {}
    }
    object Snapshot175 {
        @JvmStatic fun `convertViewToBitmapInMainExecutor$lambda$8`(task:Runnable,w:Int,h:Int,result:CompletableFuture<*>,view:View) {}
        @JvmStatic fun `convertViewToBitmapInMainExecutor$lambda$8$lambda$6$lambda$5`(canvas:Canvas,view:View) {}
    }
    object AmbiguousSnapshot {
        @JvmStatic fun `convertViewToBitmapInMainExecutor$lambda$4`(task:Runnable,w:Int,h:Int,result:CompletableFuture<*>,view:View) {}
        @JvmStatic fun `convertViewToBitmapInMainExecutor$lambda$5`(task:Runnable,w:Int,h:Int,result:CompletableFuture<*>,view:View) {}
    }
    @Test fun recorderFollowsSignatureAcrossLambdaRenames() {
        for((type,name) in listOf(Snapshot16::class.java to "4",Snapshot17::class.java to "5",Snapshot175::class.java to "8")){
            assertEquals("convertViewToBitmapInMainExecutor\$lambda\$$name",
                LauncherMethodResolver.snapshotRecorder(type,View::class.java)?.name)
        }
    }
    @Test fun ambiguousRecorderIsNotHooked() {
        assertNull(LauncherMethodResolver.snapshotRecorder(AmbiguousSnapshot::class.java,View::class.java))
    }

    class Background {
        fun drawBackground(canvas:Canvas) {}
        fun drawBackground(canvas:Canvas,view:View?) {}
        fun drawBackground(canvas:Canvas,view:CellLayout?) {}
        fun drawBackground(canvas:Canvas,params:ConvertParams,item:Any) {}
    }
    @Test fun viewBackgroundOverloadsAreNotMistakenForConvertedGeometry() {
        for(method in Background::class.java.declaredMethods){
            val expected=method.parameterCount<3
            assertEquals(method.toString(),expected,LauncherMethodResolver.isViewBackground(method,View::class.java))
        }
    }

    class SplitIcon {
        @JvmField var mBitmap=Bitmap()
        @JvmField var mSubLayerBitmap=Bitmap()
        @JvmField var mPaintSubLayer=Paint()
        fun drawSubLayerInternal(canvas:Canvas,rect:Rect) {}
    }
    class UnrelatedIcon {
        @JvmField var mBitmap=Bitmap()
        @JvmField var mSubLayerBitmap=Bitmap()
        @JvmField var mPaintSubLayer="not a paint"
        fun drawSubLayerInternal(canvas:Canvas,rect:Rect) {}
    }
    @Test fun splitLayerSupportRequiresAllNativeTypes() {
        assertNotNull(LauncherMethodResolver.splitIconMethod(SplitIcon::class.java,Bitmap::class.java,Paint::class.java,Canvas::class.java,Rect::class.java))
        assertNull(LauncherMethodResolver.splitIconMethod(UnrelatedIcon::class.java,Bitmap::class.java,Paint::class.java,Canvas::class.java,Rect::class.java))
    }
}
