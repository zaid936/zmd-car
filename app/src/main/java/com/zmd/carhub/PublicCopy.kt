package com.zmd.carhub

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Puts a copy of every app file the hub installs (the bundled CarPlay / Android Auto apps and
 * the store's APKs) in the screen's public Download folder, under «ZMD Car», so the user can see
 * it in any file manager and install it by hand. That matters on screens where one installer
 * fails (2026-11-18: Files by Google abandoned its own install session on the car, while the
 * system file manager installed the same file fine).
 *
 * The hub still installs from its private copy as before; this copy is extra. Android 10+ only
 * (MediaStore Downloads, no storage permission needed). Older screens just skip it.
 */
object PublicCopy {
    const val FOLDER = "ZMD Car"
    private const val TAG = "ZmdPublicCopy"
    private const val APK = "application/vnd.android.package-archive"

    /** Saves [src] as Download/ZMD Car/[name], replacing our older copy. Blocking: call off the main thread. */
    @Synchronized // two quick taps must not leave "name (1).apk" duplicates
    fun save(ctx: Context, src: File, name: String = src.name): Boolean {
        if (Build.VERSION.SDK_INT < 29 || !src.isFile) return false
        return runCatching {
            val cr = ctx.contentResolver
            val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val rel = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/"
            val where = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
            val args = arrayOf(name, rel)
            // Only our own entries are visible here. Same name and size: already there.
            var same = false
            cr.query(coll, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE), where, args, null)?.use { c ->
                while (c.moveToNext()) if (c.getLong(1) == src.length()) same = true
            }
            if (same) return true
            cr.delete(coll, where, args)
            val uri = cr.insert(coll, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, APK)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: return false
            val ok = runCatching {
                cr.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } != null
            }.getOrDefault(false)
            if (!ok) {
                cr.delete(uri, null, null)
                return false
            }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Log.i(TAG, "saved $rel$name (${src.length()} bytes)")
            true
        }.onFailure { Log.w(TAG, "copy of ${src.name} failed", it) }.getOrDefault(false)
    }

    /** [save] of an APK on a background thread, named after the app inside it (see [apkName]). */
    fun saveApkLater(ctx: Context, src: File) {
        val app = ctx.applicationContext
        Thread({ save(app, src, apkName(app, src, src.name)) }, "zmd-public-copy").start()
    }

    /** A readable file name from the APK itself, e.g. "ZMD Auto 0.3.12-zmd.1.apk"; [fallback] if it can't be read. */
    fun apkName(ctx: Context, apk: File, fallback: String): String = runCatching {
        val info = ctx.packageManager.getPackageArchiveInfo(apk.path, 0) ?: return fallback
        info.applicationInfo?.let { ai -> ai.sourceDir = apk.path; ai.publicSourceDir = apk.path }
        val label = info.applicationInfo?.loadLabel(ctx.packageManager)?.toString()?.takeIf { it.isNotBlank() }
            ?: return fallback
        val ver = info.versionName?.takeIf { it.isNotBlank() }
        clean(if (ver != null) "$label $ver.apk" else "$label.apk")
    }.getOrDefault(fallback)

    /** Characters file managers dislike are dropped. */
    private fun clean(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
}
