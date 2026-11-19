package com.daweiba.privacyguard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DetailActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pkg = intent.getStringExtra("pkg") ?: run { finish(); return }

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val scroll = ScrollView(this)
        UiUtil.applySystemBarInsets(scroll, 28, 20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        scroll.addView(root)

        val r: AppRisk = Scanner.scanOne(this, pkg) ?: run {
            root.addView(sectionTitle("未找到应用 $pkg"))
            setContentView(scroll)
            return
        }
        val advice = AdviceDb.advise(r)

        // 标题
        root.addView(TextView(this).apply {
            text = r.label
            textSize = 22f
            setTextColor(0xFFECEFF1.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = r.pkg
            textSize = 12f
            setTextColor(0xFF9AA0A6.toInt())
            setPadding(0, dp(2), 0, dp(10))
        })

        // ===== 建议结论（最重要，放最前）=====
        root.addView(TextView(this).apply {
            text = "建议：${advice.level.label}"
            textSize = 17f
            setTextColor(advice.level.color)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = advice.reason
            textSize = 14f
            setTextColor(0xFFE8EAED.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        root.addView(TextView(this).apply {
            text = "如果处理：${advice.risk}"
            textSize = 13f
            setTextColor(0xFF9AA0A6.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(6), 0, 0)
        })

        // ===== 操作 =====
        root.addView(sectionTitle("操作"))
        if (advice.canUninstall) {
            root.addView(Button(this).apply {
                text = "卸载这个应用"
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                    } catch (e: Exception) {
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        }
        val actRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actRow.addView(
            Button(this).apply {
                text = "打开系统设置页"
                setOnClickListener {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                        )
                    } catch (e: Exception) {
                    }
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
        )
        actRow.addView(
            Button(this).apply {
                text = "复制停用命令"
                setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("cmd", "pm disable-user --user 0 $pkg"))
                    android.widget.Toast.makeText(this@DetailActivity, "已复制 adb 停用命令", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(actRow)

        // ===== 基本信息 =====
        root.addView(sectionTitle("基本信息"))
        root.addView(body(
            buildString {
                appendLine("版本: ${r.versionName}")
                appendLine("类型: ${if (r.isSystem) "系统应用" else "用户应用"}")
                appendLine("状态: ${r.enabledTxt}")
                append("目标SDK: ${r.targetSdk}")
            }
        ))

        // ===== 知识库判定 =====
        if (r.sig != null) {
            root.addView(sectionTitle("知识库判定"))
            root.addView(body(
                buildString {
                    appendLine("【${r.sig.title}】")
                    appendLine()
                    appendLine(r.sig.detail)
                    appendLine()
                    append("处置建议：${r.sig.advice}")
                },
                if (r.sig.severity > 0) 0xFFFFCCBC.toInt() else 0xFFB2DFDB.toInt()
            ))
        }

        // ===== 泄露事件 =====
        val events = EventLog.forPkg(r.pkg)
        if (events.isNotEmpty()) {
            root.addView(sectionTitle("捕获的域名解析（${events.size} 次）"))
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
            root.addView(body(
                buildString {
                    events.take(50).forEach {
                        append(fmt.format(Date(it.t)))
                        append("  ")
                        append(it.domain)
                        if (it.kb != null) append("   ⚑ ").append(it.kb.title).append(if (it.blocked) " [已拦截]" else "")
                        append('\n')
                    }
                },
                0xFFFFCCBC.toInt()
            ))
        }

        // ===== 权限参考 =====
        val (_, reasons) = RiskModel.score(r.requested)
        root.addView(sectionTitle("权限参考（${r.permScore} 分 · ${r.permBand}）"))
        root.addView(body(
            if (reasons.isEmpty()) "无敏感权限组合"
            else reasons.joinToString("\n") { "· ${it}" }
        ))

        // ===== 全部请求权限 =====
        root.addView(sectionTitle("请求权限（${r.requested.size} 项）"))
        root.addView(body(
            if (r.requested.isEmpty()) "未声明任何权限"
            else r.requested.joinToString("\n") { "· ${it.substringAfterLast('.')}" }
        ))

        setContentView(scroll)
    }

    private fun sectionTitle(t: String): TextView =
        TextView(this).apply {
            text = t
            textSize = 14f
            setTextColor(0xFF00E676.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (resources.displayMetrics.density * 16).toInt(), 0, (resources.displayMetrics.density * 6).toInt())
        }

    private fun body(t: String, color: Int = 0xFFB0BEC5.toInt()): TextView =
        TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(color)
            setLineSpacing((resources.displayMetrics.density * 3).toInt().toFloat(), 1f)
        }
}
