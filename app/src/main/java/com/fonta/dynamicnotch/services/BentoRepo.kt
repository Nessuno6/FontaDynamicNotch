package com.fonta.dynamicnotch.services

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

/** One BentoBar item as BentoBar shares it. */
data class BentoItem(
    val id: String,
    val type: String,
    val title: String,
    val text: String?,
    val label: String?,
    val description: String,
    val active: Boolean,
    /** normal, accent, warn or alert. */
    val tone: String,
    val icon: Bitmap?,
    /** BentoBar's own bar would show it now (always shown, or hidden and speaking up). */
    val inBar: Boolean = true,
) {
    /** The words for a list: the bar's text, a shorter label where it has one, else the item's name. */
    val words: String get() = label ?: text ?: title
}

/**
 * BentoBar's items, read from the table BentoBar shares with other apps
 * (`content://com.meridian11.books.bentobar.items/items`, read only). Two locks are BentoBar's own:
 * Android's permission for it, which the user grants to this app, and BentoBar's switch
 * (Look › Other apps). The table holds what BentoBar's bar shows at that moment.
 */
object BentoRepo {
    const val PACKAGE = "com.meridian11.books.bentobar"
    const val PERMISSION = "com.meridian11.books.bentobar.permission.READ_BAR_ITEMS"
    private val URI: Uri = Uri.parse("content://com.meridian11.books.bentobar.items/items")
    private const val TAG = "FontaNotch"

    enum class Status { off, notInstalled, needsPermission, sharingOff, ok }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    private var watching = false
    private var enabled = false
    private val icons = HashMap<String, Pair<Int, Bitmap?>>()

    var items by mutableStateOf(listOf<BentoItem>())
        private set
    var status by mutableStateOf(Status.off)
        private set

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    fun granted(context: Context) = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Starts or stops following BentoBar, by the notch's own option. */
    fun set(context: Context, on: Boolean) {
        val c = context.applicationContext
        app = c
        enabled = on
        if (!on) {
            if (watching) runCatching { c.contentResolver.unregisterContentObserver(observer) }
            watching = false
            items = emptyList()
            status = Status.off
            return
        }
        refresh()
    }

    fun refresh() {
        val c = app ?: return
        if (!enabled) return
        // The permission may have been granted since the last look.
        if (!watching && granted(c)) {
            watching = runCatching { c.contentResolver.registerContentObserver(URI, true, observer) }.isSuccess
        }
        worker.execute {
            val (st, list) = read(c)
            main.post {
                if (!enabled) return@post   // switched off meanwhile
                status = st
                items = list
            }
        }
    }

    private fun read(c: Context): Pair<Status, List<BentoItem>> {
        if (runCatching { c.packageManager.getPackageInfo(PACKAGE, 0) }.isFailure) return Status.notInstalled to emptyList()
        if (!granted(c)) return Status.needsPermission to emptyList()
        return runCatching {
            c.contentResolver.query(URI, null, null, null, null)?.use { cur ->
                if (cur.extras?.getBoolean("enabled", true) == false) return@use Status.sharingOff to emptyList<BentoItem>()
                fun col(name: String) = cur.getColumnIndex(name)
                val id = col("id"); val type = col("type"); val title = col("title"); val text = col("text")
                val label = col("label"); val desc = col("description"); val active = col("active")
                val tone = col("tone"); val png = col("icon_png"); val inBar = col("in_bar")
                val out = ArrayList<BentoItem>()
                while (cur.moveToNext()) {
                    val key = cur.getString(id) ?: continue
                    val bytes = if (png >= 0 && !cur.isNull(png)) cur.getBlob(png) else null
                    out += BentoItem(
                        key, cur.getString(type) ?: "", cur.getString(title) ?: "",
                        if (text >= 0) cur.getString(text) else null,
                        if (label >= 0) cur.getString(label) else null,
                        (if (desc >= 0) cur.getString(desc) else null) ?: "",
                        active >= 0 && cur.getInt(active) == 1,
                        (if (tone >= 0) cur.getString(tone) else null) ?: "normal",
                        icon(key, bytes),
                        inBar < 0 || cur.getInt(inBar) == 1,
                    )
                }
                Status.ok to out
            } ?: (Status.notInstalled to emptyList())
        }.onFailure { Log.w(TAG, "BentoBar items unreadable: ${it.javaClass.simpleName}") }
            .getOrDefault(Status.needsPermission to emptyList())
    }

    /**
     * Tells BentoBar which items the notch shows itself ([hide]: its bar leaves them out and keeps them
     * up to date) and whether the notch lists every item right now ([watch]). BentoBar holds this for
     * two minutes, so it is sent again on every change and once a minute; nothing held, nothing sent.
     */
    fun claim(hide: List<String>, watch: Boolean, whole: Boolean = false) {
        val c = app ?: return
        worker.execute {
            runCatching {
                c.contentResolver.call(URI, "claim", null, android.os.Bundle().apply {
                    putStringArrayList("hide", ArrayList(hide))
                    putBoolean("watch", watch)
                    putBoolean("whole", whole)
                })
            }.onFailure { Log.w(TAG, "BentoBar refused the claim: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * What a click on [item] does in BentoBar's own bar: its popup opens under [at] (screen pixels),
     * or the item acts by itself where it has no popup.
     */
    fun open(item: BentoItem, at: android.graphics.Rect, hover: Boolean = false) {
        val c = app ?: return
        worker.execute {
            runCatching {
                c.contentResolver.call(URI, "open", item.id, android.os.Bundle().apply {
                    putInt("left", at.left); putInt("top", at.top); putInt("right", at.right); putInt("bottom", at.bottom)
                    // A pointer resting on it: only the popup (no click action), and an open one stays open.
                    putBoolean("hover", hover)
                })
            }.onFailure { Log.w(TAG, "BentoBar would not open the item: ${it.javaClass.simpleName}") }
        }
    }

    /** The notch moved on (it opened): the BentoBar popup a wing opened closes, if it's still showing. */
    fun closeMenu() {
        val c = app ?: return
        worker.execute {
            runCatching { c.contentResolver.call(URI, "close", null, null) }
                .onFailure { Log.w(TAG, "BentoBar would not close its popup: ${it.javaClass.simpleName}") }
        }
    }

    /** Decodes an item's icon once, and again only when its bytes change. */
    private fun icon(id: String, bytes: ByteArray?): Bitmap? {
        bytes ?: return null
        val hash = bytes.contentHashCode()
        icons[id]?.let { if (it.first == hash) return it.second }
        val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        icons[id] = hash to bmp
        return bmp
    }
}
