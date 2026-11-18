package com.zmd.carhub

import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads for the private store. https only, except plain http to a private/local address. */
object Net {
    fun allowed(u: String): Boolean {
        val url = runCatching { URL(u) }.getOrNull() ?: return false
        if (url.protocol == "https") return true
        if (url.protocol != "http") return false
        val h = url.host.lowercase()
        if (h == "localhost" || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")) return true
        val second = Regex("^172\\.(\\d+)\\.").find(h)?.groupValues?.get(1)?.toIntOrNull()
        return second != null && second in 16..31
    }

    private fun open(u: String): HttpURLConnection {
        if (!allowed(u)) throw IOException("الرابط لازم يبدي بـ https")
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        // Ask for a fresh copy so a newly published store list isn't hidden by a CDN cache.
        c.setRequestProperty("Cache-Control", "no-cache")
        c.setRequestProperty("Pragma", "no-cache")
        return c
    }

    fun getText(u: String, maxBytes: Int = 1 shl 20): String {
        val c = open(u)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("ملف القائمة أكبر من اللازم")
                }
                return out.toString("UTF-8")
            }
        } finally {
            c.disconnect()
        }
    }

    /** Saves to [dest] (via a .part file) and returns the SHA-256 of what was downloaded, in hex. */
    fun download(u: String, dest: File, progress: (done: Long, total: Long) -> Unit): String {
        val c = open(u)
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(65536)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - last > 250) {
                            last = now
                            progress(done, total)
                        }
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("ما انحفظ الملف")
            return md.digest().joinToString("") { "%02x".format(it) }
        } finally {
            tmp.delete()
            c.disconnect()
        }
    }
}
