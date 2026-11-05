package com.cosmod.launcherglass.util

object SuUtil {

    fun execute(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    fun restartLauncher(): Boolean {
        return execute("am force-stop com.android.launcher; am force-stop com.oppo.launcher; am force-stop com.oplus.launcher; am start -a android.intent.action.MAIN -c android.intent.category.HOME")
    }

    fun copyWallpaperToLauncher(): Boolean {
        // Legacy no-op; wallpaper capture is handled by WallpaperHelper.
        return true
    }

    fun clearLogcat(): Boolean {
        return execute("logcat -c")
    }

    // Drain stdout/stderr while waiting, cap report size and report failures.
    // A denied/hung su must never leave the share button waiting indefinitely.
    private fun collect(command:String,seconds:Long=8):String {
        return try {
            val process=ProcessBuilder("su","-c",command).redirectErrorStream(true).start()
            val output=StringBuffer()
            val reader=Thread({
                try {process.inputStream.bufferedReader().use {input ->
                    val buffer=CharArray(4096)
                    while(true){
                        val count=input.read(buffer)
                        if(count<0)break
                        synchronized(output){
                            val keep=minOf(count,600_000-output.length)
                            if(keep>0)output.append(buffer,0,keep)
                        }
                    }
                }}catch(_:Exception){}
            },"CherryPickDiagnostics").apply {isDaemon=true;start()}
            val finished=process.waitFor(seconds,java.util.concurrent.TimeUnit.SECONDS)
            if(!finished)process.destroyForcibly()
            reader.join(500)
            if(reader.isAlive)try {process.inputStream.close()}catch(_:Exception){}
            val result=synchronized(output){output.toString()}
            buildString {
                if(!finished)appendLine("[collection timed out after ${seconds}s]")
                else if(process.exitValue()!=0)appendLine("[command failed: exit ${process.exitValue()}]")
                append(result.ifBlank {"[no matching entries / source unavailable]\n"})
                if(result.length>=600_000)appendLine("\n[section truncated at 600000 characters]")
            }
        }catch(e:Exception){"[collection failed: ${e.message}]\n"}
    }

    fun readLogcat(context: android.content.Context): String = buildString {
        appendLine("=== Root and launcher processes ===")
        appendLine(collect("id; ps -A -o PID,NAME | grep -E 'launcher|lspd|lsposed'"))
        appendLine("=== Boot restart ===")
        appendLine(try {BootRestart.describe(context)}catch(e:Exception){"[unavailable: ${e.message}]"})
        appendLine("=== Module logcat (all buffers, last 1500 lines) ===")
        appendLine(collect("logcat -b all -d -v threadtime -t 1500 'ColorOSGlass:V' '*:S'"))
        appendLine("=== Launcher process logcat (last 1200 lines per PID) ===")
        appendLine(collect("""
            for package in com.android.launcher com.oppo.launcher com.oplus.launcher; do
              for launcher_pid in ${'$'}(pidof "${'$'}package"); do
                echo "--- ${'$'}package PID=${'$'}launcher_pid ---"
                logcat -b main,system,crash -d -v threadtime --pid="${'$'}launcher_pid" -t 1200
              done
            done
        """.trimIndent()))
        appendLine("=== System events about the launcher (including previous PIDs) ===")
        appendLine(collect("logcat -b system,events -d -v threadtime -t 12000 | grep -i -E -A 8 'com\\.(android|oppo|oplus)\\.launcher|ColorOSGlass|am_anr|am_crash' | tail -n 1500"))
        appendLine("=== Launcher crashes and stack traces ===")
        appendLine(collect("logcat -b crash -d -v threadtime -t 4000 | grep -i -E -B 3 -A 60 'com\\.(android|oppo|oplus)\\.launcher|ColorOSGlass|com\\.cosmod\\.launcherglass' | tail -n 1000"))
        appendLine("=== LSPosed files: module and launcher entries ===")
        appendLine(collect("""
            found=0
            for directory in /data/adb/lspd/log /data/adb/lsposed/log /data/adb/lspd/log.old; do
              [ -d "${'$'}directory" ] || continue
              for kind in modules verbose; do
                for file in ${'$'}(ls -1t "${'$'}directory"/"${'$'}kind"*.log 2>/dev/null | head -n 1); do
                  found=1
                  echo "--- ${'$'}file ---"
                  tail -n 4000 "${'$'}file" | grep -i -E -A 16 'ColorOSGlass|com\.cosmod\.launcherglass|com\.(android|oppo|oplus)\.launcher' | tail -n 600
                done
              done
              [ "${'$'}found" = 1 ] && break
            done
            [ "${'$'}found" = 1 ] || echo '[LSPosed log files not found or inaccessible]'
        """.trimIndent(),12))
        appendLine("=== Launcher rendering ===")
        appendLine(collect("dumpsys gfxinfo com.android.launcher | head -n 85"))
        appendLine("=== End diagnostics ===")
    }
}
