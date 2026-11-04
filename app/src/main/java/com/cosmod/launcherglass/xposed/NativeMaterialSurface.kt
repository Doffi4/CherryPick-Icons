package com.cosmod.launcherglass.xposed

import android.graphics.PixelFormat
import android.view.Surface
import android.view.SurfaceControl
import de.robv.android.xposed.XposedHelpers

/** Small material buffer, inheriting ALL motion/clipping from the OEM parent. */
internal class NativeMaterialSurface private constructor(
    val control:SurfaceControl,val surface:Surface,
    val width:Int,val height:Int,val canvasWidth:Int,val canvasHeight:Int
){
    private var shown=false
    fun detach(){
        if(!control.isValid)return
        val tx=SurfaceControl.Transaction()
        try {
            XposedHelpers.callMethod(tx,"hide",control)
            XposedHelpers.callMethod(tx,"reparent",control,null)
            tx.apply();shown=false
        }finally{tx.close()}
    }
    fun attach(parent:SurfaceControl){
        val tx=SurfaceControl.Transaction()
        try {XposedHelpers.callMethod(tx,"reparent",control,parent);tx.apply()}finally{tx.close()}
    }
    fun show(){
        if(shown)return
        val tx=SurfaceControl.Transaction()
        try {
            XposedHelpers.callMethod(tx,"setMatrix",control,canvasWidth.toFloat()/width,0f,0f,canvasHeight.toFloat()/height)
            XposedHelpers.callMethod(tx,"setPosition",control,0f,0f)
            XposedHelpers.callMethod(tx,"setLayer",control,1)
            XposedHelpers.callMethod(tx,"show",control)
            tx.apply();shown=true
        }finally{tx.close()}
    }
    fun close(){
        try {
            val tx=SurfaceControl.Transaction()
            try {if(control.isValid){XposedHelpers.callMethod(tx,"remove",control);tx.apply()}}finally{tx.close()}
        }catch(_:Throwable){}
        try{surface.release()}catch(_:Throwable){}
        try{control.release()}catch(_:Throwable){}
    }
    companion object {
        private val session by lazy {XposedHelpers.newInstance(Class.forName("android.view.SurfaceSession"))}
        fun create(parent:SurfaceControl?,width:Int,height:Int,canvasWidth:Int,canvasHeight:Int):NativeMaterialSurface {
            val builder=XposedHelpers.newInstance(Class.forName("android.view.SurfaceControl"+'$'+"Builder"),session)
            XposedHelpers.callMethod(builder,"setName","CherryPick.material")
            if(parent!=null)XposedHelpers.callMethod(builder,"setParent",parent)
            XposedHelpers.callMethod(builder,"setFormat",PixelFormat.TRANSLUCENT)
            XposedHelpers.callMethod(builder,"setBufferSize",width,height)
            val control=XposedHelpers.callMethod(builder,"build") as SurfaceControl
            val surface=XposedHelpers.newInstance(Surface::class.java) as Surface
            try {
                XposedHelpers.callMethod(surface,"copyFrom",control)
                check(surface.isValid){"Material producer is invalid"}
                return NativeMaterialSurface(control,surface,width,height,canvasWidth,canvasHeight)
            }catch(t:Throwable){surface.release();control.release();throw t}
        }
    }
}
