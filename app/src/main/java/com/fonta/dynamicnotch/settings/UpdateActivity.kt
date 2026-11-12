package com.fonta.dynamicnotch.settings

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates the app from inside it: Android's "Install unknown apps" permission first (asked only now, when the user
 * chose to update), then the release's APK is downloaded and handed to Android's package installer. Every prompt
 * is the system's own dialog in the device's theme, so on Samsung it looks like the rest of One UI. Started with
 * [EXTRA_APK] (the release's download address) and [EXTRA_VERSION].
 */
class UpdateActivity : Activity() {
    private var stage = Stage.DOWNLOADING
    private var failure = ""
    private var dialog: AlertDialog? = null
    private lateinit var bar: ProgressBar
    private val apk get() = intent.getStringExtra(EXTRA_APK).orEmpty()
    private val version get() = intent.getStringExtra(EXTRA_VERSION).orEmpty()

    private enum class Stage { NEED_PERMISSION, DOWNLOADING, INSTALLING, FAILED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The installer's answer can start this screen afresh (updating the app ends the old process): an answer to
        // handle, not a new update.
        if (intent.action == ACTION_STATUS) { show(); handleStatus(intent); return }
        // Opened with nothing to update: One UI reopens the screen that was on top after an app updates, without its
        // details. That's this screen coming back after the update it just did: say so, and close.
        if (intent.getStringExtra(EXTRA_APK) == null) {
            if (justUpdated()) Toast.makeText(this, "Dynamic Notch is updated", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        show()
        if (savedInstanceState == null) start()
    }

    /** The one dialog, in the device's own theme (One UI's on Samsung); [render] changes what it says. */
    private fun show() {
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, 0, pad, 0); addView(bar) }
        dialog = AlertDialog.Builder(this)
            .setTitle("Update to $version")
            .setMessage(" ")
            .setView(box)
            .setPositiveButton(" ", null)
            .setNegativeButton(android.R.string.cancel, null)
            .setOnCancelListener { finish() }
            .create().apply {
                setCanceledOnTouchOutside(false)
                show()
                getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    when (stage) {
                        Stage.NEED_PERMISSION -> askPermission()
                        Stage.FAILED -> start()
                        else -> {}
                    }
                }
                getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener { finish() }
            }
        render()
    }

    private fun render() {
        val d = dialog ?: return
        val busy = stage == Stage.DOWNLOADING || stage == Stage.INSTALLING
        d.setMessage(when (stage) {
            Stage.NEED_PERMISSION -> "To update from inside the app, Android needs you to allow it to install apps. " +
                "Turn on \"Allow from this source\", then come back."
            Stage.DOWNLOADING -> "Downloading…"
            Stage.INSTALLING -> "Installing…"
            Stage.FAILED -> failure
        })
        bar.visibility = if (stage == Stage.DOWNLOADING) View.VISIBLE else View.GONE
        d.setCancelable(!busy)
        d.getButton(DialogInterface.BUTTON_POSITIVE)?.apply {
            visibility = if (stage == Stage.NEED_PERMISSION || stage == Stage.FAILED) View.VISIBLE else View.GONE
            text = if (stage == Stage.NEED_PERMISSION) "Allow" else "Try again"
        }
        d.getButton(DialogInterface.BUTTON_NEGATIVE)?.visibility = if (busy) View.GONE else View.VISIBLE
    }

    private fun go(next: Stage) {
        stage = next
        render()
    }

    private fun start() {
        if (apk.isEmpty()) { fail("This release has no APK to download."); return }
        if (packageManager.canRequestPackageInstalls()) download() else go(Stage.NEED_PERMISSION)
    }

    /** Back from Android's setting: on with the update if the user allowed it. */
    override fun onResume() {
        super.onResume()
        if (stage == Stage.NEED_PERMISSION && packageManager.canRequestPackageInstalls()) download()
    }

    private fun justUpdated(): Boolean = runCatching {
        System.currentTimeMillis() - packageManager.getPackageInfo(packageName, 0).lastUpdateTime < 2 * 60_000
    }.getOrDefault(false)

    private fun askPermission() {
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
    }

    private fun download() {
        bar.progress = 0
        go(Stage.DOWNLOADING)
        Thread({
            val file = File(cacheDir, "update.apk")
            val ok = runCatching { fetch(apk, file) }.getOrDefault(false)
            runOnUiThread { if (ok) install(file) else fail("The download didn't finish. Check your connection and try again.") }
        }, "Update download").start()
    }

    /** The APK into [into]; GitHub sends it from its storage after one redirect, which HttpURLConnection follows. */
    private fun fetch(address: String, into: File): Boolean {
        val c = URL(address).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "DynamicNotch-Android")
        try {
            if (c.responseCode != 200) return false
            val total = c.contentLengthLong.takeIf { it > 0 } ?: -1L
            var done = 0L
            c.inputStream.use { input ->
                into.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > MAX_BYTES) return false
                        if (total > 0) runOnUiThread { bar.progress = (done * 1000 / total).toInt() }
                    }
                }
            }
            return total < 0 || done == total
        } finally {
            c.disconnect()
        }
    }

    private fun install(file: File) {
        go(Stage.INSTALLING)
        runCatching {
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(packageName) }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("update.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                // The installer's answer comes back here (onNewIntent): a confirmation to show, or how it ended.
                val status = PendingIntent.getActivity(
                    this, id, Intent(this, UpdateActivity::class.java).setAction(ACTION_STATUS)
                        .putExtra(EXTRA_APK, apk).putExtra(EXTRA_VERSION, version),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(status.intentSender)
            }
        }.onFailure { fail("Android couldn't start the installation.") }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_STATUS) handleStatus(intent)
    }

    private fun handleStatus(intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) startActivity(confirm) else fail("Android didn't ask to confirm the installation.")
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(this, "Dynamic Notch is updated", Toast.LENGTH_SHORT).show()
                finish()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> finish()
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                fail("This update is signed differently from the installed app, so Android won't install it over it.")
            else -> fail(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { "Installation failed: $it" } ?: "Installation failed.")
        }
    }

    private fun fail(text: String) {
        failure = text
        go(Stage.FAILED)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_APK = "apk"
        const val EXTRA_VERSION = "version"
        private const val ACTION_STATUS = "com.fonta.dynamicnotch.UPDATE_STATUS"
        private const val MAX_BYTES = 200L * 1024 * 1024

        fun intent(context: android.content.Context, apk: String, version: String): Intent =
            Intent(context, UpdateActivity::class.java).putExtra(EXTRA_APK, apk).putExtra(EXTRA_VERSION, version)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
