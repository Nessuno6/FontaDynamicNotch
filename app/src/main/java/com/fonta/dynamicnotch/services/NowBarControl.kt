package com.fonta.dynamicnotch.services

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Turns Samsung's own live-notification surfaces off and on, so items do not show twice:
 *  - the chip in the status bar: Settings.Secure `quickstar_indicator_hide_ongoing_chip`
 *    (despite the name, SystemUI reads it as "show the chip": 0 hides it);
 *  - the Now Bar on the lock screen: Settings.System `now_bar_enabled`.
 *
 * Uses direct Settings writes where permitted, then Shizuku's UserService (works without a PC
 * or root), then `su`, falling back to `adb` instructions if none of those are available.
 */
object NowBarControl {
    private const val TAG = "FontaNotch"
    private const val CHIP = "quickstar_indicator_hide_ongoing_chip"
    private const val NOW_BAR = "now_bar_enabled"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    enum class ShizukuState { Unavailable, NeedsPermission, PermissionDenied, Ready }

    data class Result(
        val chipOk: Boolean,
        val barOk: Boolean,
        val problem: String?,
    )

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var cachedShell: IShellService? = null

    @Volatile
    private var pendingBind: CompletableFuture<IShellService?>? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = binder?.let { IShellService.Stub.asInterface(it) }
            cachedShell = service
            pendingBind?.complete(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cachedShell = null
            pendingBind?.complete(null)
        }
    }

    fun isShizukuInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun shizukuState(): ShizukuState = runCatching {
        when {
            !Shizuku.pingBinder() -> ShizukuState.Unavailable
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.Ready
            Shizuku.shouldShowRequestPermissionRationale() -> ShizukuState.PermissionDenied
            else -> ShizukuState.NeedsPermission
        }
    }.getOrDefault(ShizukuState.Unavailable)

    fun openShizuku(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    private fun granted(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun put(what: String, block: () -> Boolean): Boolean =
        runCatching(block).onFailure { Log.w(TAG, "$what refused: ${it.javaClass.simpleName}") }.getOrDefault(false)

    private fun bindShizukuShell(app: Context): IShellService? {
        if (Looper.myLooper() == Looper.getMainLooper() || shizukuState() != ShizukuState.Ready) {
            cachedShell = null
            return null
        }
        cachedShell?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        cachedShell = null

        val version = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode.toInt()
        }.getOrDefault(1)
        val args = Shizuku.UserServiceArgs(ComponentName(app, ShellUserService::class.java))
            .daemon(false)
            .processNameSuffix("shizuku")
            .tag("shell")
            .debuggable(false)
            .version(version)

        val future = CompletableFuture<IShellService?>()
        pendingBind = future
        val bound = runCatching { Shizuku.bindUserService(args, serviceConnection); true }
            .onFailure { Log.w(TAG, "Shizuku bindUserService failed: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
        if (!bound) {
            pendingBind = null
            return null
        }
        val service = runCatching { future.get(5, TimeUnit.SECONDS) }.getOrNull()
        pendingBind = null
        if (service == null) {
            cachedShell = null
            runCatching { Shizuku.unbindUserService(args, serviceConnection, false) }
        }
        return service
    }

    private fun runViaShizuku(app: Context, command: String): Boolean {
        repeat(2) { attempt ->
            val service = bindShizukuShell(app) ?: return false
            val call = runCatching { service.run(command) }
            if (call.isSuccess) return call.getOrDefault(false)
            Log.w(TAG, "Shizuku shell call failed (attempt ${attempt + 1}): ${call.exceptionOrNull()?.javaClass?.simpleName}")
            cachedShell = null
        }
        return false
    }

    private fun helpText(app: Context, state: ShizukuState, hide: Boolean, chipOnly: Boolean): String {
        val show = if (hide) 0 else 1
        if (chipOnly) {
            val stateStr = if (hide) "hidden" else "back"
            val shizukuHint = when (state) {
                ShizukuState.PermissionDenied -> "Allow access in Shizuku › Authorized applications, "
                ShizukuState.NeedsPermission -> "Allow Shizuku access, "
                ShizukuState.Unavailable -> if (isShizukuInstalled(app)) "Start Shizuku, " else "Use Shizuku or root, "
                ShizukuState.Ready -> "Use Shizuku or root, "
            }
            return "The status-bar chip is $stateStr. To also control the lock-screen Now Bar: " +
                "${shizukuHint}switch it in Settings › Lock screen and AOD › Now bar, or run: adb shell settings put system $NOW_BAR $show"
        }
        return when (state) {
            ShizukuState.PermissionDenied ->
                "Shizuku permission was denied. Allow FontaDynamicNotch in Shizuku › Authorized applications, use root, or run: adb shell pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS"
            ShizukuState.Unavailable ->
                if (isShizukuInstalled(app)) {
                    "Start Shizuku and allow access, use root, or run a one-time command from a computer: adb shell pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS"
                } else {
                    "This needs Shizuku, root, or a one-time command from a computer: adb shell pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS"
                }
            ShizukuState.NeedsPermission, ShizukuState.Ready ->
                "This needs Shizuku, root, or a one-time command from a computer: adb shell pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS"
        }
    }

    /**
     * Hides or restores the system's Now Bar and chip. Set [skipSu] when callers want to prompt
     * for Shizuku authorization before falling back to `su`.
     */
    fun apply(
        context: Context,
        hide: Boolean,
        skipSu: Boolean = false,
        done: (Result) -> Unit,
    ) {
        val app = context.applicationContext
        val show = if (hide) 0 else 1
        worker.execute {
            // With WRITE_SECURE_SETTINGS the chip's switch can be written directly; the lock screen's
            // Now Bar setting requires shell (Shizuku / adb) or root (`su`).
            var chip = granted(app) && put("chip switch") { Settings.Secure.putInt(app.contentResolver, CHIP, show) }
            var bar = put("Now Bar switch") { Settings.System.putInt(app.contentResolver, NOW_BAR, show) }
            if (!chip || !bar) {
                val exec: ((String) -> Boolean)? = when {
                    shizukuState() == ShizukuState.Ready -> { cmd -> runViaShizuku(app, cmd) || (!skipSu && runShellCommand("su", cmd)) }
                    !skipSu -> { cmd -> runShellCommand("su", cmd) }
                    else -> null
                }
                if (exec != null) {
                    if (!granted(app)) exec("pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS")
                    if (!chip) chip = exec("settings put secure $CHIP $show")
                    if (!bar) bar = exec("settings put system $NOW_BAR $show")
                }
            }
            val problem = when {
                chip && bar -> null
                chip -> helpText(app, shizukuState(), hide, chipOnly = true)
                else -> helpText(app, shizukuState(), hide, chipOnly = false)
            }
            main.post { done(Result(chip, bar, problem)) }
        }
    }
}
