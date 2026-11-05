package com.cosmod.launcherglass.util

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.SettingsDefaults
import com.cosmod.launcherglass.ui.SettingsProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Restarts the stock launcher at most once per boot, shortly after the first unlock.
 *
 * An ordinary unlock of a device that has been running for a while must never restart the launcher. OEM freezers
 * and background restrictions can deliver BOOT_COMPLETED, or the inexact fallback alarm, hours late; an unlock
 * listener created by such a late delivery would then fire on a random unlock. Therefore every step is bounded by
 * [WINDOW_MS] of uptime, only the system user acts (clone and work profiles receive BOOT_COMPLETED whenever they
 * start), and the decision is recorded per boot (boot_id, BOOT_COUNT, boot wall time), so a boot is handled once.
 */
internal object BootRestart {
    private const val TAG = "ColorOSGlass"
    private const val STATE = "boot_restart_state"
    private const val KEY_ID = "boot_id"
    private const val KEY_COUNT = "boot_count"
    private const val KEY_WALL = "boot_wall"
    private const val KEY_RESULT = "result"
    private const val KEY_AT = "result_uptime"
    private const val LEGACY_ATTEMPT = "boot_restart_attempt_boot_count"
    const val WINDOW_MS = 15 * 60_000L
    private const val FALLBACK_MS = 10_000L
    private const val WALL_TOLERANCE_MS = 5 * 60_000L
    private val launchers = setOf("com.android.launcher", "com.oppo.launcher", "com.oplus.launcher", "com.coloros.launcher", "com.android.launcher3")
    private val main = Handler(Looper.getMainLooper())
    private var listener: BroadcastReceiver? = null
    private var expiry: Runnable? = null
    private var handledInProcess = false

    class Boot(val id: String?, val count: Int, val wall: Long)

    fun log(message: String) { Log.i(TAG, "[BootRestart] $message") }
    fun uptime(): Long = SystemClock.elapsedRealtime()
    fun inWindow(): Boolean = uptime() <= WINDOW_MS
    private fun state(context: Context) = context.getSharedPreferences(STATE, Context.MODE_PRIVATE)
    private fun pending(context: Context) = PendingIntent.getBroadcast(context, 0,
        Intent(context, LauncherRestartReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun enabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(SettingsProvider.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(SettingsProvider.KEY_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_ENABLED)) &&
            prefs.getBoolean(SettingsProvider.KEY_BOOT_RESTART, SettingsDefaults.boolean(SettingsProvider.KEY_BOOT_RESTART))
    }
    fun systemUser(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isSystemUser ?: (Process.myUid() / 100_000 == 0)
    fun unlocked(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
        return power.isInteractive && !keyguard.isKeyguardLocked
    }
    /** Default home package, only when it is a known stock launcher; never stop anything else. */
    fun homePackage(context: Context): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val target = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return target?.takeIf { it in launchers }
    }

    fun current(context: Context): Boot {
        val id = try {
            File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf { it.length in 8..64 }
        } catch (_: Throwable) { null }
        val count = try { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) } catch (_: Throwable) { -1 }
        return Boot(id, count, System.currentTimeMillis() - SystemClock.elapsedRealtime())
    }
    fun handled(context: Context, boot: Boot): Boolean {
        if (handledInProcess) return true
        val state = state(context)
        val id = state.getString(KEY_ID, null)
        if (boot.id != null && id != null) return boot.id == id
        val count = state.getInt(KEY_COUNT, -1)
        if (boot.count >= 0 && count >= 0) return boot.count == count
        if (boot.count >= 0 && context.getSharedPreferences(SettingsProvider.PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(LEGACY_ATTEMPT, -1) == boot.count) return true
        val wall = state.getLong(KEY_WALL, 0L)
        return wall > 0L && abs(boot.wall - wall) <= WALL_TOLERANCE_MS
    }
    /** Records the single decision for this boot and drops every pending listener or alarm. */
    fun finish(context: Context, boot: Boot, result: String): Boolean {
        cancel(context)
        handledInProcess = true
        val saved = state(context).edit().putString(KEY_ID, boot.id).putInt(KEY_COUNT, boot.count).putLong(KEY_WALL, boot.wall)
            .putString(KEY_RESULT, result).putLong(KEY_AT, uptime()).commit()
        log("$result (${uptime() / 1000} s after boot)")
        return saved
    }
    fun record(context: Context, result: String) {
        state(context).edit().putString(KEY_RESULT, result).apply()
        log(result)
    }
    fun describe(context: Context): String {
        val state = state(context)
        return "enabled=${enabled(context)}, systemUser=${systemUser(context)}, uptime=${uptime() / 1000} s, " +
            "window=${WINDOW_MS / 60_000} min, thisBootHandled=${handled(context, current(context))}\n" +
            "last decision: ${state.getString(KEY_RESULT, "none")} (${state.getLong(KEY_AT, -1L) / 1000} s after that boot)"
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java)?.cancel(pending(app))
        listener?.let { try { app.unregisterReceiver(it) } catch (_: IllegalArgumentException) {} }
        listener = null
        expiry?.let(main::removeCallbacks)
        expiry = null
    }
    /** Waits for the first unlock, but never past the window. */
    fun watchUnlock(context: Context) {
        val app = context.applicationContext
        val left = WINDOW_MS - uptime()
        if (left <= 0L) { finish(app, current(app), "Skipped: no unlock within ${WINDOW_MS / 60_000} min after boot"); return }
        if (listener == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != Intent.ACTION_USER_PRESENT) return
                    cancel(c)
                    if (inWindow()) deliverShortly(c, this)
                    else finish(c, current(c), "Skipped: first unlock came after the window")
                }
            }
            try {
                ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
                listener = receiver
            } catch (error: Exception) {
                listener = null
                Log.w(TAG, "[BootRestart] Unlock listener unavailable; relying on the fallback alarm", error)
            }
        }
        if (expiry == null) {
            val task = Runnable {
                expiry = null
                finish(app, current(app), "Skipped: no unlock within ${WINDOW_MS / 60_000} min after boot")
            }
            expiry = task
            main.postDelayed(task, left + 1_000L)
        }
        // Inexact and non-waking; a late delivery is rejected by the window check in LauncherRestartReceiver.
        app.getSystemService(AlarmManager::class.java)?.set(AlarmManager.ELAPSED_REALTIME,
            uptime() + minOf(FALLBACK_MS, left), pending(app))
    }
    fun deliverShortly(context: Context, receiver: BroadcastReceiver) {
        val result = receiver.goAsync()
        val app = context.applicationContext
        main.postDelayed({
            try { app.sendBroadcast(Intent(app, LauncherRestartReceiver::class.java)) }
            finally { result.finish() }
        }, 2_000L)
    }
}

class BootRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val uptime = BootRestart.uptime()
        if (!BootRestart.systemUser(context)) { BootRestart.log("Boot completed for a secondary user; ignored"); return }
        if (!BootRestart.enabled(context)) { BootRestart.log("Boot completed; restart is disabled"); return }
        val boot = BootRestart.current(context)
        if (BootRestart.handled(context, boot)) { BootRestart.log("Boot completed again for an already handled boot; ignored"); return }
        if (uptime > BootRestart.WINDOW_MS) {
            BootRestart.finish(context, boot, "Skipped: boot event arrived late (${uptime / 1000} s after boot)")
            return
        }
        BootRestart.log("Boot completed ${uptime / 1000} s after boot; waiting for the first unlock")
        if (BootRestart.unlocked(context)) BootRestart.deliverShortly(context, this) else BootRestart.watchUnlock(context)
    }
}

class LauncherRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BootRestart.systemUser(context)) { BootRestart.cancel(context); return }
        if (!BootRestart.enabled(context)) { BootRestart.cancel(context); BootRestart.log("Cancelled: restart is disabled"); return }
        val boot = BootRestart.current(context)
        if (BootRestart.handled(context, boot)) { BootRestart.cancel(context); return }
        if (!BootRestart.inWindow()) {
            BootRestart.finish(context, boot, "Skipped: no unlock within ${BootRestart.WINDOW_MS / 60_000} min after boot")
            return
        }
        if (!BootRestart.unlocked(context)) { BootRestart.watchUnlock(context); return }
        val target = BootRestart.homePackage(context)
        if (target == null) { BootRestart.finish(context, boot, "Skipped: default home is not the stock launcher"); return }
        if (!BootRestart.finish(context, boot, "Restarting $target after the first unlock")) {
            BootRestart.log("Could not record the attempt; restart skipped")
            return
        }
        val prefs = context.getSharedPreferences(SettingsProvider.PREFS_NAME, Context.MODE_PRIVATE)
        val showToast = prefs.getBoolean(SettingsProvider.KEY_BOOT_RESTART_TOAST, SettingsDefaults.boolean(SettingsProvider.KEY_BOOT_RESTART_TOAST))
        if (showToast) Toast.makeText(context, R.string.ui_boot_restart_message, Toast.LENGTH_LONG).show()
        val app = context.applicationContext
        val pending = goAsync()
        Thread({
            var failure: String? = null
            try {
                val process = ProcessBuilder("su", "-c", "am force-stop --user 0 $target && am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1")
                    .redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
                val finished = process.waitFor(8, TimeUnit.SECONDS)
                if (!finished) process.destroyForcibly()
                if (!finished) failure = "Restart timed out" else if (process.exitValue() != 0) failure = "Restart failed: exit ${process.exitValue()}"
            } catch (error: Exception) {
                failure = "Restart failed: ${error.javaClass.simpleName}"
                Log.e("ColorOSGlass", "[BootRestart] Launcher restart failed", error)
            } finally {
                failure?.let { BootRestart.record(app, it) }
                if (failure != null && showToast)
                    Handler(Looper.getMainLooper()).post { Toast.makeText(app, R.string.ui_boot_restart_failed, Toast.LENGTH_SHORT).show() }
                pending.finish()
            }
        }, "CherryPickBootRestart").start()
    }
}
