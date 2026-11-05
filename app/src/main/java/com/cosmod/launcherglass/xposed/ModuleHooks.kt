package com.cosmod.launcherglass.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Member
import java.lang.reflect.Method

/** Settings/bootstrap observers stay installed; rendering/animation hooks exist only while enabled. */
internal object ModuleHooks {
    private data class Entry(val method:Member,val callback:XC_MethodHook,var handle:XC_MethodHook.Unhook?=null)
    private val entries=ArrayList<Entry>()
    @Volatile private var active=false
    val isActive get()=active
    /** Incremented on every activation: observers can tell whether they ran without a gap. */
    @Volatile var epoch=0
        private set
    fun hookMethod(method:Method,callback:XC_MethodHook)=hookMember(method,callback)
    @Synchronized fun hookMember(method:Member,callback:XC_MethodHook){
        val entry=Entry(method,callback)
        if(active)entry.handle=XposedBridge.hookMethod(method,callback)
        entries.add(entry)
    }
    fun hookAllConstructors(type:Class<*>,callback:XC_MethodHook){
        type.declaredConstructors.forEach {hookMember(it,callback)}
    }
    fun hookAllMethods(type:Class<*>,name:String,callback:XC_MethodHook){
        type.declaredMethods.filter {it.name==name}.forEach {hookMethod(it,callback)}
    }
    fun findAndHookMethod(type:Class<*>,name:String,vararg arguments:Any?){
        val callback=arguments.last() as XC_MethodHook
        val parameters=arguments.dropLast(1).map {
            when(it){is Class<*> -> it;is String -> XposedHelpers.findClass(it,type.classLoader);else -> error("Invalid hook parameter: $it")}
        }.toTypedArray()
        hookMethod(XposedHelpers.findMethodExact(type,name,*parameters),callback)
    }
    fun findAndHookMethod(name:String,loader:ClassLoader,method:String,vararg arguments:Any?){
        findAndHookMethod(XposedHelpers.findClass(name,loader),method,*arguments)
    }
    @Synchronized fun setEnabled(enabled:Boolean){
        if(active==enabled)return
        active=enabled
        if(enabled)epoch++
        for(entry in entries){
            if(enabled)entry.handle=XposedBridge.hookMethod(entry.method,entry.callback)
            else {entry.handle?.unhook();entry.handle=null}
        }
    }
}
