package com.ccm.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 三段式：
 *   1. 拉起内置的 cloudflared，把远端的 SOCKS5 映射到本机 127.0.0.1:1080
 *   2. 等 1080 能连上（最多 30 秒）
 *   3. 建 TUN，交给 hev 的 tun2socks 把流量灌进 1080
 *
 * cloudflared 要 3 秒左右才和边缘握完手，所以对外显示「预热中」，
 * 这段时间 UI 要明确告诉用户别急着走。
 */
class CcmVpnService : VpnService() {

    companion object {
        private const val TAG = "CCMVPN"
        private const val CH_ID = "ccmvpn"
        private const val NOTIF_ID = 1001
        // TUN 自己的地址，必须和 hev.yml 里的 tunnel.ipv4 一致
        private const val HEV_TUN_ADDR = "198.18.0.1"
        const val ACTION_STATE = "com.ccm.vpn.STATE"
        const val ACTION_START = "com.ccm.vpn.START"
        const val ACTION_STOP = "com.ccm.vpn.STOP"

        // 由 hostname 决定，App 里写死一个可配置的值
        const val PREFS = "ccmvpn"
        const val KEY_HOSTNAME = "hostname"
        const val DEFAULT_HOSTNAME = "socks.nexus-ai.kdns.fr"
    }

    private var tunnel: ParcelFileDescriptor? = null
    private var localSocks: LocalSocks? = null
    private var lastError: String = ""
    private var cfProc: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopVpn(); return START_NOT_STICKY }
        }
        if (!running) startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        running = true
        // Android 14+（API 34）起 startForeground 必须声明类型，
        // 否则系统抛 MissingForegroundServiceTypeException 直接把进程杀掉，
        // 现象就是「点了连接，两三秒后自己关了」。
        val notif = buildNotification("预热中…", "正在连接节点", 0f)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        scope.launch {
            try {
                val host = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_HOSTNAME, DEFAULT_HOSTNAME) ?: DEFAULT_HOSTNAME

                broadcastState("预热", "解析节点 $host")
                startCloudflared(host)

                // cloudflared access 起来后要几秒才真正能转发
                val ok = waitSocks(30_000)
                if (!ok) {
                    broadcastState("失败", "SOCKS5 没有就绪")
                    stopVpn(keepState = true); return@launch
                }
                broadcastState("预热", "SOCKS5 就绪")

                if (ModeStore.mode(this@CcmVpnService) == Mode.PORT) {
                    // 端口模式：不建 TUN，起一个本地 SOCKS5 让别的应用连过来
                    val p = ModeStore.port(this@CcmVpnService)
                    val ls = LocalSocks(p)
                    if (!ls.start()) {
                        broadcastState("失败", "端口 $p 起不来")
                        stopVpn(keepState = true); return@launch
                    }
                    localSocks = ls
                    broadcastState("已连接", "代理已开 127.0.0.1:$p")
                    updateNotification("已连接", "SOCKS5 127.0.0.1:$p", 0f)
                    monitor(portMode = true)
                    return@launch
                }

                if (!establishTun()) {
                    broadcastState("失败", "TUN 建立失败（授权被拒或 so 没加载）")
                    stopVpn(keepState = true); return@launch
                }

                val m = ModeStore.mode(this@CcmVpnService)
                val label = when (m) {
                    Mode.GLOBAL -> "全局 · 流量已接管"
                    Mode.APPS -> "分应用 · 已接管 ${(ModeStore.apps(this@CcmVpnService) ?: emptySet()).size} 个应用"
                    else -> "已接管"
                }
                broadcastState("已连接", label)
                updateNotification("已连接", "$host", 0f)
                monitor()
            } catch (e: Exception) {
                Log.e(TAG, "启动失败", e)
                broadcastState("失败", "${e.javaClass.simpleName}: ${e.message}")
                stopVpn(keepState = true)
            }
        }
    }

    /** 装了的所有带启动图标的第三方应用 —— 分应用模式第一次进来时全部勾上 */
    private fun installedPackages(): Set<String> {
        return try {
            packageManager.getInstalledApplications(0)
                .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { it.packageName }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** 内置的 cloudflared 二进制从 assets 解出来再执行 */
    private fun startCloudflared(host: String) {
        val bin = File(filesDir, "cloudflared")
        if (!bin.exists()) {
            assets.open("cloudflared").use { i ->
                bin.outputStream().use { o -> i.copyTo(o) }
            }
            bin.setExecutable(true)
        }
        val cmd = listOf(
            bin.absolutePath, "access", "tcp",
            "--hostname", host,
            "--url", "127.0.0.1:1080"
        )
        cfProc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .redirectOutput(File(filesDir, "cloudflared.log"))
            .start()
        Log.i(TAG, "cloudflared started: ${cmd.joinToString(" ")}")
    }

    /** 一直试到 1080 能连上，或者超时 */
    private suspend fun waitSocks(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use {
                    it.connect(InetSocketAddress("127.0.0.1", 1080), 800)
                    return@withContext true
                }
            } catch (_: Exception) {
                delay(400)
            }
        }
        false
    }

    private fun establishTun(): Boolean {
        val mode = ModeStore.mode(this)
        val b = Builder()
            .setSession("CCMVPN")
            .setMtu(1400)
            .addAddress(HEV_TUN_ADDR, 32)
            // DNS 必须走公共服务器：hev 会把发往它的 UDP 请求
            // 通过 SOCKS5 的 UDP 转发出去（配置里 socks5.udp: 'udp'）。
            // 之前写 10.8.0.1 是个没人监听的地址，所有解析都超时，
            // 现象就是「连上了但打不开任何网页」。
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .setBlocking(true)

        when (mode) {
            // 整机：所有流量都走隧道
            Mode.GLOBAL -> b.addRoute("0.0.0.0", 0)

            // 分应用：默认全勾，只把勾选的应用放进来。
            // addAllowedApplication 之后未列出的应用自动直连；
            // 一个都没勾的时候退化成「全都不走」，所以底下做兜底。
            Mode.APPS -> {
                val picked = ModeStore.apps(this) ?: installedPackages()
                if (picked.isEmpty()) b.addRoute("0.0.0.0", 0)
                else picked.forEach { pkg ->
                    try { b.addAllowedApplication(pkg) } catch (_: Exception) {}
                }
                b.addRoute("0.0.0.0", 0)
            }

            // 端口模式：只截目标端口是 port 的连接。
            // Android 的 VpnService 不支持按端口分流，只能靠 hev 自己判断，
            // 所以这里路由照旧全给，真正生效的是下面 hev 配置里的 proxy 端口白名单。
            Mode.PORT -> b.addRoute("0.0.0.0", 0)
        }

        // 本 App 自己的流量必须绕开隧道，否则 cloudflared 走隧道会成环
        try { b.addDisallowedApplication(packageName) } catch (_: Exception) {}

        val pfd = try {
            b.establish()
        } catch (e: Exception) {
            lastError = "establish: ${e.javaClass.simpleName} ${e.message}"
            null
        } ?: return false
        tunnel = pfd

        // hev-socks5-tunnel 的配置：YAML，指向我们刚拉起来的本地 SOCKS5
        // hev 的配置。几个容易踩的点：
        //   ipv4 必须和 Builder.addAddress 里的地址一致，否则 tun2socks 认不出自己的口，
        //   包进来就被丢弃 —— 现象是「显示已连接，但所有应用都不通」。
        //   task-stack-size 官方默认 86016 字节，贪小（20480）会在高负载时栈溢出。
        val tunAddr = HEV_TUN_ADDR
        val cfg = File(filesDir, "hev.yml")
        cfg.writeText(
            """
            tunnel:
              mtu: 1400
              ipv4: $tunAddr
            socks5:
              port: 1080
              address: 127.0.0.1
              udp: 'udp'
              # pipeline：SOCKS5 握手不发包等回复，一路写到底，
              # 省掉一次往返。这条链路上一个往返就是几百毫秒，效果明显。
              pipeline: true
              # fastopen 在隧道上没意义（TCP 不是直达），关掉
              tcp-fastopen: false
            misc:
              # 官方口径：task-stack-size = 20480 + tcp-buffer-size。
              # tcp-buffer-size 默认只有 4096，一次性只能塞 4KB，
              # 高延迟链路上吞吐被这个死死卡住 —— 这就是速度上不去的直接原因之一。
              tcp-buffer-size: 65536
              task-stack-size: 86016
              # 每个会话要占一份 64KB buffer，1200 会话 = 73MB。
              # 手机上给 256 够用（浏览器并发也就几十），省内存给系统。
              max-session-count: 256
              connect-timeout: 15000
              log-level: warn
              log-file: ${File(filesDir, "hev.log").absolutePath}
            """.trimIndent()
        )
        return try {
            org.amnezia.awg.hevtunnel.TProxyService.TProxyStartService(cfg.absolutePath, pfd.fd)
            true
        } catch (e: Throwable) {
            lastError = "tun2socks: ${e.javaClass.simpleName} ${e.message}"
            false
        }
    }

    /** 每 2 秒看一次，把速率推给通知栏 */
    private suspend fun monitor(portMode: Boolean = false) {
        var prevRx = 0L
        var prevTx = 0L
        var prevT = System.currentTimeMillis()
        while (running) {
            delay(2000)
            val now = System.currentTimeMillis()
            val dt = (now - prevT) / 1000.0
            if (dt <= 0) continue
            val rx = android.net.TrafficStats.getTotalRxBytes()
            val tx = android.net.TrafficStats.getTotalTxBytes()
            val down = ((rx - prevRx) / dt / 1024 / 1024).coerceAtLeast(0.0)
            val up = ((tx - prevTx) / dt / 1024 / 1024).coerceAtLeast(0.0)
            prevRx = rx; prevTx = tx; prevT = now
            lastDown = down; lastUp = up
            updateNotification(
                "已连接",
                "↓ %.2f MB/s   ↑ %.2f MB/s".format(down, up),
                down.toFloat()
            )
            broadcastState("已连接", "↓ %.2f MB/s ↑ %.2f MB/s".format(down, up))
            if (cfProc?.isAlive == false) {
                broadcastState("失败", "cloudflared 已退出")
                stopVpn(); return
            }
        }
    }

    private fun broadcastState(state: String, detail: String) {
        val i = Intent(ACTION_STATE).setPackage(packageName)
        i.putExtra("state", state)
        i.putExtra("detail", detail)
        sendBroadcast(i)
    }

    private fun stopVpn(keepState: Boolean = false) {
        running = false
        try { org.amnezia.awg.hevtunnel.TProxyService.TProxyStopService() } catch (_: Exception) {}
        tunnel = null
        try { cfProc?.destroy() } catch (_: Exception) {}
        cfProc = null
        scope.coroutineContext.cancelChildren()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // 失败路径要把「失败」留在界面上，不能随手盖成「未连接」——
        // 否则用户只看到它自己关了，不知道原因。
        if (!keepState) broadcastState("未连接", "")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun buildNotification(title: String, text: String, progress: Float): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "VPN 状态", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(title: String, text: String, progress: Float) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(title, text, progress))
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    // 供 UI 读的瞬时值
    var lastDown = 0.0
    var lastUp = 0.0
}
