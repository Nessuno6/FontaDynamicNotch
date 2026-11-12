package com.fonta.dynamicnotch.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Turns Samsung's own live-notification surfaces off and on, so items do not show twice:
 *  - the chip in the status bar: Settings.Secure `quickstar_indicator_hide_ongoing_chip`
 *    (despite the name, SystemUI reads it as "show the chip": 0 hides it);
 *  - the Now Bar on the lock screen: Settings.System `now_bar_enabled`.
 *
 * Both need WRITE_SECURE_SETTINGS, which an app cannot ask for. It is granted once from a computer
 * (`adb shell pm grant com.fonta.dynamicnotch android.permission.WRITE_SECURE_SETTINGS`), or by the
 * app itself where it has root.
 */
object NowBarControl {
    private const val TAG = "FontaNotch"
    private const val CHIP = "quickstar_indicator_hide_ongoing_chip"
    private const val NOW_BAR = "now_bar_enabled"
    const val HELP = "This needs root, or a one-time permission from a computer: " +
        "adb shell pm grant com.fonta.dynamicnotch android.permission.WRITE_SECURE_SETTINGS"

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private fun granted(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun su(command: String): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor(8, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    private fun put(what: String, block: () -> Boolean): Boolean =
        runCatching(block).onFailure { Log.w(TAG, "$what refused: ${it.javaClass.simpleName}") }.getOrDefault(false)

    /**
     * Hides or restores the system's Now Bar and chip. [done] gets null when both switches were set,
     * otherwise a sentence saying what could not be done.
     */
    fun apply(context: Context, hide: Boolean, done: (String?) -> Unit) {
        val app = context.applicationContext
        val show = if (hide) 0 else 1
        worker.execute {
            // With the permission the chip's switch can be written directly; the lock screen's is a
            // private system setting that only the shell (root) may write.
            var chip = granted(app) && put("chip switch") { Settings.Secure.putInt(app.contentResolver, CHIP, show) }
            var bar = put("Now Bar switch") { Settings.System.putInt(app.contentResolver, NOW_BAR, show) }
            if (!chip || !bar) {
                if (!granted(app)) su("pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS")
                if (!chip) chip = su("settings put secure $CHIP $show")
                if (!bar) bar = su("settings put system $NOW_BAR $show")
            }
            val problem = when {
                chip && bar -> null
                chip -> "The status-bar chip is ${if (hide) "hidden" else "back"}. The lock-screen Now Bar needs root: " +
                    "switch it in Settings › Lock screen and AOD › Now bar, or run: adb shell settings put system $NOW_BAR $show"
                else -> HELP
            }
            main.post { done(problem) }
        }
    }
}
