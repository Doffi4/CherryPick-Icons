package com.cosmod.launcherglass.xposed

import java.util.WeakHashMap

// Weak collections can shrink between size() and iterator(). In particular,
// Kotlin's single-element toList()/toMap() fast paths then call next() on an
// empty iterator, even with all application access synchronized. Copy by
// iteration instead; callers still own their normal thread/locking contract.
internal fun <T> Iterable<T>.weakSnapshot(): List<T> {
    val result=ArrayList<T>()
    for(item in this)result.add(item)
    return result
}

internal fun <K,V> WeakHashMap<K,V>.strongSnapshot(): Map<K,V> {
    val result=LinkedHashMap<K,V>()
    for((key,value) in this)result[key]=value
    return result
}
