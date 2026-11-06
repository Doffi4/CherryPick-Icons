package com.cosmod.launcherglass.xposed

import java.lang.ref.WeakReference

/** Cache identity without keeping launcher views or drawables alive. */
internal class CpuFrameOwner(view:Any,drawable:Any) {
    private val hash=31*System.identityHashCode(view)+System.identityHashCode(drawable)
    private val view=WeakReference(view)
    private val drawable=WeakReference(drawable)

    override fun hashCode()=hash
    override fun equals(other:Any?):Boolean {
        if(this===other)return true
        if(other !is CpuFrameOwner)return false
        val owner=view.get() ?: return false
        val icon=drawable.get() ?: return false
        return owner===other.view.get() && icon===other.drawable.get()
    }
}
