package com.daweiba.privacyguard

import java.util.Calendar

/**
 * 行为启发式：不依赖已知域名白名单，从 EventLog 已记录的 DNS 解析里找「反常」。
 *
 * 重要边界：
 *  - 输出一律是「可疑（未确认）」，不是「已确认泄露」；
 *  - 输入来自 EventLog，因此**必须先开启「泄露监控」**，否则无数据可分析；
 *  - 已命中 DomainKb 的解析归 ① 组，这里只看没命中的部分；
 *  - 用良性域名白名单 + 域名「段」匹配压低误报，触发条件较保守。
 */
object Heuristics {

    data class Finding(
        val pkg: String,
        val app: String,
        val score: Int,
        val reasons: List<String>,
        val domains: List<String>,
        val total: Int,
        val hits: Int
    )

    /** 不参与可疑判定的核心组件 */
    private val EXCLUDE = listOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone",
        "com.google.android.gms", "com.google.android.gsf",
        "com.daweiba.privacyguard", "moe.shizuku.privileged.api"
    )

    /** 常见正常服务域名，命中即视为良性 */
    private val BENIGN = listOf(
        "google.com", "gstatic.com", "googleapis.com", "googlevideo.com",
        "android.com", "apple.com", "icloud.com", "mzstatic.com",
        "samsung.com", "samsungapps.com", "samsungcloud.com",
        "microsoft.com", "live.com", "windowsupdate.com",
        "akamaiedge.net", "akamai.net", "cloudflare.com", "cloudfront.net",
        "amazonaws.com", "azureedge.net", "fastly.net",
        "qq.com", "weixin.qq.com", "tencent.com", "alipay.com", "taobao.com",
        "baidu.com", "bdstatic.com", "mi.com", "xiaomi.com",
        "whatsapp.net", "facebook.com", "fbcdn.net",
        "doubleclick.net", "googlesyndication.com"
    )

    /** 按域名「段」匹配，避免 static / login / catalog 之类误伤 */
    private val SUSPECT_WORDS = listOf(
        "track", "tracker", "tracking", "telemetry", "analytics", "beacon",
        "collect", "collector", "report", "reporter", "stat", "stats",
        "metrics", "deviceinfo", "applist", "upload", "dmp", "sdklog", "eventlog"
    )

    fun isBenign(domain: String): Boolean {
        val d = domain.lowercase().trimEnd('.')
        return BENIGN.any { d == it || d.endsWith(".$it") }
    }

    fun keywordHit(domain: String): String? {
        val segs = domain.lowercase().trimEnd('.').split('.')
        for (s in segs) {
            for (w in SUSPECT_WORDS) {
                if (s == w || s.startsWith("$w-") || s.endsWith("-$w")) return w
            }
        }
        return null
    }

    private fun excluded(pkg: String): Boolean =
        EXCLUDE.any { pkg == it || pkg.startsWith("$it.") }

    fun analyze(): Map<String, Finding> {
        val events = EventLog.snapshot()
        if (events.isEmpty()) return emptyMap()

        val byPkg = LinkedHashMap<String, MutableList<EventLog.Ev>>()
        for (e in events) {
            val p = e.pkg ?: continue
            if (excluded(p) || e.domain.isBlank()) continue
            byPkg.getOrPut(p) { ArrayList() }.add(e)
        }

        val out = ArrayList<Finding>()
        for ((pkg, list) in byPkg) {
            val unknown = list.filter { it.kb == null && !isBenign(it.domain) }
            if (unknown.isEmpty()) continue

            val reasons = ArrayList<String>()
            val flagged = LinkedHashSet<String>()
            var score = 0

            // 规则 1：域名段里带遥测关键词（强证据）
            val kw = LinkedHashMap<String, String>()
            for (e in unknown) {
                val d = e.domain.lowercase().trimEnd('.')
                val w = keywordHit(d) ?: continue
                kw[d] = w
                flagged.add(d)
            }
            if (kw.isNotEmpty()) {
                score += 4
                reasons.add("域名含遥测关键词（${kw.values.distinct().take(3).joinToString("/")}），共 ${kw.size} 个")
            }

            // 规则 2：同一域名重复解析（心跳）
            val freq = HashMap<String, Int>()
            for (e in unknown) {
                val d = e.domain.lowercase().trimEnd('.')
                freq[d] = (freq[d] ?: 0) + 1
            }
            val heartbeats = freq.filter { it.value >= 6 }
            if (heartbeats.isNotEmpty()) {
                score += 2
                reasons.add("同一域名重复解析 ≥6 次（疑似心跳）：${heartbeats.keys.take(2).joinToString()}")
                flagged.addAll(heartbeats.keys)
            }

            // 规则 3：解析的不同域名数量
            val distinct = freq.keys.toList()
            if (distinct.size >= 8) {
                score += 2
                reasons.add("解析了 ${distinct.size} 个不同域名")
            }

            // 规则 4：凌晨 0–6 点仍有解析
            val cal = Calendar.getInstance()
            val night = unknown.count {
                cal.timeInMillis = it.t
                cal.get(Calendar.HOUR_OF_DAY) < 6
            }
            if (night >= 5) {
                score += 2
                reasons.add("凌晨 0–6 点仍有 $night 次解析（后台活动）")
            }

            // 触发：有关键词证据，或「心跳 + 域名多 + 夜间」三条同时成立
            val combo = heartbeats.isNotEmpty() && distinct.size >= 8 && night >= 5
            if (kw.isEmpty() && !combo) continue
            if (score < 4) continue

            out.add(
                Finding(
                    pkg = pkg,
                    app = list.first().app,
                    score = score,
                    reasons = reasons,
                    domains = flagged.take(5).ifEmpty { distinct.take(5) },
                    total = list.size,
                    hits = list.count { it.kb != null }
                )
            )
        }
        return out.sortedByDescending { it.score }.associateBy { it.pkg }
    }
}
