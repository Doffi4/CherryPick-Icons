package com.cosmod.launcherglass.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.Checkable
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.sin
import androidx.appcompat.widget.AppCompatImageView
import com.cosmod.launcherglass.R

// M3 Expressive: spring response for spatial properties; critically damped
// response for opacity. Unit-mass spring, normalized to the animation interval.
internal val CherryMotion = android.animation.TimeInterpolator { fraction ->
    if(fraction>=1f)1f else {
        val t=fraction*.45;val w=kotlin.math.sqrt(750.0);val z=.75;val wd=w*kotlin.math.sqrt(1-z*z)
        (1-kotlin.math.exp(-z*w*t)*(kotlin.math.cos(wd*t)+z/kotlin.math.sqrt(1-z*z)*kotlin.math.sin(wd*t))).toFloat()
    }
}
internal val CherryEffects = android.animation.TimeInterpolator { f ->
    if(f>=1f)1f else (1-(1+10*f)*kotlin.math.exp(-10*f.toDouble())).toFloat()
}

/** A stable-width action strip: an expanding cell borrows space from its neighbors. */
internal class ConnectedActions(context: Context, buttons: List<View>) : LinearLayout(context) {
    private var animation: Animator? = null
    private val unit = context.resources.displayMetrics.density
    init {
        orientation=HORIZONTAL
        gravity=Gravity.CENTER_VERTICAL
        layoutParams=LayoutParams((buttons.size*48*unit).toInt(),(48*unit).toInt())
        buttons.forEachIndexed { i, view ->
            val left=if(i==0)12*unit else 4*unit
            val right=if(i==buttons.lastIndex)12*unit else 4*unit
            ((view.background as? RippleDrawable)?.getDrawable(0) as? GradientDrawable)?.cornerRadii=
                floatArrayOf(left,left,right,right,right,right,left,left)
            addView(view,LayoutParams(0,LayoutParams.MATCH_PARENT,1f).apply {
                if(i>0) leftMargin=(3*unit).toInt()
            })
        }
        isBaselineAligned=false
    }
    private fun weights()=FloatArray(childCount){(getChildAt(it).layoutParams as LayoutParams).weight}
    private fun assign(values:FloatArray) {
        for(i in values.indices) {
            val params=getChildAt(i).layoutParams as LayoutParams
            params.weight=values[i]
            getChildAt(i).layoutParams=params
        }
    }
    private fun target(view: View, amount:Float):FloatArray {
        val index=indexOfChild(view)
        return FloatArray(childCount){ if(it==index) 1f+amount*.58f else 1f-amount*.58f/(childCount-1).coerceAtLeast(1) }
    }
    private fun segment(from:FloatArray,to:FloatArray,time:Long)=ValueAnimator.ofFloat(0f,1f).apply {
        duration=(time*1.1f).toLong();interpolator=CherryMotion
        addUpdateListener { value ->
            val f=value.animatedValue as Float
            assign(FloatArray(childCount){from[it]+(to[it]-from[it])*f})
        }
    }
    fun pulse(view: View,time:Long=1000,onProgress:((Float)->Unit)?=null) {
        animation?.cancel()
        val from=weights()
        val enlarged=target(view,1f)
        // One eased timeline drives width and glyph rotation together. Applying
        // another interpolator to either half previously distorted the curve.
        animation=ValueAnimator.ofFloat(0f,1f).apply {
            duration=(time*1.1f).toLong();interpolator=CherryMotion
            addUpdateListener {
                val f=it.animatedValue as Float
                val bulge=sin(Math.PI*f).toFloat()
                assign(FloatArray(childCount){i -> from[i]+(1f-from[i])*f+(enlarged[i]-1f)*bulge })
                onProgress?.invoke(f)
            }
            start()
        }
    }
    fun hold(view:View, progress:Float) {
        animation?.cancel();animation=null
        assign(target(view,CherryMotion.getInterpolation(progress.coerceIn(0f,1f))))
    }
    fun release() {
        animation?.cancel()
        animation=segment(weights(),FloatArray(childCount){1f},200).apply { start() }
    }
    override fun onDetachedFromWindow() { animation?.cancel();super.onDetachedFromWindow() }
}

internal class SquareAction(context: Context, icon:Int, tint:Int, fill:Int, label:String) : FrameLayout(context), Checkable {
    val glyph=AppCompatImageView(context).apply {
        setImageResource(icon)
        imageTintList=ColorStateList.valueOf(tint)
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var checked=false
    private var actionShape:GradientDrawable?=null
    var isToggle=false
    init {
        val d=resources.displayMetrics.density
        layoutParams=LinearLayout.LayoutParams((48*d).toInt(),(48*d).toInt())
        val shape=GradientDrawable().apply { cornerRadius=12*d;setColor(fill);setStroke(d.toInt().coerceAtLeast(1),(tint and 0xffffff) or 0x30000000) }
        actionShape=shape
        background=RippleDrawable(ColorStateList.valueOf((tint and 0xffffff) or 0x24000000),shape,null)
        addView(glyph,LayoutParams((24*d).toInt(),(24*d).toInt(),Gravity.CENTER))
        contentDescription=label;isFocusable=true;isClickable=true
    }
    fun roundCorners(radius:Float) { actionShape?.cornerRadius=radius }
    fun roundFreeEnd(radius:Float) {
        actionShape?.cornerRadii=floatArrayOf(0f,0f,radius,radius,radius,radius,0f,0f)
    }
    override fun isChecked()=checked
    override fun setChecked(value:Boolean) {
        checked=value
        if(isToggle) glyph.setImageResource(if(value)R.drawable.ic_check else R.drawable.ic_close)
    }
    override fun toggle() { setChecked(!checked) }
    fun animateChecked(value:Boolean) {
        checked=value
        glyph.animate().withEndAction(null).cancel()
        glyph.rotation=0f
        glyph.animate().rotation(-180f).setInterpolator(CherryMotion).setDuration(72).withEndAction {
            glyph.setImageResource(if(checked)R.drawable.ic_check else R.drawable.ic_close)
            glyph.animate().rotation(-360f).setInterpolator(CherryMotion).setDuration(72)
                .withEndAction { glyph.rotation=0f }.start()
        }.start()
    }
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if(isToggle) { info.isCheckable=true;info.isChecked=checked;info.className="android.widget.ToggleButton" }
    }
}
