package com.fonta.dynamicnotch

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.fonta.dynamicnotch.data.JsonFiles
import com.fonta.dynamicnotch.data.SettingsStore
import com.fonta.dynamicnotch.data.ToolsStore
import com.fonta.dynamicnotch.notch.NotchState
import com.fonta.dynamicnotch.services.AudioRepo
import com.fonta.dynamicnotch.services.BrightnessRepo
import com.fonta.dynamicnotch.services.MediaRepo
import com.fonta.dynamicnotch.services.ShelfStore

class NotchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        JsonFiles.init(this)
        SettingsStore.init()
        ToolsStore.init(this)
        AudioRepo.init(this)
        BrightnessRepo.init(this)
        ShelfStore.init(this)
        MediaRepo.attach(this)
    }
}

/** Receives files from the share sheet and parks them on the notch's shelf. */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = ArrayList<Uri>()
        when (intent?.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::add)
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::addAll)
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "Only files can go on the shelf", Toast.LENGTH_SHORT).show()
        } else {
            if (!NotchState.serviceRunning) {
                Toast.makeText(this, "Added to the shelf. Turn the notch on to see it.", Toast.LENGTH_SHORT).show()
            }
            // Stay alive until the copy is done: the read permission ends with this activity.
            ShelfStore.add(uris) { finish() }
            return
        }
        finish()
    }
}
