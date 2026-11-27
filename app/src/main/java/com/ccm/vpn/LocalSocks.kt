package com.ccm.vpn

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 端口模式用的本地 SOCKS5 服务。
 *
 * 为什么不走 VpnService：Android 的 VpnService 只能按「应用」放行流量，
 * 没法按目标端口过滤。要「只有连某个端口的才生效」，只能自己起个代理，
 * 让需要的应用把代理设成 127.0.0.1:<port>。
 *
 * 上游是 cloudflared 提供的 127.0.0.1:1080（它也支持 SOCKS5），
 * 所以这里对上游其实只做透传 —— 但必须自己实现握手，
 * 因为要把「域名交给上游解析」而不是本地解析（本地 DNS 会被污染）。
 */
class LocalSocks(
    private val listenPort: Int,
    private val upstreamPort: Int = 1080,
    private val upstreamHost: String = "127.0.0.1",
) {
    private companion object { const val TAG = "CCMVPN-SOCKS" }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start(): Boolean {
        return try {
            server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", listenPort))
            }
            running = true
            thread(name = "socks-accept", isDaemon = true) { acceptLoop() }
            Log.i(TAG, "监听 127.0.0.1:$listenPort")
            true
        } catch (e: Exception) {
            Log.e(TAG, "起不来", e)
            false
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop() {
        while (running) {
            val c = try { server?.accept() } catch (_: Exception) { null } ?: continue
            thread(isDaemon = true) { handle(c) }
        }
    }

    private fun handle(client: Socket) {
        client.use { cli ->
            val inS = cli.getInputStream()
            val outS = cli.getOutputStream()

            // ── 握手：客户端发 VER NMETHODS METHODS，我们回 05 00（无需认证）
            val hdr = ByteArray(2).also { readFully(inS, it) }
            if (hdr[0].toInt() != 5) return
            val n = hdr[1].toInt() and 0xff
            readFully(inS, ByteArray(n))
            outS.write(byteArrayOf(5, 0)); outS.flush()

            // ── 请求：VER CMD RSV ATYP DST.ADDR DST.PORT
            val req = ByteArray(4).also { readFully(inS, it) }
            if (req[0].toInt() != 5 || req[1].toInt() != 1) {   // 只支持 CONNECT
                outS.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); outS.flush()
                return
            }
            val atyp = req[3].toInt() and 0xff
            val addr: ByteArray = when (atyp) {
                1 -> ByteArray(4).also { readFully(inS, it) }          // IPv4
                3 -> {                                                   // 域名
                    val l = ByteArray(1).also { readFully(inS, it) }
                    ByteArray(l[0].toInt() and 0xff).also { readFully(inS, it) }
                }
                4 -> ByteArray(16).also { readFully(inS, it) }         // IPv6
                else -> return
            }
            val portB = ByteArray(2).also { readFully(inS, it) }

            // ── 连上游，把原始请求原样发过去（域名让它自己解析）
            val up = try {
                Socket().apply {
                    connect(InetSocketAddress(upstreamHost, upstreamPort), 10000)
                    soTimeout = 0
                }
            } catch (e: Exception) {
                outS.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); outS.flush()
                return
            }
            up.use { upstream ->
                val uOut = upstream.getOutputStream()
                val uIn = upstream.getInputStream()
                uOut.write(req); uOut.write(addr); uOut.write(portB); uOut.flush()

                // 上游回 10 字节应答，原样转给客户端
                val resp = ByteArray(10).also { readFully(uIn, it) }
                outS.write(resp); outS.flush()
                if (resp[1].toInt() != 0) return

                // ── 双向透传
                val t1 = thread(isDaemon = true) {
                    runCatching { pipe(inS, uOut) }
                }
                runCatching { pipe(uIn, outS) }
                t1.join(5000)
            }
        }
    }

    private fun pipe(from: InputStream, to: OutputStream) {
        val buf = ByteArray(16384)
        while (true) {
            val n = from.read(buf)
            if (n <= 0) break
            to.write(buf, 0, n); to.flush()
        }
    }

    private fun readFully(s: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = s.read(buf, off, buf.size - off)
            if (n <= 0) throw java.io.IOException("eof")
            off += n
        }
    }
}
