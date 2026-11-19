package com.daweiba.privacyguard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager

/**
 * 单个应用的风险画像。
 * 注意：权限请求只是"参考信息"；真正的严重级判定来自
 * ① 知识库命中（已验证的采集组件）② 实时泄露事件（DNS 监控捕获的上报）。
 */
data class AppRisk(
    val pkg: String,
    val label: String,
    val versionName: String,
    val isSystem: Boolean,
    val enabled: Boolean,
    val installer: String,
    val targetSdk: Int,
    val requested: List<String>,
    val permScore: Int,
    val resolutionCount: Int,
    val sig: SignatureDb.Entry?
) {
    /** 分类等级：0=采集组件命中 1=检测到上报 2=仅权限参考 */
    val rank: Int
        get() = when {
            sig != null && sig.severity > 0 -> 0
            resolutionCount > 0 -> 1
            else -> 2
        }

    val permBand: String
        get() = when {
            permScore >= 60 -> "权限密集"
            permScore >= 30 -> "权限中等"
            else -> "正常范围"
        }

    val permColor: Int
        get() = when {
            permScore >= 60 -> 0xFFFF7043.toInt()
            permScore >= 30 -> 0xFFFFD54F.toInt()
            else -> 0xFF81C784.toInt()
        }

    val enabledTxt: String get() = if (enabled) "启用中" else "已停用"
}

object Scanner {

    fun scan(ctx: Context): List<AppRisk> {
        val pm = ctx.packageManager
        val infos: List<PackageInfo> = try {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) {
            emptyList()
        }
        val out = ArrayList<AppRisk>(infos.size)
        for (pi in infos) {
            val ai: ApplicationInfo = pi.applicationInfo ?: continue
            val label = try {
                pm.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                pi.packageName
            }
            val perms: List<String> = try {
                pi.requestedPermissions?.mapNotNull { it } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            // 安装来源查询是逐包 IPC，670 包会拖慢到 20 秒以上；界面已不展示，跳过
            val installer = if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) "系统预装" else "用户安装"
            val sig = SignatureDb.find(pi.packageName)
            val (permScore, reasons) = RiskModel.score(perms)
            out.add(
                AppRisk(
                    pkg = pi.packageName,
                    label = label,
                    versionName = pi.versionName ?: "?",
                    isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    enabled = ai.enabled,
                    installer = installer,
                    targetSdk = ai.targetSdkVersion,
                    requested = perms,
                    permScore = permScore,
                    resolutionCount = EventLog.countForPkg(pi.packageName),
                    sig = sig
                )
            )
        }
        val sorted = out.sortedWith(
            compareByDescending<AppRisk> { it.rank.inv() }
                .thenByDescending { it.sig?.severity ?: 0 }
                .thenByDescending { it.resolutionCount }
                .thenByDescending { it.permScore }
                .thenBy { it.label }
        )
        // 行为启发式：基于本次监控到的 DNS 解析，找出「没命中已知库但反常」的应用
        Session.suspicions = Heuristics.analyze()
        return sorted
    }

    fun scanOne(ctx: Context, pkg: String): AppRisk? {
        val pm = ctx.packageManager
        val pi: PackageInfo = try {
            pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) {
            return null
        }
        val ai: ApplicationInfo = pi.applicationInfo ?: return null
        val label = try {
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            pkg
        }
        val perms: List<String> = try {
            pi.requestedPermissions?.mapNotNull { it } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val sig = SignatureDb.find(pkg)
        val (permScore, reasons) = RiskModel.score(perms)
        return AppRisk(
            pkg = pkg, label = label, versionName = pi.versionName ?: "?",
            isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            enabled = ai.enabled,
            installer = "—", targetSdk = ai.targetSdkVersion,
            requested = perms, permScore = permScore,
            resolutionCount = EventLog.countForPkg(pkg),
            sig = sig
        )
    }

    fun summary(results: List<AppRisk>): String {
        if (results.isEmpty()) return "尚未扫描"
        val kbHits = results.count { it.rank == 0 }
        val leakers = results.count { it.rank == 1 }
        val sb = StringBuilder()
        sb.append("共 ${results.size} 个应用\n")
        if (kbHits > 0) sb.append("采集组件命中 $kbHits 个（${results.count { it.rank == 0 && !it.enabled }} 个已停用）\n")
        if (leakers > 0) sb.append("检测到实时上报 $leakers 个应用\n")
        val kbEvents = EventLog.kbHits().size
        if (EventLog.totalResolutions > 0) {
            sb.append("DNS 监控：已分析 ${EventLog.totalResolutions} 次解析")
            if (kbEvents > 0) sb.append("，命中已知上报域名 $kbEvents 次")
        } else {
            sb.append("权限参考：密集 ${results.count { it.permScore >= 60 }} · 中等 ${results.count { it.permScore in 30..59 }} · 正常 ${results.count { it.permScore < 30 }}")
        }
        return sb.toString().trimEnd()
    }
}

/** 权限组合参考评分（不代表泄露，只提示"能拿到的数据面"） */
object RiskModel {

    private val W = mapOf(
        "android.permission.INTERNET" to 10,
        "android.permission.ACCESS_FINE_LOCATION" to 18,
        "android.permission.ACCESS_COARSE_LOCATION" to 10,
        "android.permission.ACCESS_BACKGROUND_LOCATION" to 22,
        "android.permission.READ_CONTACTS" to 18,
        "android.permission.WRITE_CONTACTS" to 14,
        "android.permission.READ_SMS" to 22,
        "android.permission.RECEIVE_SMS" to 16,
        "android.permission.READ_CALL_LOG" to 22,
        "android.permission.WRITE_CALL_LOG" to 16,
        "android.permission.READ_PHONE_STATE" to 12,
        "android.permission.CAMERA" to 10,
        "android.permission.RECORD_AUDIO" to 10,
        "android.permission.READ_EXTERNAL_STORAGE" to 8,
        "android.permission.WRITE_EXTERNAL_STORAGE" to 8,
        "android.permission.MANAGE_EXTERNAL_STORAGE" to 20,
        "android.permission.PACKAGE_USAGE_STATS" to 25,
        "android.permission.SYSTEM_ALERT_WINDOW" to 14,
        "android.permission.QUERY_ALL_PACKAGES" to 14,
        "android.permission.REQUEST_INSTALL_PACKAGES" to 12,
        "android.permission.BODY_SENSORS" to 12
    )

    fun score(perms: List<String>): Pair<Int, List<String>> {
        val set = perms.toSet()
        var s = 0
        val reasons = ArrayList<String>()
        for (p in set) {
            val w = W[p]
            if (w != null) {
                s += w
                reasons.add("${p.substringAfterLast('.')}  +$w")
            }
        }
        val net = "android.permission.INTERNET" in set
        val fineLoc = "android.permission.ACCESS_FINE_LOCATION" in set
        val usage = "android.permission.PACKAGE_USAGE_STATS" in set
        val contacts = "android.permission.READ_CONTACTS" in set
        val sms = "android.permission.READ_SMS" in set
        val callLog = "android.permission.READ_CALL_LOG" in set
        if (net && (fineLoc || usage || contacts || sms || callLog)) {
            s += 20; reasons.add("联网 × 敏感信息组合  +20")
        }
        if (usage && "android.permission.QUERY_ALL_PACKAGES" in set) {
            s += 15; reasons.add("应用清单 × 使用统计组合  +15")
        }
        return s to reasons
    }
}
