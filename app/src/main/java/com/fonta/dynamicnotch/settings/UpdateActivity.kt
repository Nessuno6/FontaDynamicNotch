package com.fonta.dynamicnotch.settings

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates the app from inside it: Android's "Install unknown apps" permission first (asked only now, when the user
 * chose to update), then the release's APK is downloaded and handed to Android's package installer, which asks
 * the user to confirm as always. Started with [EXTRA_APK] (the release's download address) and [EXTRA_VERSION].
 */
class UpdateActivity : ComponentActivity() {
    private var stage by mutableStateOf(Stage.ASKING)
    private var progress by mutableFloatStateOf(0f)
    private var message by mutableStateOf("")
    private val apk get() = intent.getStringExtra(EXTRA_APK).orEmpty()
    private val version get() = intent.getStringExtra(EXTRA_VERSION).orEmpty()

    private enum class Stage { ASKING, NEED_PERMISSION, DOWNLOADING, INSTALLING, FAILED }

    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) download() else stage = Stage.NEED_PERMISSION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AlertDialog(
                    onDismissRequest = { if (stage != Stage.DOWNLOADING && stage != Stage.INSTALLING) finish() },
                    title = { Text("Update to $version") },
                    text = {
                        Column {
                            when (stage) {
                                Stage.ASKING -> Text("Preparing…")
                                Stage.NEED_PERMISSION -> Text(
                                    "To update from inside the app, Android needs you to allow it to install apps. " +
                                        "Turn on \"Allow from this source\", then come back.",
                                )
                                Stage.DOWNLOADING -> {
                                    Text("Downloading…")
                                    Spacer(Modifier.height(12.dp))
                                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.padding(vertical = 4.dp))
                                }
                                Stage.INSTALLING -> Text("Installing. Android will ask you to confirm.")
                                Stage.FAILED -> Text(message)
                            }
                        }
                    },
                    confirmButton = {
                        when (stage) {
                            Stage.NEED_PERMISSION -> TextButton({ askPermission() }) { Text("Allow") }
                            Stage.FAILED -> TextButton({ start() }) { Text("Try again") }
                            else -> {}
                        }
                    },
                    dismissButton = {
                        if (stage != Stage.DOWNLOADING && stage != Stage.INSTALLING) TextButton({ finish() }) { Text("Cancel") }
                    },
                )
            }
        }
        if (savedInstanceState == null) start()
    }

    private fun start() {
        if (apk.isEmpty()) { fail("This release has no APK to download."); return }
        if (packageManager.canRequestPackageInstalls()) download() else stage = Stage.NEED_PERMISSION
    }

    private fun askPermission() {
        permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
    }

    private fun download() {
        stage = Stage.DOWNLOADING
        progress = 0f
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
                        if (total > 0) runOnUiThread { progress = done.toFloat() / total }
                    }
                }
            }
            return total < 0 || done == total
        } finally {
            c.disconnect()
        }
    }

    private fun install(file: File) {
        stage = Stage.INSTALLING
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
        if (intent.action != ACTION_STATUS) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) startActivity(confirm) else fail("Android didn't ask to confirm the installation.")
            }
            PackageInstaller.STATUS_SUCCESS -> finish()
            PackageInstaller.STATUS_FAILURE_ABORTED -> finish()
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                fail("This update is signed differently from the installed app, so Android won't install it over it.")
            else -> fail(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { "Installation failed: $it" } ?: "Installation failed.")
        }
    }

    private fun fail(text: String) {
        message = text
        stage = Stage.FAILED
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
