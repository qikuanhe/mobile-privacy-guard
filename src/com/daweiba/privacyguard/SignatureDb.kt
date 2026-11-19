package com.daweiba.privacyguard

/**
 * 已知采集组件知识库
 * 条目均来自真机审计验证（2026-11，SM-F9660 国行）或公开逆向报告。
 * severity = 0 表示"已知的正常组件"，用于消除误报；>0 会叠加到风险分。
 */
object SignatureDb {

    data class Entry(
        val pkg: String,
        val title: String,
        val severity: Int,
        val detail: String,
        val advice: String,
        val allowDisable: Boolean = true
    )

    val all: List<Entry> = listOf(
        Entry(
            "com.mobiletools.systemhelper", "中国联通 终端设备管理(DM)", 100,
            "国行手机预装的联通设备管理客户端。真机验证：持有 READ_PRIVILEGED_PHONE_STATE、PACKAGE_USAGE_STATS、QUERY_ALL_PACKAGES、开机自启，内置端点 http://dm.wo.com.cn:18080/register，上报应用清单/使用统计/IMEI/IMSI/ICCID，另内置百度定位SDK。2026-10-31 小米/联通事件同款组件。",
            "建议禁用（电脑 adb）：pm disable-user --user 0 com.mobiletools.systemhelper；或在系统设置中停用。禁用不影响通话上网。"
        ),
        Entry(
            "com.samsung.chn.apps.devicemanagement", "移动/电信 终端设备管理(DM)", 95,
            "三星国行「设备管理」组件（彩讯 Richinfo DM 平台）。真机验证：以 radio 特权组常驻运行，持有读短信权限（不可撤销），每约2小时心跳、约21小时一次网络采集，上报域名 a.fxltsbl.com / b.fxltsbl.com / zzhc.vnet.cn(电信) / typdm.tydevice.com(天翼) / android.dmyk.net，采集应用列表、IMEI/IMSI/ICCID、网络与基站信息。与一加 RegService 同一套基础设施。",
            "建议禁用（电脑 adb）：pm disable-user --user 0 com.samsung.chn.apps.devicemanagement。若使用中国运营商卡且依赖自动下发配置，可保留但知悉其采集行为。"
        ),
        Entry(
            "com.coloros.regservice", "OPPO/一加 移动DM注册组件", 95,
            "ColorOS 隐藏系统应用（/product/app/RegService，应用列表不可见）。公开逆向报告：中国移动终端设备管理客户端，上报应用列表、使用时长、启动次数、分应用流量、Wi-Fi 列表、SIM/设备标识、基站信息至 a.fxltsbl.com（广州阿里云），本地库 cmcc_dm.db。",
            "建议禁用（电脑 adb）：pm disable-user --user 0 com.coloros.regservice"
        ),
        Entry(
            "com.miui.guardprovider", "小米 安全组件(录屏上报)", 85,
            "小米系统安全组件。2026-10-31 事件披露其中含录屏上传能力（com.miui.guardprovider 录屏并上传服务器），多款小米机型预装。",
            "可尝试禁用：pm disable-user --user 0 com.miui.guardprovider（可能影响部分安全功能，自行权衡）。"
        ),
        Entry(
            "com.newcall", "中国移动 NewCallSdk(VoLTE辅助)", 60,
            "移动预装 VoLTE 通话辅助 SDK。真机验证：默认持有精确定位/通讯录/麦克风/READ_PHONE_STATE 等敏感权限。功能上用于通话辅助，但敏感权限远超需要。",
            "建议保留功能、撤销敏感权限：设置→应用→NewCallSdk→权限，撤销定位/联系人/麦克风；电脑 adb 可用 pm revoke 批量撤销。",
            allowDisable = false
        ),
        Entry(
            "com.greenpoint.android.mc10086.activity", "中国移动 营业厅App", 40,
            "移动官方营业厅客户端，用户自行安装的应用。含大量保活/常驻逻辑与营销推送。",
            "不使用可直接卸载。"
        ),
        Entry(
            "com.qualcomm.qti.services.systemhelper", "高通 modem 服务(正常)", 0,
            "高通原厂 SIM/modem 支撑服务，与联通 SystemHelper 同名但完全无关，属正常系统组件。",
            "无需处理（本条为消除误报而设）。"
        ),
        Entry(
            "com.samsung.android.app.galaxyregistry", "三星账号设备注册(正常)", 0,
            "三星账号体系的设备注册组件，用于三星账号/查找手机等功能。",
            "无需处理（本条为消除误报而设）。"
        )
    )

    fun find(pkg: String): Entry? = all.firstOrNull { it.pkg == pkg }
}
