package com.cosmod.launcherglass.xposed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import com.cosmod.launcherglass.util.DesktopGrid

/** Anonymous cell footprints: no app icons, titles or widget content are copied. */
internal class DesktopGridPreview(context:Context):View(context) {
    var columns=4
    var rows=6
    var cells:List<DesktopGrid.Cell> = emptyList()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val foreground=TypedValue().let {value ->
        context.theme.resolveAttribute(android.R.attr.textColorPrimary,value,true)
        if(value.resourceId!=0)context.getColorStateList(value.resourceId).defaultColor else value.data
    }
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val cell=minOf(width.toFloat()/columns,height.toFloat()/rows)
        val left=(width-cell*columns)/2f;val top=(height-cell*rows)/2f
        paint.color=foreground;paint.alpha=45
        for(y in 0 until rows)for(x in 0 until columns)
            canvas.drawCircle(left+(x+.5f)*cell,top+(y+.5f)*cell,cell*.035f,paint)
        for(item in cells) {
            paint.alpha=if(item.width==1 && item.height==1)180 else 90
            val gap=cell*.12f
            canvas.drawRoundRect(left+item.x*cell+gap,top+item.y*cell+gap,
                left+(item.x+item.width)*cell-gap,top+(item.y+item.height)*cell-gap,cell*.18f,cell*.18f,paint)
        }
    }
}
