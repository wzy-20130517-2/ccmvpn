package com.ccm.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
        const val ACTION_STATE = "com.ccm.vpn.STATE"
        const val ACTION_START = "com.ccm.vpn.START"
        const val ACTION_STOP = "com.ccm.vpn.STOP"

        // 由 hostname 决定，App 里写死一个可配置的值
        const val PREFS = "ccmvpn"
        const val KEY_HOSTNAME = "hostname"
        const val DEFAULT_HOSTNAME = "socks.nexus-ai.kdns.fr"
    }

    private var tunnel: ParcelFileDescriptor? = null
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
        startForeground(NOTIF_ID, buildNotification("预热中…", "正在连接 Kaggle 节点", 0f))

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
                    stopVpn(); return@launch
                }
                broadcastState("预热", "SOCKS5 就绪，建立 TUN")

                if (!establishTun()) {
                    broadcastState("失败", "TUN 建立失败")
                    stopVpn(); return@launch
                }

                broadcastState("已连接", "流量已接管")
                updateNotification("已连接", "$host", 0f)
                monitor()
            } catch (e: Exception) {
                Log.e(TAG, "启动失败", e)
                broadcastState("失败", e.message ?: "未知错误")
                stopVpn()
            }
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
        Log.i(TAG, "cloudflared pid=${cfProc?.pid()}")
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
        val b = Builder()
            .setSession("CCMVPN")
            .setMtu(1500)
            .addAddress("10.8.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("10.8.0.1")     // 交给 hev 自己处理，别设公网 DNS 否则解析绕不出去
            .setBlocking(true)
        // 本 App 自己的流量必须绕开隧道，否则 cloudflared 走隧道会成环
        try { b.addDisallowedApplication(packageName) } catch (_: Exception) {}
        val pfd = b.establish() ?: return false
        tunnel = pfd

        // hev-socks5-tunnel 的配置：YAML，指向我们刚拉起来的本地 SOCKS5
        val cfg = File(filesDir, "hev.yml")
        cfg.writeText(
            """
            tunnel:
              mtu: 1500
            socks5:
              port: 1080
              address: 127.0.0.1
              udp: 'udp'
            misc:
              task-stack-size: 20480
              connect-timeout: 12000
              log-level: warn
              log-file: ${File(filesDir, "hev.log").absolutePath}
            """.trimIndent()
        )
        return try {
            val ok = hev.htproxy.TProxyService.TProxyStartService(
                cfg.absolutePath, pfd.fd
            )
            Log.i(TAG, "tun2socks start=$ok")
            ok
        } catch (e: Throwable) {
            Log.e(TAG, "tun2socks 起不来", e)
            false
        }
    }

    /** 每 2 秒看一次，把速率推给通知栏 */
    private suspend fun monitor() {
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

    private fun stopVpn() {
        running = false
        try { hev.htproxy.TProxyService.TProxyStopService() } catch (_: Exception) {}
        tunnel = null
        try { cfProc?.destroy() } catch (_: Exception) {}
        cfProc = null
        scope.coroutineContext.cancelChildren()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        broadcastState("未连接", "")
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
