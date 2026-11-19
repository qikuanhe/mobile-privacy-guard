package com.daweiba.privacyguard

/** 扫描结果的会话缓存（避免 Intent 传大对象） */
object Session {
    @Volatile var results: List<AppRisk> = emptyList()
    @Volatile var scannedAt: Long = 0L

    /** 行为启发式：pkg -> 可疑外联判定（只在开启泄露监控后才有数据） */
    @Volatile var suspicions: Map<String, Heuristics.Finding> = emptyMap()

    /** 离线静态扫描：pkg -> APK 包内发现的上报端点线索 */
    @Volatile var apkFindings: Map<String, List<String>> = emptyMap()
    @Volatile var apkScanDone: Boolean = false

    /** 完整结果只存内存（不落盘），供首页回到前台时补显示结果对话框 */
    @Volatile var apkResult: ApkScanner.Result? = null
    @Volatile var apkResultShown: Boolean = false
}
