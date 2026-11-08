package com.cosmod.launcherglass.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Reads only this module's activation/scope, never changes LSPosed settings. */
object LsposedScopeStatus {
    data class State(val enabled: Boolean, val launcherSelected: Boolean)
    // Overlapping Activity lifetimes must not truncate another reader's mmap-backed snapshot.
    @Synchronized fun read(context: Context): State? {
        val folder=File(context.cacheDir,"lsposed-status").apply { mkdirs() }
        val copy=File(folder,"scope.db")
        val source="/data/adb/lspd/config/modules_config.db"
        fun quote(path:String)="'"+path.replace("'","'\\''")+"'"
        return try {
            // Pre-create as the app UID: root then overwrites the contents while
            // retaining ownership so Android SQLite can open its private copy.
            val commands=mutableListOf<String>()
            for(suffix in listOf("","-wal","-shm")) {
                val file=File(copy.path+suffix)
                file.writeBytes(byteArrayOf())
                commands.add("if [ -f ${quote(source+suffix)} ]; then cat ${quote(source+suffix)} > ${quote(file.path)}; fi")
            }
            if(!SuUtil.execute(commands.joinToString("; "))) return null
            SQLiteDatabase.openDatabase(copy.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                val tables=mutableSetOf<String>()
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'",null).use { while(it.moveToNext())tables.add(it.getString(0)) }
                fun columns(table:String):Set<String> {
                    val names=mutableSetOf<String>()
                    db.rawQuery("PRAGMA table_info($table)",null).use { while(it.moveToNext())names.add(it.getString(1)) }
                    return names
                }
                val userId=android.os.Process.myUid()/100000
                val module=context.packageName
                var enabled=false
                var moduleId:Long?=null
                if("modules_state" in tables) {
                    db.rawQuery("SELECT enabled FROM modules_state WHERE module_pkg_name=? AND user_id=?",arrayOf(module,userId.toString())).use {
                        enabled=it.moveToFirst() && it.getInt(0)!=0
                    }
                } else {
                    val fields=columns("modules")
                    if("mid" !in fields || "enabled" !in fields)return null
                    db.rawQuery("SELECT mid,enabled FROM modules WHERE module_pkg_name=?",arrayOf(module)).use {
                        if(it.moveToFirst()) { moduleId=it.getLong(0);enabled=it.getInt(1)!=0 }
                    }
                }
                val scopeFields=columns("scope")
                val key=if("module_pkg_name" in scopeFields)"module_pkg_name" else "mid"
                val value=if(key=="mid")moduleId?.toString() ?: return State(enabled,false) else module
                var selected=false
                db.rawQuery("SELECT app_pkg_name FROM scope WHERE $key=? AND user_id=?",arrayOf(value,userId.toString())).use {
                    while(it.moveToNext()) if(it.getString(0) in setOf("com.android.launcher","com.oplus.launcher","com.oppo.launcher"))selected=true
                }
                State(enabled,selected)
            }
        } catch(_:Exception) { null }
        finally { folder.listFiles()?.forEach { it.delete() } }
    }
}
