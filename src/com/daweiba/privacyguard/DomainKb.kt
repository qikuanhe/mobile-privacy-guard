package com.daweiba.privacyguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 已知上报域名知识库。
 * 内置条目为真机审计 / 公开逆向验证过的上报通道；
 * 用户可通过首页「知识库更新」追加自定义条目（默认零联网，需主动填写 HTTPS 地址）。
 * match() 支持子域匹配。
 */
object DomainKb {

    data class Entry(
        val domain: String,
        val title: String,
        val note: String
    )

    private val BUILTIN: List<Entry> = listOf(
        // ---- 真机验证的运营商 DM 上报通道 ----
        Entry("fxltsbl.com", "运营商DM上报通道", "彩讯DM平台（移动/电信终端设备管理）上报服务器，SystemHelper/设备管理/RegService 等组件使用"),
        Entry("dm.wo.com.cn", "联通DM上报通道", "联通 SystemHelper 上报服务器（端口18080），上报应用清单/使用统计/IMEI等"),
        Entry("zzhc.vnet.cn", "电信增值业务平台", "中国电信增值合作平台"),
        Entry("tydevice.com", "天翼设备管理", "中国电信天翼 DM 通道"),
        Entry("dmyk.net", "移动DM平台", "移动终端设备管理（彩讯）通道"),
        // ---- 常见第三方遥测 / 推送 ----
        Entry("umeng.com", "友盟统计SDK", "第三方应用统计上报"),
        Entry("applovin.com", "AppLovin广告", "广告归因/监测"),
        Entry("doubleclick.net", "Google广告", "DoubleClick 广告监测"),
        Entry("googlesyndication.com", "Google广告", "广告投放"),
        Entry("crashlytics.com", "Crashlytics", "崩溃统计上报"),
        Entry("flurry.com", "Flurry统计", "第三方统计上报"),
        Entry("getui.com", "个推推送SDK", "个推（GeTui）消息推送服务域名，大量国内 App 内置，推送通道会携带设备标识与应用活跃信息"),
        Entry("igexin.com", "个推推送通道(IGexin)", "个推 SDK 实际长连接/注册域名（如 sdk.open.phone.igexin.com），与 getui.com 同属个推体系")
    )

    @Volatile private var custom: List<Entry> = emptyList()
    @Volatile private var customVersion: String = ""

    /** 内置 + 自定义；match() 与 UI 都读这里 */
    val entries: List<Entry> get() = BUILTIN + custom
    val builtinCount: Int get() = BUILTIN.size
    fun customCount(): Int = custom.size
    fun version(): String = if (customVersion.isEmpty()) "内置" else customVersion

    fun match(domain: String): Entry? {
        val d = domain.lowercase().trimEnd('.')
        for (e in entries) {
            if (d == e.domain || d.endsWith("." + e.domain)) return e
        }
        return null
    }

    // ============ 自定义条目：只存本地，默认空 ============
    private const val PREF = "kb"
    private const val KEY_JSON = "custom_json"
    private const val KEY_VER = "custom_version"
    private const val KEY_URL = "update_url"

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        customVersion = p.getString(KEY_VER, "") ?: ""
        custom = try {
            parseArray(JSONArray(p.getString(KEY_JSON, "[]") ?: "[]"))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseArray(arr: JSONArray): List<Entry> {
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val d = o.optString("domain", "").lowercase().trim()
            if (d.isEmpty()) continue
            out.add(Entry(d, o.optString("title", "自定义规则"), o.optString("note", "")))
        }
        return out
    }

    /** 合并一份更新，去重、忽略非法域名；返回新增条数 */
    fun applyUpdate(ctx: Context, version: String, incoming: List<Entry>): Int {
        val known = entries.map { it.domain.lowercase() }.toMutableSet()
        val merged = custom.toMutableList()
        var added = 0
        for (e in incoming) {
            val d = e.domain.lowercase().trim().trimEnd('.')
            if (!KbUpdate.isValidDomain(d) || d in known) continue
            known.add(d)
            merged.add(Entry(d, e.title, e.note))
            added++
        }
        custom = merged
        customVersion = version
        save(ctx)
        return added
    }

    fun clearCustom(ctx: Context) {
        custom = emptyList()
        customVersion = ""
        save(ctx)
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        for (e in custom) {
            arr.put(JSONObject().put("domain", e.domain).put("title", e.title).put("note", e.note))
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_JSON, arr.toString())
            .putString(KEY_VER, customVersion)
            .apply()
    }

    fun savedUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_URL, "") ?: ""

    fun saveUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url.trim()).apply()
    }
}

/**
 * 可选的知识库更新：**默认零联网**。
 * 只有用户在首页主动填入 HTTPS 地址并点「检查更新」时，才会发起一次 GET；
 * 只下载域名清单，不上传任何设备信息、应用列表或 DNS 记录。
 *
 * 托管文件格式：
 * {"version":"2026-11-19","domains":[{"domain":"example.com","title":"示例","note":"说明"}]}
 */
object KbUpdate {

    // Regex.matches() 是全匹配，无需 ^ 和 $ 锚点
    private val DOMAIN_RE = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")

    fun isValidDomain(d: String): Boolean = DOMAIN_RE.matches(d.lowercase())

    data class Payload(val version: String, val entries: List<DomainKb.Entry>)

    /** 同步执行，必须在子线程调用 */
    fun check(url: String): Payload {
        val u = url.trim()
        if (!u.startsWith("https://", ignoreCase = true)) {
            throw IllegalArgumentException("仅支持 HTTPS 地址")
        }
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parse(body)
        } finally {
            conn.disconnect()
        }
    }

    fun parse(json: String): Payload {
        val o = JSONObject(json)
        val version = o.optString("version", "").take(32)
        val arr = o.optJSONArray("domains") ?: throw IllegalArgumentException("缺少 domains 数组")
        val list = ArrayList<DomainKb.Entry>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val domain = d.optString("domain", "").lowercase().trim().trimEnd('.')
            if (!isValidDomain(domain)) continue
            list.add(
                DomainKb.Entry(
                    domain,
                    d.optString("title", "自定义规则").take(40),
                    d.optString("note", "").take(120)
                )
            )
        }
        return Payload(version, list)
    }
}
