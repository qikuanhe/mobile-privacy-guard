package com.daweiba.privacyguard

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页：只保留两个主入口 —— 一键体检 / 泄露监控开关。
 * 底部有两处轻量入口：OTA 复查提醒（自动）、知识库更新（手动，默认不联网）。
 */
class MainActivity : Activity() {

    private lateinit var monitorDot: TextView
    private lateinit var monitorText: TextView
    private lateinit var monitorBtn: Button
    private lateinit var scanBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var scanHint: TextView
    private lateinit var recentHit: TextView
    private lateinit var otaBanner: TextView
    private lateinit var kbInfo: TextView
    private lateinit var deepScan: TextView
    @Volatile private var resumed = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            if (LeakMonitorService.running) {
                updateMonitor()
                handler.postDelayed(this, 3000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DomainKb.load(this)   // 载入本地自定义域名（默认空）

        val dp = resources.displayMetrics.density
        fun dp(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        UiUtil.applySystemBarInsets(root, 20, 16)

        root.addView(TextView(this).apply {
            text = "大尾巴隐私守护"
            textSize = 26f
            setTextColor(0xFFECEFF1.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "查出谁在偷偷上传你的数据，并告诉你怎么处理"
            textSize = 13f
            setTextColor(0xFF9AA0A6.toInt())
            setPadding(0, dp(6), 0, dp(28))
        })

        // ===== OTA 复查提醒（仅系统更新且此前体检过时显示，只提醒一次） =====
        otaBanner = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFCC80.toInt())
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(0xFF3A2A16.toInt())
                cornerRadius = 12f * dp
                setStroke(dp(1), 0xFF8D6E63.toInt())
            }
            visibility = View.GONE
            setOnClickListener { startScan() }
        }
        root.addView(
            otaBanner,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }
        )

        // ===== 主操作：一键体检 =====
        scanBtn = Button(this).apply {
            text = "一键体检"
            textSize = 18f
            setTextColor(0xFF0B1B0B.toInt())
            background = GradientDrawable().apply {
                setColor(0xFF00E676.toInt())
                cornerRadius = 14f * dp
            }
            setOnClickListener { startScan() }
        }
        root.addView(
            scanBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { bottomMargin = dp(6) }
        )
        scanHint = TextView(this).apply {
            text = "扫描全部应用 · 给出删除/停用建议 · 约 5 秒"
            textSize = 12f
            setTextColor(0xFF9AA0A6.toInt())
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(scanHint)
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        root.addView(progress)

        // ===== 次要入口：泄露监控 =====
        val monitorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(0xFF1E1F22.toInt())
                cornerRadius = 12f * dp
            }
        }
        root.addView(
            monitorCard,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(22)
            }
        )
        val mHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        monitorDot = TextView(this).apply {
            text = "○"
            textSize = 16f
            setTextColor(0xFF9AA0A6.toInt())
            setPadding(0, 0, dp(8), 0)
        }
        mHead.addView(monitorDot)
        monitorText = TextView(this).apply {
            text = "泄露监控"
            textSize = 15f
            setTextColor(0xFFE8EAED.toInt())
            setTypeface(typeface, Typeface.BOLD)
        }
        mHead.addView(monitorText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        monitorBtn = Button(this).apply {
            text = "开启"
            textSize = 13f
            setOnClickListener { toggleMonitor() }
        }
        mHead.addView(monitorBtn)
        monitorCard.addView(mHead)

        monitorCard.addView(TextView(this).apply {
            text = "后台实时监控哪个应用在连哪个上报服务器（本地 VPN，仅路由 DNS，不影响上网）"
            textSize = 11f
            setTextColor(0xFF9AA0A6.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(6), 0, 0)
        })

        recentHit = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFF7043.toInt())
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
            setOnClickListener { showAllEvents() }
        }
        monitorCard.addView(recentHit)

        // ===== 底部说明 + 两个轻量入口 =====
        root.addView(TextView(this).apply {
            text = "v0.7.1 · 默认纯本地运行 · 不上传任何数据"
            textSize = 11f
            setTextColor(0xFF546E7A.toInt())
            setPadding(0, dp(26), 0, 0)
        })
        kbInfo = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF64B5F6.toInt())
            setPadding(0, dp(8), 0, 0)
            setOnClickListener { showKbDialog() }
        }
        root.addView(kbInfo)
        deepScan = TextView(this).apply {
            text = "深度扫描：查已装应用包内是否藏有上报端点 ›"
            textSize = 11f
            setTextColor(0xFF64B5F6.toInt())
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { showDeepScanDialog() }
        }
        root.addView(deepScan)

        setContentView(root)
        updateMonitor()
        checkOta()
        updateKbInfo()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateMonitor()
        updateKbInfo()
        if (LeakMonitorService.running) handler.post(refresher)
        // 深度扫描若在后台完成，结果只存内存；回到前台再补显示，避免弹窗丢在后台窗口
        maybeShowDeepResult()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        handler.removeCallbacks(refresher)
    }

    // ============ 体检 ============
    private fun startScan() {
        scanBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        scanHint.text = "正在扫描全部应用…"
        Thread {
            val r = Scanner.scan(this)
            runOnUiThread {
                Session.results = r
                Session.scannedAt = System.currentTimeMillis()
                OtaWatch.noteScan(this@MainActivity)
                otaBanner.visibility = View.GONE
                progress.visibility = View.GONE
                scanBtn.isEnabled = true
                scanHint.text = "扫描完成 · 点击上方按钮可重新体检"
                startActivity(Intent(this@MainActivity, ReportActivity::class.java))
            }
        }.start()
    }

    // ============ OTA 复查提醒 ============
    private fun checkOta() {
        val c = OtaWatch.check(this) ?: return
        val t = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(c.lastScan))
        otaBanner.text = "⚠ 检测到系统已更新（OTA），建议重新体检\n上次体检：$t · 点此重新扫描"
        otaBanner.visibility = View.VISIBLE
    }

    // ============ 深度扫描：离线静态检查 APK 包内线索 ============
    private fun showDeepScanDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("深度扫描（可选）")
            .setMessage(
                "读取最多 30 个系统预装应用的安装包，在其中查找内置的上报域名与遥测关键词。" +
                    "\n\n全程本地、不联网、不上传；约 10 秒。结果只保存在本次会话，退出即清空。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("开始扫描") { _, _ -> doDeepScan() }
            .show()
    }

    private fun doDeepScan() {
        Session.apkScanDone = false
        Session.apkResult = null
        Session.apkResultShown = false
        Session.apkFindings = emptyMap()
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle("深度扫描中…")
            .setMessage("正在读取已安装应用包内容（最多 30 个，约 10 秒）…")
            .setCancelable(false)
            .show()
        Thread {
            val r = ApkScanner.scan(this@MainActivity)
            runOnUiThread {
                try { dlg.dismiss() } catch (_: Throwable) {}
                Session.apkFindings = r.findings
                Session.apkResult = r
                Session.apkScanDone = true
                maybeShowDeepResult()
            }
        }.start()
    }

    /** 只在首页真正处于前台时弹结果；在后台时结果留在 Session，等 onResume 补显 */
    private fun maybeShowDeepResult() {
        val r = Session.apkResult ?: return
        if (Session.apkResultShown || !resumed) return
        Session.apkResultShown = true
        showDeepResult(r)
    }

    private fun showDeepResult(r: ApkScanner.Result) {
        val sb = StringBuilder()
        sb.append("已扫描 ${r.scanned} 个系统应用，耗时 ${r.elapsedMs / 1000} 秒")
        if (r.truncated) sb.append("（达到时间上限，已停止）")
        sb.append("\n\n")
        if (r.findings.isEmpty()) {
            sb.append("未发现内置上报端点。\n\n提示：这是抽样扫描，未覆盖全部应用；开启「泄露监控」可实时看到实际外联。")
        } else {
            for ((pkg, list) in r.findings) {
                sb.append("· ").append(pkg).append('\n')
                for (line in list) sb.append("    ").append(line).append('\n')
            }
            sb.append("\n以上只是包内字符串线索，不代表该应用正在上报，请结合结论页判断。")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("包内线索（${r.findings.size} 个应用）")
            .setMessage(sb)
            .setPositiveButton("知道了", null)
            .setNeutralButton("去结论页") { _, _ ->
                if (Session.results.isNotEmpty()) {
                    startActivity(Intent(this@MainActivity, ReportActivity::class.java))
                } else {
                    Toast.makeText(this, "请先做一次「一键体检」", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    // ============ 知识库更新（可选，默认零联网） ============
    private fun updateKbInfo() {
        if (!::kbInfo.isInitialized) return
        kbInfo.text = "知识库 v${DomainKb.version()} · 内置 ${DomainKb.builtinCount} 条 / 自定义 ${DomainKb.customCount()} 条 · 点此更新"
    }

    private fun showKbDialog() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val input = EditText(this).apply {
            hint = "https://你的地址/kb.json"
            setText(DomainKb.savedUrl(this@MainActivity))
            setSingleLine(true)
            textSize = 13f
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(TextView(this@MainActivity).apply {
                text = "默认不联网。只有填入 HTTPS 地址并点「检查更新」，才会下载一份域名清单；不会上传设备信息、应用列表或 DNS 记录。"
                textSize = 12f
                setTextColor(0xFF9AA0A6.toInt())
                setLineSpacing(dp(2).toFloat(), 1f)
            })
            addView(input)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("知识库更新（可选）")
            .setMessage("当前：内置 ${DomainKb.builtinCount} 条 / 自定义 ${DomainKb.customCount()} 条")
            .setView(box)
            .setNegativeButton("取消", null)
            .setNeutralButton("清除自定义") { _, _ ->
                DomainKb.clearCustom(this)
                Toast.makeText(this, "已清除自定义条目，恢复内置知识库", Toast.LENGTH_SHORT).show()
                updateKbInfo()
            }
            .setPositiveButton("检查更新") { _, _ ->
                val url = input.text.toString().trim()
                DomainKb.saveUrl(this, url)
                doKbCheck(url)
            }
            .show()
    }

    private fun doKbCheck(url: String) {
        if (url.isEmpty()) {
            Toast.makeText(this, "请先填写 HTTPS 地址", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try {
                val p = KbUpdate.check(url)
                val n = DomainKb.applyUpdate(this@MainActivity, p.version, p.entries)
                "更新完成：新增 $n 条（当前共 ${DomainKb.entries.size} 条）"
            } catch (e: Exception) {
                "检查失败：${e.message}"
            }
            runOnUiThread {
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                updateKbInfo()
            }
        }.start()
    }

    // ============ 泄露监控 ============
    private fun toggleMonitor() {
        if (LeakMonitorService.running) {
            stopService(Intent(this, LeakMonitorService::class.java))
            updateMonitor()
            return
        }
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, 100) else startMonitor()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            if (resultCode == RESULT_OK) startMonitor()
            else Toast.makeText(this, "未授予 VPN 权限，监控未启动", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startMonitor() {
        LeakMonitorService.blockEnabled =
            getSharedPreferences("monitor", Context.MODE_PRIVATE).getBoolean("block", false)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startService(Intent(this, LeakMonitorService::class.java))
        handler.postDelayed({ updateMonitor() }, 800)
        handler.postDelayed(refresher, 3000)
    }

    private fun updateMonitor() {
        if (LeakMonitorService.running) {
            monitorDot.text = "●"
            monitorDot.setTextColor(0xFF00E676.toInt())
            monitorText.text = "泄露监控运行中"
            monitorBtn.text = "关闭"
            monitorText.setOnClickListener { showAllEvents() }
        } else {
            monitorDot.text = "○"
            monitorDot.setTextColor(0xFF9AA0A6.toInt())
            monitorText.text = "泄露监控"
            monitorBtn.text = "开启"
        }
        val hits = EventLog.kbHits()
        if (hits.isNotEmpty()) {
            val ev = hits.last()
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ev.t))
            recentHit.visibility = View.VISIBLE
            recentHit.text = "⚠ $time ${ev.app} → ${ev.domain}（${ev.kb!!.title}）\n点击查看全部事件"
        } else {
            recentHit.visibility = View.GONE
        }
    }

    private fun showAllEvents() {
        val evs = EventLog.snapshot()
        if (evs.isEmpty()) {
            Toast.makeText(this, "暂无事件。先开启监控再使用手机。", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        for (ev in evs.asReversed().take(80)) {
            sb.append(fmt.format(Date(ev.t))).append("  ")
            sb.append(ev.app).append(" → ").append(ev.domain)
            if (ev.kb != null) sb.append("  ⚑ ").append(ev.kb.title).append(if (ev.blocked) " [已拦截]" else "")
            sb.append('\n')
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("泄露监控事件（${evs.size} 条）")
            .setMessage(sb)
            .setPositiveButton("关闭", null)
            .show()
    }
}

/**
 * OTA 复查提醒：本地记录系统指纹，检测到系统更新后提示重新体检。
 * 全程不联网、不上传，只存一个字符串和一个时间戳。
 */
object OtaWatch {
    private const val PREF = "ota"
    private const val KEY_FP = "fingerprint"
    private const val KEY_SCAN = "last_scan"

    data class Change(val from: String, val to: String, val lastScan: Long)

    /** 非空 = 检测到系统变更且此前体检过；同时把指纹更新为当前值，因此只提醒一次 */
    fun check(ctx: Context): Change? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = Build.FINGERPRINT
        val old = p.getString(KEY_FP, null)
        if (old == null) {
            p.edit().putString(KEY_FP, now).apply()
            return null
        }
        if (old == now) return null
        val last = p.getLong(KEY_SCAN, 0L)
        p.edit().putString(KEY_FP, now).apply()
        return if (last > 0L) Change(old, now, last) else null
    }

    fun noteScan(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SCAN, System.currentTimeMillis())
            .putString(KEY_FP, Build.FINGERPRINT)
            .apply()
    }
}
