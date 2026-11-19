package com.daweiba.privacyguard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 体检结论页：三段式建议
 *  ① 需处理（采集组件 / 检测到上报）
 *  ② 可卸载（用户自装）/ 可停用（预装）
 *  ③ 建议保留（只显示数量）
 *
 * v0.6.0：接入 Shizuku 可选增强。授权后「去停用」升级为「一键停用」，
 * 并可一键恢复；未授权时行为与旧版完全一致（跳系统设置页手动停用）。
 */
class ReportActivity : Activity() {

    private data class ActionRow(
        val btn: Button,
        val r: AppRisk,
        val a: AdviceDb.Advice,
        val level: AdviceDb.Level
    )

    private val rows = mutableListOf<ActionRow>()
    private lateinit var statusText: TextView
    private lateinit var restoreBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dp = resources.displayMetrics.density
        fun dp(v: Int) = (v * dp).toInt()

        val results = Session.results
        val advised = results.map { it to AdviceDb.advise(it) }

        val fixes = advised.filter { it.second.level == AdviceDb.Level.FIX }
            .sortedWith(compareByDescending<Pair<AppRisk, AdviceDb.Advice>> { it.first.sig?.severity ?: 0 }
                .thenByDescending { it.first.resolutionCount })
        val uninstalls = advised.filter { it.second.level == AdviceDb.Level.UNINSTALL }
            .sortedByDescending { it.first.permScore }
        val disables = advised.filter { it.second.level == AdviceDb.Level.DISABLE }
            .sortedBy { it.first.label }
        val keeps = advised.filter { it.second.level == AdviceDb.Level.KEEP }
        val suspicions = Session.suspicions

        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF121212.toInt()) }
        UiUtil.applySystemBarInsets(scroll, 28, 24)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        scroll.addView(root)

        // 标题区
        root.addView(TextView(this).apply {
            text = "体检结果"
            textSize = 24f
            setTextColor(0xFFECEFF1.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "共 ${results.size} 个应用 · 需要你决定的是下面这几组"
            textSize = 12f
            setTextColor(0xFF9AA0A6.toInt())
            setPadding(0, dp(4), 0, dp(12))
        })

        // 总结条
        root.addView(TextView(this).apply {
            text = buildString {
                append("⚠ 需处理 ${fixes.size} 个")
                if (suspicions.isNotEmpty()) append("    ·    可疑外联 ${suspicions.size} 个")
                append("    ·    建议清理 ${uninstalls.size + disables.size} 个")
                append("\n✓ 其余 ${keeps.size} 个无需处理")
            }
            textSize = 14f
            setTextColor(0xFFE8EAED.toInt())
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFF1E1F22.toInt())
                cornerRadius = 10f * dp
            }
        })

        // 一键停用能力状态条（Shizuku 可选增强）
        addStatusBar(root)

        // ① 需处理
        if (fixes.isNotEmpty()) {
            section(root, "① 需处理 — 有实据的问题", 0xFFE53935.toInt(),
                "这些组件会把你的数据报到外部服务器。停用最干净，不影响正常使用。")
            fixes.forEach { (r, a) -> row(root, r, a, AdviceDb.Level.FIX) }
        }

        // ② 可疑外联（行为启发式，未确认）
        if (suspicions.isNotEmpty()) {
            section(root, "② 可疑外联 — 没命中已知库，但行为反常", 0xFFFFAB40.toInt(),
                "以下结论是「可疑（未确认）」，不是已确认泄露。依据：域名关键词、心跳式重复解析、域名数量、凌晨后台活动。请先自行核查再处理。")
            suspicions.values.take(12).forEach { suspectRow(root, it) }
        } else if (EventLog.totalResolutions == 0) {
            root.addView(TextView(this).apply {
                text = "提示：开启首页「泄露监控」后，本页会自动标注未知域名的可疑外联（不需要预先知道域名）。"
                textSize = 12f
                setTextColor(0xFF7986CB.toInt())
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(dp(4), dp(14), dp(4), dp(4))
            })
        }

        // ③ 可以卸载
        if (uninstalls.isNotEmpty()) {
            section(root, "③ 可以卸载 — 你自己装的，不要就删", 0xFFFF7043.toInt(),
                "删除只影响该应用本身，不影响手机系统。数据会一并消失。按权限面大小列出前 12 个。")
            uninstalls.take(12).forEach { (r, a) -> row(root, r, a, AdviceDb.Level.UNINSTALL) }
            if (uninstalls.size > 12) {
                root.addView(TextView(this).apply {
                    text = "另有 ${uninstalls.size - 12} 个你安装的应用未列出（权限面较小，无需特别处理）"
                    textSize = 12f
                    setTextColor(0xFF7986CB.toInt())
                    setPadding(0, (resources.displayMetrics.density * 2).toInt(), 0, 0)
                })
            }
        }
        // ④ 可以停用
        if (disables.isNotEmpty()) {
            section(root, "④ 可以停用 — 预装但你可能用不到", 0xFFFFB74D.toInt(),
                "停用（不是卸载）随时可恢复，代价是列表里写明的功能会消失。")
            disables.forEach { (r, a) -> row(root, r, a, AdviceDb.Level.DISABLE) }
        }

        // ⑤ 包内线索（仅在做过「深度扫描」后出现）
        if (Session.apkFindings.isNotEmpty()) {
            section(root, "⑤ 包内线索 — 离线扫描发现", 0xFF64B5F6.toInt(),
                "下面是从应用安装包里读到的字符串线索，只代表「包里写了这个地址」，不代表它正在上报。")
            for ((pkg, list) in Session.apkFindings) {
                val label = Session.results.firstOrNull { it.pkg == pkg }?.label ?: pkg
                val line = TextView(this).apply {
                    text = buildString {
                        append("· ").append(label).append("（").append(pkg).append("）\n")
                        list.take(3).forEach { append("    ").append(it).append('\n') }
                    }
                    textSize = 12f
                    setTextColor(0xFFB0BEC5.toInt())
                    setLineSpacing(dp(2).toFloat(), 1f)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = GradientDrawable().apply {
                        setColor(0xFF1E1F22.toInt())
                        cornerRadius = 10f * dp
                    }
                }
                root.addView(
                    line,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                )
            }
        }

        // ⑥ 建议保留
        section(root, "⑥ 建议保留 — 不用管", 0xFF81C784.toInt(),
            "${keeps.size} 个系统组件：核心功能或未发现上报行为。卸载有风险，保持现状即可。")

        setContentView(scroll)
        refreshActions()
    }

    override fun onResume() {
        super.onResume()
        refreshActions()   // 授权对话框关闭后回到这里，自动刷新按钮状态
    }

    // ============ 状态条 ============
    private fun addStatusBar(root: LinearLayout) {
        val dp = resources.displayMetrics.density
        fun dp(v: Int) = (v * dp).toInt()

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFF1A2A1E.toInt())
                cornerRadius = 10f * dp
            }
        }
        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFC8E6C9.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        bar.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        restoreBtn = Button(this).apply {
            textSize = 11f
            visibility = View.GONE
        }
        bar.addView(restoreBtn)

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(10)
        lp.bottomMargin = dp(4)
        root.addView(bar, lp)
    }

    /** 根据 Shizuku 授权状态刷新状态条和每一行的动作按钮 */
    private fun refreshActions() {
        val st = ShizukuHelper.state()
        when (st) {
            ShizukuHelper.State.NO_SERVICE -> {
                statusText.text = "⚙ 想从这里直接停用？安装 Shizuku 可解锁（可选，不影响其他功能）。\n" +
                    "当前点「去停用」会带你到系统设置页手动操作，效果一样。"
                statusText.setOnClickListener { showShizukuHelp() }
            }
            ShizukuHelper.State.NEED_PERMISSION -> {
                statusText.text = "⚙ 已检测到 Shizuku，点这里授权，即可解锁「一键停用 / 一键恢复」。"
                statusText.setOnClickListener { ShizukuHelper.requestPermission() }
            }
            ShizukuHelper.State.READY -> {
                statusText.text = "✓ 已解锁一键停用。停用随时可在本页恢复。"
                statusText.setOnClickListener(null)
            }
        }

        val disabled = ShizukuHelper.disabledList(this)
        if (st == ShizukuHelper.State.READY && disabled.isNotEmpty()) {
            restoreBtn.visibility = View.VISIBLE
            restoreBtn.text = "恢复 ${disabled.size} 个"
            restoreBtn.setOnClickListener { confirmRestore(disabled) }
        } else {
            restoreBtn.visibility = View.GONE
        }

        for (row in rows) {
            when (row.level) {
                AdviceDb.Level.UNINSTALL -> {
                    row.btn.text = "卸载"
                    row.btn.isEnabled = true
                    row.btn.setOnClickListener { uninstall(row.r.pkg) }
                }
                else -> {
                    val canDirect = st == ShizukuHelper.State.READY &&
                        !ShizukuHelper.isProtected(row.r.pkg) && row.a.allowDisable
                    if (canDirect) {
                        val enabled = appEnabled(row.r.pkg)
                        row.btn.text = if (enabled) "一键停用" else "已停用"
                        row.btn.isEnabled = enabled
                        row.btn.setOnClickListener { confirmDisable(row) }
                    } else {
                        // 建议保留功能、只需撤销权限的组件：引导去权限页，不给停用按钮
                        row.btn.text = if (row.a.allowDisable) "去停用" else "去处理"
                        row.btn.isEnabled = true
                        row.btn.setOnClickListener {
                            if (!row.a.allowDisable) {
                                Toast.makeText(
                                    this,
                                    "此组件建议保留功能，请在权限页撤销定位/通讯录/麦克风等敏感权限",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            openAppSettings(row.r.pkg)
                        }
                    }
                }
            }
        }
    }

    /** 实时读系统启用状态，不用扫描时的快照 */
    private fun appEnabled(pkg: String): Boolean = try {
        val s = packageManager.getApplicationEnabledSetting(pkg)
        s != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
            s != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    } catch (e: Exception) {
        true
    }

    // ============ 停用 ============
    private fun confirmDisable(row: ActionRow) {
        AlertDialog.Builder(this)
            .setTitle("停用 ${row.r.label}？")
            .setMessage(
                "包名：${row.r.pkg}\n\n" +
                    "影响：${row.a.risk}\n\n" +
                    "停用后图标会消失、不再运行；随时可回到本页点「恢复」把它加回来。\n" +
                    "注意：系统核心组件已被自动排除。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("确认停用") { _, _ -> doDisable(row) }
            .show()
    }

    private fun doDisable(row: ActionRow) {
        row.btn.isEnabled = false
        row.btn.text = "处理中…"
        Thread {
            val res = ShizukuHelper.disable(this, row.r.pkg)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (res.ok) "已停用 ${row.r.label}" else "停用失败：${res.message}",
                    Toast.LENGTH_LONG
                ).show()
                refreshActions()
            }
        }.start()
    }

    // ============ 恢复 ============
    private fun confirmRestore(list: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("恢复 ${list.size} 个应用？")
            .setMessage(
                list.joinToString("\n") + "\n\n" +
                    "将执行 pm enable，恢复后这些应用会重新出现在桌面。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("全部恢复") { _, _ -> doRestore(list) }
            .show()
    }

    private fun doRestore(list: List<String>) {
        restoreBtn.isEnabled = false
        restoreBtn.text = "恢复中…"
        Thread {
            var ok = 0
            val failed = mutableListOf<String>()
            for (pkg in list) {
                if (ShizukuHelper.enable(this, pkg).ok) ok++ else failed.add(pkg)
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (failed.isEmpty()) "已恢复 $ok 个应用" else "恢复 $ok 个，失败 ${failed.size} 个",
                    Toast.LENGTH_LONG
                ).show()
                refreshActions()
            }
        }.start()
    }

    private fun showShizukuHelp() {
        AlertDialog.Builder(this)
            .setTitle("一键停用是可选增强")
            .setMessage(
                "普通应用没有权限停用别的应用（Android 系统限制，不是本 App 做不到）。\n\n" +
                    "想从这里直接停用，可安装开源工具 Shizuku，用「无线调试」启动一次；" +
                    "Android 11 及以上不需要电脑。装好回到本页点一下授权即可。\n\n" +
                    "不安装也完全不影响使用：点「去停用」会打开系统应用详情页手动停用，效果相同。"
            )
            .setPositiveButton("知道了", null)
            .setNeutralButton("打开下载页") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")))
                } catch (e: Exception) {
                }
            }
            .show()
    }

    // ============ 通用 UI ============
    private fun section(root: LinearLayout, title: String, color: Int, hint: String) {
        val dp = resources.displayMetrics.density
        root.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(color)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (dp * 22).toInt(), 0, (dp * 4).toInt())
        })
        root.addView(TextView(this).apply {
            text = hint
            textSize = 12f
            setTextColor(0xFF9AA0A6.toInt())
            setLineSpacing((dp * 2).toInt().toFloat(), 1f)
            setPadding(0, 0, 0, (dp * 8).toInt())
        })
    }

    /** 可疑外联卡片：独立渲染，不加入 rows，因此不受 Shizuku 停用链路影响 */
    private fun suspectRow(root: LinearLayout, f: Heuristics.Finding) {
        val dp = resources.displayMetrics.density
        fun dp(v: Int) = (v * dp).toInt()
        val label = Session.results.firstOrNull { it.pkg == f.pkg }?.label ?: f.app

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFF1E1F22.toInt())
                cornerRadius = 10f * dp
                setStroke(dp(1), 0xFF8D6E63.toInt())
            }
        }
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }

        card.addView(TextView(this).apply {
            text = "$label  ·  可疑外联（未确认）"
            textSize = 15f
            setTextColor(0xFFFFCC80.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = "解析 ${f.total} 次 / 命中已知库 ${f.hits} 次 · 包名 ${f.pkg}"
            textSize = 11f
            setTextColor(0xFF9AA0A6.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = f.reasons.joinToString("\n") { "· $it" }
            textSize = 12f
            setTextColor(0xFFE8EAED.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(8), 0, 0)
        })
        if (f.domains.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = "域名样例：${f.domains.take(3).joinToString("、")}"
                textSize = 11f
                setTextColor(0xFF9AA0A6.toInt())
                setPadding(0, dp(4), 0, 0)
            })
        }
        val apkHits = Session.apkFindings[f.pkg]
        if (!apkHits.isNullOrEmpty()) {
            card.addView(TextView(this).apply {
                text = "包内线索：${apkHits.take(2).joinToString("；")}"
                textSize = 11f
                setTextColor(0xFFFFAB40.toInt())
                setPadding(0, dp(4), 0, 0)
            })
        }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "去处理"
            textSize = 12f
            setOnClickListener { openAppSettings(f.pkg) }
        })
        actions.addView(Button(this).apply {
            text = "查看详情 ›"
            textSize = 12f
            setOnClickListener {
                startActivity(Intent(this@ReportActivity, DetailActivity::class.java).putExtra("pkg", f.pkg))
            }
        })
        card.addView(actions)
        root.addView(card)
    }

    private fun row(root: LinearLayout, r: AppRisk, a: AdviceDb.Advice, level: AdviceDb.Level) {
        val dp = resources.displayMetrics.density
        fun dp(v: Int) = (v * dp).toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFF1E1F22.toInt())
                cornerRadius = 10f * dp
            }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)
        card.layoutParams = lp

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            try { setImageDrawable(packageManager.getApplicationIcon(r.pkg)) } catch (e: Exception) { }
        }
        head.addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(10) })

        val nameBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nameBox.addView(TextView(this).apply {
            text = r.label
            textSize = 15f
            setTextColor(0xFFE8EAED.toInt())
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        nameBox.addView(TextView(this).apply {
            text = if (r.enabled) a.reason else "${a.reason}（当前已停用）"
            textSize = 12f
            setTextColor(a.level.color)
            maxLines = 3
        })
        head.addView(nameBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val act = Button(this).apply { textSize = 12f }
        head.addView(act)
        card.addView(head)
        rows.add(ActionRow(act, r, a, level))

        card.addView(TextView(this).apply {
            text = "影响：${a.risk}"
            textSize = 11f
            setTextColor(0xFF9AA0A6.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = "查看详情 ›"
            textSize = 12f
            setTextColor(0xFF64B5F6.toInt())
            setPadding(0, dp(6), 0, 0)
            setOnClickListener {
                startActivity(Intent(this@ReportActivity, DetailActivity::class.java).putExtra("pkg", r.pkg))
            }
        })
        root.addView(card)
    }

    private fun uninstall(pkg: String) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
        } catch (e: Exception) {
            Toast.makeText(this, "系统不允许直接卸载，请到设置中操作", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppSettings(pkg: String) {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$pkg")))
        } catch (e: Exception) {
        }
    }
}
