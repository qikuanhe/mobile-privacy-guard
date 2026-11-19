package com.daweiba.privacyguard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.regex.Pattern
import java.util.zip.ZipInputStream

/**
 * 离线静态扫描：读已安装 APK 里的 dex / arsc 字符串，找内置的上报端点。
 *
 * 完全本地、不联网、不上传。为避免拖慢体验：
 *  - 只抽样系统预装应用（默认 30 个），排除核心组件与本 App；
 *  - 每个 APK 只读 classes*.dex / resources.arsc，单条上限 3 MB；
 *  - 有总时间预算（默认 10 秒），超时立即停止；
 *  - 只保留命中已知上报库或含遥测关键词的域名，其余全部丢弃。
 */
object ApkScanner {

    data class Result(
        val scanned: Int,
        val elapsedMs: Long,
        val findings: Map<String, List<String>>,
        val truncated: Boolean
    )

    private val URL_RE = Pattern.compile("https?://[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]{4,140}")
    private val DOMAIN_RE = Pattern.compile(
        "(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.){1,5}" +
            "(?:com|cn|net|org|io|xyz|top|cc|me|info|biz|ru|hk|tw|jp|kr|vip|site|online)",
        Pattern.CASE_INSENSITIVE
    )

    /** 开发框架 / 文档 / 示例域名，扫到也不算线索 */
    private val NOISE = listOf(
        "schemas.android.com", "android.com", "w3.org", "example.com", "localhost",
        "apache.org", "github.com", "google.com", "gstatic.com", "json.org",
        "xml.org", "openssl.org", "gnu.org", "mozilla.org", "squareup.com"
    )

    private val EXCLUDE = listOf(
        "android", "com.android.systemui", "com.android.settings",
        "com.daweiba.privacyguard", "moe.shizuku.privileged.api"
    )

    fun scan(ctx: Context, maxPackages: Int = 30, budgetMs: Long = 10000L): Result {
        val t0 = System.currentTimeMillis()
        val apps = try {
            ctx.packageManager.getInstalledApplications(0)
        } catch (e: Exception) {
            emptyList<ApplicationInfo>()
        }

        val candidates = apps
            .filter { ai ->
                (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    !EXCLUDE.any { ai.packageName == it || ai.packageName.startsWith("$it.") } &&
                    (ai.sourceDir?.let { File(it).canRead() } == true)
            }
            .sortedBy { it.packageName }
            .take(maxPackages)

        val findings = LinkedHashMap<String, List<String>>()
        var scanned = 0
        var truncated = false

        for (ai in candidates) {
            if (System.currentTimeMillis() - t0 > budgetMs) {
                truncated = true
                break
            }
            val hits = try {
                scanOne(File(ai.sourceDir))
            } catch (e: Throwable) {
                emptyList()
            }
            scanned++
            if (hits.isNotEmpty()) findings[ai.packageName] = hits
        }
        return Result(scanned, System.currentTimeMillis() - t0, findings, truncated)
    }

    private fun scanOne(apk: File): List<String> {
        val out = LinkedHashSet<String>()
        ZipInputStream(apk.inputStream().buffered(64 * 1024)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val name = entry.name
                val interesting = !entry.isDirectory &&
                    (name.endsWith(".dex") || name == "resources.arsc")
                if (interesting) {
                    val cap = 3 * 1024 * 1024
                    val baos = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    var total = 0
                    while (total < cap) {
                        val n = zin.read(buf)
                        if (n <= 0) break
                        val take = minOf(n, cap - total)
                        baos.write(buf, 0, take)
                        total += take
                    }
                    collect(String(baos.toByteArray(), Charsets.ISO_8859_1), out)
                    if (out.size >= 8) break
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        return out.toList().take(6)
    }

    private fun collect(text: String, out: LinkedHashSet<String>) {
        val m1 = URL_RE.matcher(text)
        while (m1.find() && out.size < 8) {
            val host = m1.group()
                .substringAfter("://")
                .substringBefore("/")
                .substringBefore(":")
                .lowercase()
            consider(host, out, "内置上报地址")
        }
        val m2 = DOMAIN_RE.matcher(text)
        while (m2.find() && out.size < 8) {
            consider(m2.group().lowercase(), out, "")
        }
    }

    private fun consider(host: String, out: LinkedHashSet<String>, prefix: String) {
        if (host.length < 5) return
        if (host.startsWith("192.") || host.startsWith("10.") || host.startsWith("127.")) return
        if (NOISE.any { host == it || host.endsWith(".$it") }) return
        val kb = DomainKb.match(host)
        val kw = Heuristics.keywordHit(host)
        if (kb == null && kw == null) return
        val label = when {
            kb != null -> "已知上报域名：${kb.title}（$host）"
            kw != null -> "遥测关键词域名：$host（$kw）"
            else -> host
        }
        out.add(if (prefix.isEmpty()) label else "$prefix：$host")
    }
}
