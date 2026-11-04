package com.cosmod.launcherglass.util

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.SystemClock
import com.cosmod.launcherglass.xposed.ColorDepthEffect
import com.cosmod.launcherglass.xposed.PrefsHelper

/** Accessed only by CosmodWallpaper's single worker, never by the UI thread. */
object GpuWallpaperBlur {
    private class Target(private val name: String, private val material: Boolean = false) {
        private var reader: ImageReader? = null
        private var renderer: HardwareRenderer? = null
        private val node = RenderNode(name)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var effectRadius = -1f
        private var effectTone = Int.MIN_VALUE
        private var effectVibrancy = -1
        private var effectDepthBits = -1
        private var effectDepthStage = -1
        private var outputWidth = 0
        private var outputHeight = 0

        fun render(source: Bitmap, width: Int, height: Int, radius: Float,level:Int=50,vibrancy:Int=0): Bitmap {
            // ColorOS UniRender supports this GPU entry point directly, while
            // a standalone ImageReader renderer can submit no output at all.
            if (hardwareBitmapMethod != null) {
                if (width != outputWidth || height != outputHeight) {
                    node.setPosition(0, 0, width, height)
                    outputWidth = width
                    outputHeight = height
                }
                record(source, width, height, radius,level,vibrancy)
                return hardwareBitmapMethod.invoke(null, node, width, height) as? Bitmap
                    ?: error("GPU $name produced no hardware bitmap")
            }
            var images = reader
            if (images == null || images.width != width || images.height != height) {
                close()
                images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
                reader = images
                renderer = HardwareRenderer().apply {
                    setSurface(images.surface)
                    setContentRoot(node)
                    setOpaque(false)
                }
                node.setPosition(0, 0, width, height)
            }
            record(source, width, height, radius,level,vibrancy)
            val result = renderer!!.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = images.acquireLatestImage() ?: error("GPU $name produced no image (sync=$result)")
            image.use {
                val buffer = it.hardwareBuffer ?: error("GPU $name produced no hardware buffer")
                buffer.use { hardware ->
                    val wrapped = Bitmap.wrapHardwareBuffer(hardware, ColorSpace.get(ColorSpace.Named.SRGB))
                        ?: error("Cannot wrap GPU $name buffer")
                    try {
                        // Own the GPU texture before returning the ImageReader's
                        // slot; cached folder display lists must not see it reused.
                        return wrapped.copy(Bitmap.Config.HARDWARE, false)
                            ?: error("Cannot retain GPU $name image")
                    } finally { wrapped.recycle() }
                }
            }
        }

        private fun record(source: Bitmap, width: Int, height: Int, radius: Float,level:Int,vibrancy:Int) {
            val actualRadius=radius
            val bits=if(material)PrefsHelper.colorDepthBits else 24
            val stage=if(material)PrefsHelper.colorDepthStage else ColorDepthEffect.FINISHED_PLATE
            if (effectRadius != actualRadius || effectTone != level || effectVibrancy != vibrancy || effectDepthBits!=bits || effectDepthStage!=stage) {
                val vivid=BackdropVibrancy.effect(vibrancy)
                val color=if(material)ColorDepthEffect.at(vivid,ColorDepthEffect.BEFORE_BLUR)else vivid
                val blur=if(actualRadius>0f){
                    if(color!=null)RenderEffect.createBlurEffect(actualRadius,actualRadius,color,Shader.TileMode.CLAMP)
                    else RenderEffect.createBlurEffect(actualRadius,actualRadius,Shader.TileMode.CLAMP)
                }else color
                val toned=ShadowTone.effect(blur,level)
                node.setRenderEffect(if(material)ColorDepthEffect.at(toned,ColorDepthEffect.AFTER_BLUR)else toned)
                effectRadius = actualRadius;effectTone=level;effectVibrancy=vibrancy;effectDepthBits=bits;effectDepthStage=stage
            }
            val canvas = node.beginRecording(width, height)
            try {
                canvas.drawBitmap(source, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
            } finally { node.endRecording() }
        }

        fun close() {
            renderer?.destroy()
            renderer = null
            reader?.close()
            reader = null
            node.discardDisplayList()
            effectRadius = -1f
        }
    }

    // Optional OEM GPU path; lookup failure selects ImageReader, rendering failure uses the caller's CPU fallback.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private val hardwareBitmapMethod = try {
        HardwareRenderer::class.java.getDeclaredMethod("createHardwareBitmap",
            RenderNode::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
    } catch (_: Throwable) { null }
    private val blurTarget = Target("CosmodBlur",true)
    private val folderTarget = Target("CherryPickFolderBlur",true)
    private val sampleTarget = Target("CosmodMotionSample")
    private var count = 0
    private var elapsedMs = 0L

    fun blur(source: Bitmap, width: Int, height: Int, radius: Float): Bitmap {
        val start = SystemClock.uptimeMillis()
        val result = blurTarget.render(source, width, height, radius,com.cosmod.launcherglass.xposed.PrefsHelper.blurLightening,com.cosmod.launcherglass.xposed.PrefsHelper.blurVibrancy)
        elapsedMs += SystemClock.uptimeMillis() - start
        count++
        if (count == 1 || count % 60 == 0) {
            GlassLogger.i("GpuBlur", "GPU blur active: ${width}x$height, output=${result.config}, frames=$count, avg=${elapsedMs / count}ms")
        }
        return result
    }

    fun blurFolder(source:Bitmap,width:Int,height:Int,radius:Float,level:Int,vibrancy:Int)=folderTarget.render(source,width,height,radius,level,vibrancy)

    fun fingerprint(source: Bitmap): Long {
        val thumbnail = sampleTarget.render(source, 12, 12, 0f)
        try {
            val pixels = thumbnail.copy(Bitmap.Config.ARGB_8888, false) ?: error("Cannot read motion thumbnail")
            try {
                var signature = 17L
                for (y in 0 until 12) for (x in 0 until 12) {
                    signature = signature * 31L + (pixels.getPixel(x, y) and 0x00F8F8F8)
                }
                return signature
            } finally { pixels.recycle() }
        } finally { thumbnail.recycle() }
    }

    fun close() {
        blurTarget.close()
        folderTarget.close()
        sampleTarget.close()
    }
}
