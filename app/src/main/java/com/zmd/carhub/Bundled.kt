package com.zmd.carhub

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

/** A receiver app shipped inside the hub's own APK (unchanged original file in assets/bundled). */
class BundledApp(val asset: String, val pkg: String, val title: String)

/**
 * Installs the CarPlay and Android Auto receiver apps that come inside the hub. Android does not
 * let an ordinary app install others silently, so each one goes through Android's own install
 * screen, once. After that the hub only opens them.
 */
object Bundled {
    // Android Auto is built into ZMD Car now (no ZMD Auto to install); only CarPlay comes as a
    // separate app, because DiPlay is not ours and stays unmodified.
    val apps = listOf(
        BundledApp("bundled/diplay.apk", Prefs.DEFAULT_CARPLAY, "CarPlay (DiPlay)"),
    )

    private fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "store").apply { mkdirs() }

    /**
     * Copies the APK out of assets (only when the copy is missing or a different size). Locked and
     * written to a temp file first: HubService copies in the background at start while a tap on
     * «نصّب» may ask for the same file.
     */
    @Synchronized
    fun file(ctx: Context, app: BundledApp): File? = runCatching {
        val out = File(dir(ctx), app.asset.substringAfterLast('/'))
        val size = ctx.assets.openFd(app.asset).use { it.length }
        if (!out.isFile || out.length() != size) {
            val part = File(out.path + ".part")
            ctx.assets.open(app.asset).use { input -> part.outputStream().use { input.copyTo(it) } }
            out.delete()
            if (!part.renameTo(out)) error("rename failed")
        }
        out
    }.getOrNull()

    private fun versionOf(ctx: Context, f: File): Long =
        runCatching { ctx.packageManager.getPackageArchiveInfo(f.path, 0)?.let { PackageInfoCompat.getLongVersionCode(it) } }
            .getOrNull() ?: 0L

    private fun installedVersion(ctx: Context, pkg: String): Long? =
        runCatching { PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(pkg, 0)) }.getOrNull()

    /** Bundled apps that are not installed yet, or older than the copy inside the hub. */
    fun needed(ctx: Context): List<BundledApp> = apps.filter { app ->
        val have = installedVersion(ctx, app.pkg) ?: return@filter true
        val f = file(ctx, app) ?: return@filter false
        versionOf(ctx, f) > have
    }

    /**
     * The old public DiAuto is still installed next to ZMD Auto. It answers every USB device and
     * may be the saved default for the phone, so it would grab the phone first: it has to go.
     */
    fun oldAutoLeft(ctx: Context): Boolean =
        installedVersion(ctx, Prefs.OLD_AUTO) != null && installedVersion(ctx, Prefs.DEFAULT_AUTO) != null

    /** Android's own uninstall prompt for the old DiAuto (the user confirms there). */
    fun removeOldAuto(a: Activity): Boolean = runCatching {
        a.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${Prefs.OLD_AUTO}"))); true
    }.getOrDefault(false)

    fun isUpdate(ctx: Context, app: BundledApp): Boolean = installedVersion(ctx, app.pkg) != null

    /**
     * Opens Android's install screen for [app]. Returns false when the "install apps" permission
     * is still missing; its settings page has been opened instead.
     */
    fun install(a: Activity, app: BundledApp): Boolean {
        if (!Perms.canInstallApks(a)) {
            // BYD DiLink (newer firmware) has no installer screen: the copy goes to Download/ZMD Car
            // and the driver installs it from a USB stick («Third Party Apps» folder).
            file(a, app)?.let { PublicCopy.saveApkLater(a, it) }
            noInstallerNotice(a)
            return false
        }
        if (!a.packageManager.canRequestPackageInstalls()) {
            runCatching {
                a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            }
            return false
        }
        val f = file(a, app) ?: return false
        // A visible copy in Download/ZMD Car too, for installing by hand from a file manager.
        PublicCopy.saveApkLater(a, f)
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { a.startActivity(i); true }.getOrDefault(false)
    }

    /** This screen can't install from inside apps (BYD DiLink): say how it's done there. */
    fun noInstallerNotice(a: Activity) {
        android.app.AlertDialog.Builder(a)
            .setTitle("هالشاشة ما تنصّب من داخل البرامج")
            .setMessage("شاشات BYD تنصّب البرامج من الفلاشة بس.\n\n" +
                "الملف انحفظ بمجلد Download/ZMD Car. انقله لفلاشة (FAT32) بمجلد اسمه «Third Party Apps» " +
                "وبعده رمز بلد السيارة (مثلاً Third Party Apps 964)، وركّب الفلاشة بالسيارة.")
            .setPositiveButton("تمام", null)
            .show()
    }
}
