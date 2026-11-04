package com.cosmod.launcherglass.xposed

import java.lang.reflect.Field
import java.lang.reflect.Method

/** Resolve optional OEM members once, including absent members on older launchers. */
internal object NativeIconAccess {
    private val fields = HashMap<Pair<Class<*>, String>, Field?>()
    private val methods = HashMap<Pair<Class<*>, String>, Method?>()
    fun field(target: Any, name: String): Any? {
        val key = target.javaClass to name
        val member = synchronized(fields) {
            if (!fields.containsKey(key)) fields[key] = generateSequence(target.javaClass as Class<*>?) { it.superclass }
                .mapNotNull { type -> type.declaredFields.firstOrNull { it.name == name } }.firstOrNull()?.apply { isAccessible = true }
            fields[key]
        }
        return member?.get(target)
    }
    fun call(target: Any, name: String): Any? {
        val key = target.javaClass to name
        val member = synchronized(methods) {
            if (!methods.containsKey(key)) methods[key] = generateSequence(target.javaClass as Class<*>?) { it.superclass }
                .mapNotNull { type -> type.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() } }
                .firstOrNull()?.apply { isAccessible = true }
            methods[key]
        }
        return member?.invoke(target)
    }
}
