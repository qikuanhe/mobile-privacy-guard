package com.daweiba.privacyguard

/**
 * 建议引擎：给每个应用一个明确结论 —— 删不删？删了有什么风险？
 * 原则：没有把握就不建议删；系统核心组件一律保留。
 */
object AdviceDb {

    enum class Level(val label: String, val color: Int, val order: Int) {
        FIX("需处理", 0xFFE53935.toInt(), 0),
        UNINSTALL("建议卸载", 0xFFFF7043.toInt(), 1),
        DISABLE("可停用", 0xFFFFB74D.toInt(), 2),
        KEEP("建议保留", 0xFF81C784.toInt(), 3)
    }

    data class Advice(
        val level: Level,
        val reason: String,   // 一句话结论
        val risk: String,     // 删/停之后会怎样
        val canUninstall: Boolean, // 是否可走系统卸载（用户应用）
        val allowDisable: Boolean = true // false = 建议保留功能，只撤销权限，不给一键停用
    )

    /** 系统核心：卸载必然出问题 */
    private val NEVER = listOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.mms",
        "com.android.stk",
        "com.android.shell",
        "com.sec.imsservice",
        "com.sec.phone",
        "com.samsung.android.incallui",
        "com.sec.android.app.launcher",
        "com.samsung.android.app.telephonyui",
        "com.samsung.android.cidmanager",
        "com.samsung.android.mdm",
        "com.sec.enterprise.knox",
        "com.qualcomm.qti.services",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.samsung.android.app.galaxyregistry",
        "com.samsung.chn.apps.devicemanagement" // 已在知识库处理，双保险
    )

    /**
     * 三星/运营商预装的"功能型"组件：可停用，附影响说明。
     *
     * exactPkgs 非空 → 只按精确包名 / 命名空间前缀匹配（用于曾误判的 bixby、sticker 家族）；
     * labelHint 非空 → 额外要求应用名包含该字样，进一步收紧。
     * 其余规则沿用包名包含匹配，保持对其他机型的泛化能力。
     */
    private data class Rule(
        val key: String,
        val reason: String,
        val risk: String,
        val exactPkgs: List<String> = emptyList(),
        val labelHint: String? = null
    )

    private val DISABLE_RULES = listOf(
        // —— 精确匹配：真机（SM-F9660 国行）验证过的包名，避免误判 ——
        Rule("bixby", "三星 Bixby 语音助手", "Bixby 语音/侧键唤醒失效，不影响其他功能",
            exactPkgs = listOf(
                "com.samsung.android.bixby.agent",
                "com.samsung.android.bixby.wakeup",
                "com.samsung.android.bixbyhelper",
                "com.samsung.android.bixby.ondevice" // 覆盖各语种离线语音模型 *.zhcn / *.enus 等
            ), labelHint = "bixby"),
        Rule("bixbyvision", "Bixby Vision 图像识别框架",
            "相机内 Bixby Vision / 图像识别入口消失，拍照录像不受影响",
            exactPkgs = listOf("com.samsung.android.bixbyvision.framework")),
        Rule("bixbytouch", "即圈即搜（Bixby Touch）", "长按圈选搜索失效，不影响其他功能",
            exactPkgs = listOf("com.samsung.android.bixbytouch")),
        Rule("sticker", "Sticker Center（相机贴纸中心）", "相机贴纸功能消失",
            exactPkgs = listOf("com.samsung.android.stickercenter")),
        Rule("avatarsticker", "AR 头像贴纸素材包", "相机里的 AR 头像贴纸素材消失，不影响系统",
            exactPkgs = listOf(
                "com.samsung.android.app.camera.sticker.facearavatar.preload",
                "com.sec.android.mimage.avatarstickers"
            )),

        // —— 通用规则：包名包含匹配，未发现误判 ——
        Rule("members", "三星会员服务（营销/社区）", "无法使用三星会员活动与社区，不影响系统"),
        Rule("kids", "三星儿童模式", "儿童模式入口消失，不影响正常使用"),
        Rule("multistar", "Good Lock 多任务插件", "多窗口增强功能消失"),
        Rule("routineplus", "Good Lock 日常程序插件", "自动场景（如到家静音）失效"),
        Rule("nicecatch", "Good Lock 事件记录插件", "事件记录功能消失"),
        Rule("samsungpay", "三星支付", "NFC 刷卡/公交卡不可用（卡数据仍在三星账号）"),
        Rule("arzone", "AR 表情/贴纸", "相机 AR 功能消失，拍照录像不受影响"),
        Rule("avatarmaker", "AR 虚拟形象", "虚拟形象功能消失"),
        Rule("aremoji", "AR 表情包", "AR 表情功能消失"),
        Rule("globalgoals", "三星公益营销组件", "无任何功能影响"),
        Rule("samsungapps", "三星应用商店", "无法更新/安装三星应用，其他应用商店不受影响"),
        Rule("shealth", "三星健康", "健康数据将保留在账号，但无法在本机查看记录"),
        Rule("facebook", "预装 Facebook 存根", "仅移除预装入口，需要时可自行安装")
    )

    fun advise(r: AppRisk): Advice {
        // 1) 用户自己安装的应用 → 卸载优先（即使命中知识库）
        if (!r.isSystem) {
            val kb = r.sig?.takeIf { it.severity > 0 }
            return Advice(
                Level.UNINSTALL,
                when {
                    kb != null -> "${kb.title}，而且是你自己安装的应用"
                    r.resolutionCount > 0 -> "${r.resolutionCount} 次域名解析，且是你自己安装的应用"
                    else -> "你自己安装的应用，不需要可以删"
                },
                "删除后该应用的数据和功能一并消失；不影响系统和其他应用。",
                canUninstall = true
            )
        }
        // 2) 系统应用 + 知识库已验证的采集组件 → 停用（附该组件的专属影响说明）
        if (r.sig != null && r.sig.severity > 0) {
            return Advice(
                Level.FIX,
                "已验证的采集组件：${r.sig.title}",
                r.sig.advice,
                canUninstall = false,
                allowDisable = r.sig.allowDisable
            )
        }
        if (r.sig != null) {
            return Advice(Level.KEEP, "${r.sig.title}（正常组件）", "删除会影响相关系统功能，无需处理。", false)
        }
        // 3) 系统核心
        if (NEVER.any { r.pkg == it || r.pkg.startsWith("$it.") }) {
            return Advice(Level.KEEP, "系统核心组件", "卸载会导致系统异常甚至无法开机，务必保留。", false)
        }
        // 4) 预装功能组件（按上面定义的精确 / 宽松规则匹配）
        for (rule in DISABLE_RULES) {
            val hit = if (rule.exactPkgs.isNotEmpty()) {
                rule.exactPkgs.any { p -> r.pkg == p || r.pkg.startsWith("$p.") }
            } else {
                r.pkg.contains(rule.key, ignoreCase = true)
            }
            if (!hit) continue
            if (rule.labelHint != null && !r.label.contains(rule.labelHint, ignoreCase = true)) continue
            return Advice(Level.DISABLE, rule.reason, rule.risk, false)
        }
        // 5) 其它系统应用：单纯的域名解析次数不构成风险证据（不靠数量判定，用户明确要求过）
        //    解析次数只作为参考信息保留在详情页，不进入「需处理」分组。
        return Advice(Level.KEEP, "系统预装组件，未发现上报行为", "无需处理；系统应用不建议卸载。", false)
    }
}
