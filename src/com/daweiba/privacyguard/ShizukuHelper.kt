package com.daweiba.privacyguard

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * Shizuku 可选增强：借 shell(uid 2000) 身份执行 pm 命令，实现真正的「一键停用 / 一键恢复」。
 *
 * 设计前提：App 本体完全不依赖 Shizuku。没装 / 没授权时所有功能照常，
 * 结论页的按钮只是退回「跳转到系统设置页手动停用」。
 *
 * 用 IShizukuService 直接调 newProcess —— Shizuku 13.x 的 API 层移除了
 * Shizuku.newProcess() 便捷方法，但服务端 AIDL 接口仍保留，这样可以用最新的库。
 */
object ShizukuHelper {

    const val REQ_CODE = 2001

    enum class State { NO_SERVICE, NEED_PERMISSION, READY }

    data class CmdResult(val code: Int, val out: String, val err: String) {
        /** pm 命令即便 exit 0 也可能把失败写进输出里（如 "Cannot disable ..."） */
        val ok: Boolean
            get() {
                val all = (out + "\n" + err).lowercase()
                return code == 0 &&
                    !all.contains("error") &&
                    !all.contains("cannot ") &&
                    !all.contains("failure") &&
                    !all.contains("not found") &&
                    !all.contains("unknown package")
            }
        val message: String
            get() = err.trim().ifEmpty { out.trim() }.ifEmpty { "退出码 $code" }
    }

    private const val PREFS = "shizuku_actions"
    private const val KEY_DISABLED = "disabled_packages"

    /** 无论如何都不允许停用：停了就再也恢复不了，或会直接弄坏系统 */
    private val PROTECTED = listOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers",
        "com.android.shell",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.mms",
        "com.android.stk",
        "com.sec.android.app.launcher",
        "com.samsung.android.incallui",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.daweiba.privacyguard",   // 停了自己就没法一键恢复
        "moe.shizuku.privileged.api"  // 停了 Shizuku 就没法一键恢复
    )

    private val PKG_RE = Regex("^[A-Za-z0-9_.]+$")

    fun isProtected(pkg: String): Boolean =
        PROTECTED.any { pkg == it || pkg.startsWith("$it.") }

    /** 当前授权状态。Shizuku 未启动时 pingBinder 可能直接抛异常，必须兜住 */
    fun state(): State = try {
        if (!Shizuku.pingBinder()) State.NO_SERVICE
        else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) State.NEED_PERMISSION
        else State.READY
    } catch (e: Throwable) {
        State.NO_SERVICE
    }

    fun requestPermission() = try {
        Shizuku.requestPermission(REQ_CODE)
    } catch (e: Throwable) {
        // Shizuku 未安装 / 服务未启动时忽略，界面会保持原状
    }

    /** 同步执行一条 shell 命令；必须在后台线程调用 */
    fun exec(cmd: Array<String>): CmdResult = try {
        val binder = Shizuku.getBinder()
            ?: return CmdResult(-1, "", "Shizuku 服务未连接")
        val svc = IShizukuService.Stub.asInterface(binder)
        val proc = svc.newProcess(cmd, null, null)

        // 两个流并发读，避免管道写满导致子进程阻塞
        var out = ""
        var err = ""
        val tOut = Thread { out = read(proc.inputStream) }
        val tErr = Thread { err = read(proc.errorStream) }
        tOut.start(); tErr.start()
        val code = try { proc.waitFor() } catch (e: Exception) { -1 }
        tOut.join(2500); tErr.join(2500)
        CmdResult(code, out, err)
    } catch (e: Throwable) {
        CmdResult(-1, "", e.message ?: "执行失败")
    }

    private fun read(pfd: ParcelFileDescriptor?): String {
        if (pfd == null) return ""
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    // ============ 停用 / 恢复 ============

    fun disable(ctx: Context, pkg: String): CmdResult {
        if (!PKG_RE.matches(pkg)) return CmdResult(-1, "", "非法包名")
        if (isProtected(pkg)) return CmdResult(-1, "", "系统核心组件，禁止停用")
        if (state() != State.READY) return CmdResult(-1, "", "Shizuku 未授权")
        val r = exec(arrayOf("pm", "disable-user", "--user", "0", pkg))
        if (r.ok) addDisabled(ctx, pkg)
        return r
    }

    fun enable(ctx: Context, pkg: String): CmdResult {
        if (!PKG_RE.matches(pkg)) return CmdResult(-1, "", "非法包名")
        if (state() != State.READY) return CmdResult(-1, "", "Shizuku 未授权")
        val r = exec(arrayOf("pm", "enable", "--user", "0", pkg))
        if (r.ok) removeDisabled(ctx, pkg)
        return r
    }

    // ============ 本 App 停用过的记录，用于「一键恢复」 ============

    fun disabledList(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_DISABLED, emptySet())!!
            .toList().sorted()

    private fun addDisabled(ctx: Context, pkg: String) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = sp.getStringSet(KEY_DISABLED, emptySet())!!.toMutableSet()
        cur.add(pkg)
        sp.edit().putStringSet(KEY_DISABLED, cur).apply()
    }

    private fun removeDisabled(ctx: Context, pkg: String) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = sp.getStringSet(KEY_DISABLED, emptySet())!!.toMutableSet()
        cur.remove(pkg)
        sp.edit().putStringSet(KEY_DISABLED, cur).apply()
    }
}
