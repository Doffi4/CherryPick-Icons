package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.os.Build
import androidx.annotation.RequiresApi
import com.cosmod.launcherglass.util.GlassLogger

/** One retained GPU lens per sample. No CPU pixels, extra capture, or blur pass. */
@RequiresApi(33)
internal class LiquidGlassShader private constructor(private val shader:RuntimeShader,private val stateless:Boolean=false) {
    private var image:Bitmap?=null
    private var backdrop:Shader?=null
    private var previousStyle:GlassStyle?=null
    private var previousMask:Shader?=null
    private var previousWidth=0
    private var previousHeight=0
    private var previousRounding=Float.NaN
    private var previousFilter:ColorMatrixColorFilter?=null
    private var filterSet=false
    fun update(wallpaper:Bitmap,mask:Shader,source:RectF,width:Int,height:Int,filter:ColorMatrixColorFilter?,rounding:Float,style:GlassStyle=GlassStyle.icons()):Shader {
        if(image!==wallpaper){
            backdrop=GlassGeometry.wallpaperShader(wallpaper)
            shader.setInputShader("backdrop",backdrop!!)
            image=wallpaper
        }else if(stateless)shader.setInputShader("backdrop",backdrop!!)
        // A shared instance is re-armed completely for each snapshot because
        // the previous caller may have left another sample's state behind.
        if(stateless || previousMask!==mask){shader.setInputShader("mask",mask);previousMask=mask}
        if(stateless || previousStyle!=style || previousWidth!=width || previousHeight!=height || previousRounding!=rounding){
            geometry(width,height,rounding,style)
            previousStyle=style;previousWidth=width;previousHeight=height;previousRounding=rounding
        }
        shader.setFloatUniform("crop",source.left,source.top,source.width(),source.height())
        if(stateless || !filterSet || previousFilter!==filter){
            colors(filter)
            previousFilter=filter;filterSet=true
        }
        return shader
    }
    /** Lens over a RenderEffect input of [wrapperW]x[wrapperH] whose plate starts at [margin]. */
    fun updateLifted(mask:Shader,width:Int,height:Int,margin:Float,wrapperW:Int,wrapperH:Int,filter:ColorMatrixColorFilter?,rounding:Float,style:GlassStyle):RuntimeShader {
        shader.setInputShader("mask",mask)
        geometry(width,height,rounding,style)
        shader.setFloatUniform("origin",margin,margin)
        shader.setFloatUniform("limit",wrapperW.toFloat(),wrapperH.toFloat())
        colors(filter)
        return shader
    }
    private fun geometry(width:Int,height:Int,rounding:Float,style:GlassStyle){
        val edge=minOf(width,height).toFloat()
        val extend=style.folder || style.extendedLens
        shader.setFloatUniform("capPadding",if(extend)(width-edge)*.5f else 0f,if(extend)(height-edge)*.5f else 0f)
        shader.setFloatUniform("capSize",if(extend)edge else width.toFloat(),if(extend)edge else height.toFloat())
        shader.setFloatUniform("size",width.toFloat(),height.toFloat())
        shader.setFloatUniform("rounding",rounding)
        shader.setFloatUniform("bevelWidth",.015f+style.width*.0014f)
        shader.setFloatUniform("distortion",style.distortion/50f)
    }
    private fun colors(filter:ColorMatrixColorFilter?){
        val matrix=ColorMatrix();filter?.getColorMatrix(matrix)
        val m=matrix.array
        shader.setFloatUniform("rowR",m[0],m[1],m[2],m[3])
        shader.setFloatUniform("rowG",m[5],m[6],m[7],m[8])
        shader.setFloatUniform("rowB",m[10],m[11],m[12],m[13])
        shader.setFloatUniform("bias",m[4]/255f,m[9]/255f,m[14]/255f)
    }
    companion object {
        @Volatile private var failed=false
        @Volatile private var liftedFailed=false
        private var sharedLens:LiquidGlassShader?=null
        private var liftedLens:LiquidGlassShader?=null
        fun create():LiquidGlassShader? {
            if(Build.VERSION.SDK_INT<33 || failed)return null
            return try {LiquidGlassShader(RuntimeShader(SOURCE))}catch(t:Throwable){
                failed=true;GlassLogger.e("LiquidGlass","GPU lens unavailable; keeping frosted material",t);null
            }
        }
        /** UI-thread lens for RenderEffect snapshots only, never for a Paint. */
        fun shared():LiquidGlassShader? {
            if(Build.VERSION.SDK_INT<33 || failed)return null
            sharedLens?.let {return it}
            return try {LiquidGlassShader(RuntimeShader(SOURCE),stateless=true).also {sharedLens=it}}catch(t:Throwable){
                failed=true;GlassLogger.e("LiquidGlass","GPU lens unavailable; keeping frosted material",t);null
            }
        }
        /** UI-thread lens for lifted RenderEffects; [updateLifted] re-arms every uniform. */
        fun lifted():LiquidGlassShader? {
            if(Build.VERSION.SDK_INT<33 || failed || liftedFailed)return null
            liftedLens?.let {return it}
            return try {LiquidGlassShader(RuntimeShader(LIFTED_SOURCE),stateless=true).also {liftedLens=it}}catch(t:Throwable){
                liftedFailed=true;GlassLogger.e("LiquidGlass","Lifted GPU lens unavailable; using frosted lifted material",t);null
            }
        }
        // Shared declarations and lens geometry. Both entry points evaluate
        // the same contour-normal refraction; only backdrop addressing differs.
        private const val HEADER="""
            uniform shader backdrop;
            uniform shader mask;
            uniform float2 capPadding;
            uniform float2 capSize;
            uniform float2 size;
            uniform float rounding;
            uniform float bevelWidth;
            uniform float distortion;
            uniform float4 rowR;
            uniform float4 rowG;
            uniform float4 rowB;
            uniform float3 bias;
        """
        private const val LENS="""
            float2 lensOffset(float2 xy) {
                float edgeSize=min(size.x,size.y);
                float2 center=xy-size*.5;
                // Reuse the icon lens unchanged at each rounded end. The excess
                // length is a straight extrusion of its central cross-section,
                // so wide/tall folders are neither stretched ellipses nor a
                // different contour-normal refraction model.
                center=sign(center)*max(abs(center)-capPadding,float2(0.0));
                float radialDistance=length(center);
                float2 direction=center/max(radialDistance,.001);
                // Intersect a radial ray with a rounded rectangle, instead of
                // selecting the nearest side. The optical cap stays rounded
                // even when the silhouette is deliberately almost square.
                float radius=edgeSize*max(rounding,.20);
                float2 halfSize=capSize*.5;
                float2 absDirection=abs(direction);
                float2 sideDistances=halfSize/max(absDirection,float2(.0001));
                float boundary=min(sideDistances.x,sideDistances.y);
                float2 sidePoint=absDirection*boundary;
                float2 cornerCenter=halfSize-radius;
                if(sidePoint.x>cornerCenter.x && sidePoint.y>cornerCenter.y){
                    float projection=dot(absDirection,cornerCenter);
                    float discriminant=projection*projection-dot(cornerCenter,cornerCenter)+radius*radius;
                    boundary=projection+sqrt(max(discriminant,0.0));
                }
                float radial=clamp(radialDistance/max(boundary,1.0),0.0,1.0);
                float edgeStart=clamp(1.0-bevelWidth*4.0,.25,.96);
                float shoulder=smoothstep(edgeStart,1.0,radial);

                // A curved cap normal changes continuously around the whole
                // lens. Refraction through it magnifies/bends the backdrop;
                // there are no four independent planar side displacements.
                float slope=.18*radial+.95*shoulder/sqrt(max(.15,1.0-.85*shoulder*shoulder));
                float3 normal=normalize(float3(direction*slope,1.0));
                float3 ray=refract(float3(0.0,0.0,-1.0),normal,1.0/1.45);
                return ray.xy/max(-ray.z,.25)*(edgeSize*.22)*distortion;
            }
            half4 shade(half4 sampled,float2 xy) {
                float3 color=clamp(float3(dot(float4(sampled),rowR),dot(float4(sampled),rowG),dot(float4(sampled),rowB))+bias,0.0,1.0);
                // The shared contour texture supplies identical edge lighting
                // in Frosted and Liquid Glass, honoring the contour toggle.
                half alpha=mask.eval(xy).a;
                return half4(half3(color)*alpha,alpha);
            }
        """
        private const val SOURCE=HEADER+"""
            uniform float4 crop;
        """+LENS+"""
            half4 main(float2 xy) {
                float2 uv=(xy+lensOffset(xy))/size;
                return shade(backdrop.eval(crop.xy+uv*crop.zw),xy);
            }
        """
        // Lifted variant: "backdrop" is the RenderEffect input of a node that
        // extends the plate by [origin] on every side, so refraction may look
        // past the silhouette without sampling beyond the recorded backdrop.
        private const val LIFTED_SOURCE=HEADER+"""
            uniform float2 origin;
            uniform float2 limit;
        """+LENS+"""
            half4 main(float2 xy) {
                float2 p=xy-origin;
                if(p.x<0.0 || p.y<0.0 || p.x>size.x || p.y>size.y)return half4(0.0);
                float2 q=clamp(origin+p+lensOffset(p),float2(.5),limit-float2(.5));
                return shade(backdrop.eval(q),p);
            }
        """
    }
}
