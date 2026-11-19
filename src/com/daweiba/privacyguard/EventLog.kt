package com.daweiba.privacyguard

import java.util.Collections

/** 泄露事件日志（内存环形 + 单例） */
object EventLog {

    data class Ev(
        val t: Long,
        val uid: Int,
        val pkg: String?,
        val app: String,
        val domain: String,
        val kb: DomainKb.Entry?,
        val blocked: Boolean
    )

    private val buf = Collections.synchronizedList(mutableListOf<Ev>())
    private const val CAP = 500

    @Volatile var totalResolutions: Int = 0
        private set

    fun add(uid: Int, pkg: String?, app: String, domain: String, kb: DomainKb.Entry?, blocked: Boolean): Ev {
        totalResolutions++
        val ev = Ev(System.currentTimeMillis(), uid, pkg, app, domain, kb, blocked)
        synchronized(buf) {
            buf.add(ev)
            while (buf.size > CAP) buf.removeAt(0)
        }
        return ev
    }

    fun snapshot(): List<Ev> = synchronized(buf) { ArrayList(buf) }

    fun recent(n: Int): List<Ev> = synchronized(buf) { buf.takeLast(n).reversed() }

    fun forPkg(pkg: String): List<Ev> = synchronized(buf) { buf.filter { it.pkg == pkg } }

    fun countForPkg(pkg: String): Int = synchronized(buf) { buf.count { it.pkg == pkg } }

    fun kbHits(): List<Ev> = synchronized(buf) { buf.filter { it.kb != null } }

    fun reset() {
        synchronized(buf) { buf.clear() }
        totalResolutions = 0
    }
}
