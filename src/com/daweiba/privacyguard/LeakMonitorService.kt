package com.daweiba.privacyguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 泄露监控服务：本地 VPN（仅路由 DNS），拦截全机域名解析，
 * 按 UID 归属到应用，比对已知上报域名库，命中即告警；可选拦截（NXDOMAIN）。
 */
class LeakMonitorService : VpnService() {

    companion object {
        @Volatile var running = false
            private set
        @Volatile var blockEnabled = false
        private val uidNames = ConcurrentHashMap<Int, Pair<String, String?>>() // uid -> (label, pkg)
        private val lastNotify = ConcurrentHashMap<String, Long>()
        const val CHANNEL_ID = "leak_monitor"
    }

    private var tun: ParcelFileDescriptor? = null
    private var tunIn: FileInputStream? = null
    private var tunOut: FileOutputStream? = null
    private var upstream: DatagramSocket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var receiver: Thread? = null

    // txid -> (clientIp, clientPort, serverIp)
    private val queryMap = ConcurrentHashMap<Int, Triple<ByteArray, Int, ByteArray>>()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1, buildNotification("泄露监控启动中…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (tun == null) {
            DomainKb.load(this)   // 服务被单独拉起时也要载入自定义域名
            blockEnabled =
                getSharedPreferences("monitor", MODE_PRIVATE).getBoolean("block", false)
            establish()
        }
        return START_STICKY
    }

    private fun establish(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val lp = try {
            cm.getLinkProperties(cm.activeNetwork)
        } catch (e: Exception) {
            null
        }
        val servers: List<InetAddress> = (lp?.dnsServers ?: emptyList()).filter {
            it is java.net.Inet4Address
        }.ifEmpty { listOf(InetAddress.getByName("8.8.8.8")) }

        val builder = Builder()
            .setSession("大尾巴隐私守护·泄露监控")
            .setMtu(1500)
            .addAddress("10.111.111.1", 24)
        for (s in servers) {
            builder.addDnsServer(s.hostAddress!!)
            builder.addRoute(s.hostAddress, 32) // 仅把 DNS 服务器路由进 VPN
        }
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
        }

        tun = try {
            builder.establish()
        } catch (e: Exception) {
            null
        } ?: run {
            stopSelf()
            return false
        }
        running = true
        startForeground(1, buildNotification("泄露监控运行中"))

        tunIn = FileInputStream(tun!!.fileDescriptor)
        tunOut = FileOutputStream(tun!!.fileDescriptor)

        upstream = DatagramSocket().also { protect(it) }

        receiver = Thread { upstreamLoop() }.also { it.start() }
        worker = Thread { tunLoop() }.also { it.start() }
        return true
    }

    // ---------------- TUN 读取：解析 DNS 查询 ----------------
    private fun tunLoop() {
        val buf = ByteArray(65535)
        val fis = tunIn ?: return
        while (running) {
            val n = try {
                fis.read(buf)
            } catch (e: Exception) {
                break
            }
            if (n <= 28) continue
            handlePacket(buf, n)
        }
    }

    private fun handlePacket(p: ByteArray, n: Int) {
        // 仅 IPv4
        if ((p[0].toInt() shr 4) and 0xF != 4) return
        val ihl = (p[0].toInt() and 0xF) * 4
        if (ihl < 20 || n < ihl + 8) return
        if (p[9].toInt() and 0xFF != 17) return // 只要 UDP

        val srcIp = p.copyOfRange(12, 16)
        val dstIp = p.copyOfRange(16, 20)
        val sport = ((p[ihl].toInt() and 0xFF) shl 8) or (p[ihl + 1].toInt() and 0xFF)
        val dport = ((p[ihl + 2].toInt() and 0xFF) shl 8) or (p[ihl + 3].toInt() and 0xFF)
        if (dport != 53) return

        val payload = p.copyOfRange(ihl + 8, n)
        val parsed = parseQuery(payload) ?: return
        val (txid, domain, qEnd) = parsed

        // UID 归属
        val uid = lookupUid(srcIp, sport, dstIp)
        val (appLabel, pkg) = appFor(uid)

        // 域名知识库
        val kb = DomainKb.match(domain)
        val ev = EventLog.add(uid, pkg, appLabel, domain, kb, kb != null && blockEnabled)
        if (kb != null) {
            notifyHit(appLabel, pkg, domain, kb, blocked = blockEnabled)
        }

        if (kb != null && blockEnabled) {
            // 拦截：直接回 NXDOMAIN，不转发
            writeClient(nxdomain(payload, qEnd), srcIp, sport, dstIp)
            return
        }

        // 正常转发到上游 DNS
        queryMap[txid] = Triple(srcIp, sport, dstIp)
        try {
            val addr = InetAddress.getByAddress(dstIp)
            upstream?.send(DatagramPacket(payload, payload.size, addr, 53))
        } catch (e: Exception) {
            queryMap.remove(txid)
        }
    }

    // ---------------- 上游响应回写 ----------------
    private fun upstreamLoop() {
        val buf = ByteArray(65535)
        val sock = upstream ?: return
        while (running) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                sock.receive(pkt)
            } catch (e: Exception) {
                break
            }
            val data = pkt.data.copyOfRange(0, pkt.length)
            if (data.size < 12) continue
            val txid = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            val info = queryMap.remove(txid) ?: continue
            writeClient(data, info.first, info.second, info.third)
        }
    }

    /** 构造响应包写回 TUN：src=DNS服务器 dst=客户端 */
    private fun writeClient(dnsPayload: ByteArray, clientIp: ByteArray, clientPort: Int, serverIp: ByteArray) {
        val pkt = buildUdpPacket(serverIp, clientIp, 53, clientPort, dnsPayload)
        try {
            tunOut?.write(pkt)
            tunOut?.flush()
        } catch (e: Exception) {
        }
    }

    // ---------------- 工具 ----------------
    private fun lookupUid(clientIp: ByteArray, clientPort: Int, serverIp: ByteArray): Int {
        if (android.os.Build.VERSION.SDK_INT < 29) return -1
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getConnectionOwnerUid(
                17, // IPPROTO_UDP
                InetSocketAddress(InetAddress.getByAddress(clientIp), clientPort),
                InetSocketAddress(InetAddress.getByAddress(serverIp), 53)
            )
        } catch (e: Exception) {
            -1
        }
    }

    private fun appFor(uid: Int): Pair<String, String?> {
        if (uid < 0) return "未知应用" to null
        uidNames[uid]?.let { return it }
        val pm = packageManager
        val res: Pair<String, String?> = try {
            val pkgs = pm.getPackagesForUid(uid)
            if (pkgs.isNullOrEmpty()) {
                "UID $uid" to null
            } else {
                val pkg = pkgs[0]
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }
                label to pkg
            }
        } catch (e: Exception) {
            "UID $uid" to null
        }
        uidNames[uid] = res
        return res
    }

    private fun notifyHit(app: String, pkg: String?, domain: String, kb: DomainKb.Entry, blocked: Boolean) {
        val key = "$pkg|$domain"
        val now = System.currentTimeMillis()
        val last = lastNotify[key] ?: 0L
        if (now - last < 30 * 60 * 1000) return // 30 分钟内同类只提醒一次
        lastNotify[key] = now
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val act = if (pkg != null) {
            PendingIntent.getActivity(
                this, 0,
                Intent(this, DetailActivity::class.java).putExtra("pkg", pkg),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(if (blocked) "已拦截上报：$app" else "检测到上报：$app")
            .setContentText("$domain — ${kb.title}")
            .setAutoCancel(true)
        if (act != null) n.setContentIntent(act)
        try {
            nm.notify((key.hashCode() and 0x7FFFFFFF), n.build())
        } catch (e: Exception) {
        }
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("大尾巴隐私守护")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "泄露监控", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "应用上报已知采集服务器的实时告警"
            }
        )
    }

    override fun onRevoke() {
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        queryMap.clear()
        try { upstream?.close() } catch (e: Exception) {}
        try { tunIn?.close() } catch (e: Exception) {}
        try { tunOut?.close() } catch (e: Exception) {}
        try { tun?.close() } catch (e: Exception) {}
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(1)
        super.onDestroy()
    }
}

// ---------------- 包解析函数 ----------------

/** 解析 DNS 查询：返回 (txid, 域名, 问题段结束偏移) */
internal fun parseQuery(p: ByteArray): Triple<Int, String, Int>? {
    if (p.size < 17) return null
    val txid = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
    val flags = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
    if (flags and 0x8000 != 0) return null // 是响应不是查询
    val qd = ((p[4].toInt() and 0xFF) shl 8) or (p[5].toInt() and 0xFF)
    if (qd < 1) return null
    var pos = 12
    val sb = StringBuilder()
    while (pos < p.size) {
        val len = p[pos].toInt() and 0xFF
        pos++
        if (len == 0) break
        if (len and 0xC0 == 0xC0) return null // 查询段出现压缩指针，异常
        if (pos + len > p.size) return null
        for (i in 0 until len) sb.append(((p[pos + i].toInt() and 0xFF).toChar()))
        pos += len
        sb.append('.')
    }
    if (sb.isEmpty() || pos + 4 > p.size) return null
    val name = sb.substring(0, sb.length - 1).lowercase()
    return Triple(txid, name, pos + 4)
}

/** 构造 NXDOMAIN 响应（原样保留问题段） */
internal fun nxdomain(query: ByteArray, qEnd: Int): ByteArray {
    val r = ByteArray(qEnd)
    r[0] = query[0]; r[1] = query[1]
    r[2] = 0x81.toByte(); r[3] = 0x83.toByte() // QR=1 RD=1 RA=1 RCODE=3(NXDOMAIN)
    r[5] = 1 // QDCOUNT=1
    System.arraycopy(query, 12, r, 12, qEnd - 12)
    return r
}

/** 构造 IPv4+UDP 包 */
internal fun buildUdpPacket(srcIp: ByteArray, dstIp: ByteArray, sport: Int, dport: Int, payload: ByteArray): ByteArray {
    val udpLen = 8 + payload.size
    val total = 20 + udpLen
    val pkt = ByteArray(total)
    pkt[0] = 0x45
    pkt[2] = ((total shr 8) and 0xFF).toByte()
    pkt[3] = (total and 0xFF).toByte()
    pkt[8] = 64
    pkt[9] = 17
    System.arraycopy(srcIp, 0, pkt, 12, 4)
    System.arraycopy(dstIp, 0, pkt, 16, 4)
    pkt[20] = ((sport shr 8) and 0xFF).toByte(); pkt[21] = (sport and 0xFF).toByte()
    pkt[22] = ((dport shr 8) and 0xFF).toByte(); pkt[23] = (dport and 0xFF).toByte()
    pkt[24] = ((udpLen shr 8) and 0xFF).toByte(); pkt[25] = (udpLen and 0xFF).toByte()
    System.arraycopy(payload, 0, pkt, 28, payload.size)
    val c = checksum(pkt, 0, 20)
    pkt[10] = ((c shr 8) and 0xFF).toByte(); pkt[11] = (c and 0xFF).toByte()
    return pkt
}

internal fun checksum(data: ByteArray, off: Int, len: Int): Int {
    var s = 0
    var i = off
    var n = len
    while (n > 1) {
        s += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
        i += 2; n -= 2
    }
    if (n == 1) s += (data[i].toInt() and 0xFF) shl 8
    while (s shr 16 != 0) s = (s and 0xFFFF) + (s shr 16)
    return s.inv() and 0xFFFF
}
