package com.ccm.vpn

import android.app.Activity
import android.content.*
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// 深空蓝 → 靛紫，比纯黑有层次
private val BgTop = Color(0xFF0B1020)
private val BgBottom = Color(0xFF161B33)
private val AccentOn = Color(0xFF4ADE80)
private val AccentOff = Color(0xFF64748B)
private val Warm = Color(0xFFFBBF24)

class MainActivity : ComponentActivity() {

    private var receiver: BroadcastReceiver? = null
    private val stateFlow = mutableStateOf("未连接")
    private val detailFlow = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                stateFlow.value = i?.getStringExtra("state") ?: "未连接"
                detailFlow.value = i?.getStringExtra("detail") ?: ""
            }
        }
        registerReceiver(receiver, IntentFilter(CcmVpnService.ACTION_STATE),
            Context.RECEIVER_NOT_EXPORTED)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Screen(stateFlow.value, detailFlow.value)
            }
        }
    }

    override fun onDestroy() {
        receiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    @Composable
    private fun Screen(state: String, detail: String) {
        val ctx = LocalContext.current
        var ip by remember { mutableStateOf<String?>(null) }
        var ipLoading by remember { mutableStateOf(false) }
        var warmup by remember { mutableStateOf(0) }

        val on = state == "已连接"
        val warming = state == "预热"

        val permLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { res ->
            if (res.resultCode == Activity.RESULT_OK) startVpn(ctx)
        }

        // 预热倒计时：仅用于界面提示，实际就绪由 Service 的探活决定
        LaunchedEffect(state) {
            if (state == "预热") {
                warmup = 3
                while (warmup > 0) { delay(1000); warmup-- }
            } else warmup = 0
        }

        // 用计数器当 key：连上状态变化、或用户点刷新，都会重查一次
        var refreshTick by remember { mutableStateOf(0) }
        LaunchedEffect(on, refreshTick) {
            if (!on) { ip = null; return@LaunchedEffect }
            ipLoading = true
            ip = fetchIp()
            ipLoading = false
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(56.dp))
                Text("CCMVPN", fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, letterSpacing = 2.sp)
                Spacer(Modifier.height(6.dp))
                Text("Kaggle 出口", fontSize = 13.sp, color = Color(0xFF8B93A7))

                Spacer(Modifier.weight(1f))

                // 主按钮：呼吸光圈 + 电源图标
                PowerButton(
                    on = on, warming = warming,
                    onClick = {
                        if (on) {
                            ctx.startService(Intent(ctx, CcmVpnService::class.java)
                                .setAction(CcmVpnService.ACTION_STOP))
                        } else {
                            val prep = VpnService.prepare(ctx)
                            if (prep != null) permLauncher.launch(prep) else startVpn(ctx)
                        }
                    }
                )

                Spacer(Modifier.height(28.dp))
                Text(
                    when {
                        warming -> "预热中 $warmup"
                        on -> "已连接"
                        state == "失败" -> "失败"
                        else -> "未连接"
                    },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        warming -> Warm
                        on -> AccentOn
                        state == "失败" -> Color(0xFFF87171)
                        else -> AccentOff
                    }
                )
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(detail, fontSize = 12.sp, color = Color(0xFF6B7280),
                        fontFamily = FontFamily.Monospace)
                }

                Spacer(Modifier.weight(1f))

                InfoCard(
                    ip = ip, loading = ipLoading,
                    onRefresh = { refreshTick++ }
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun PowerButton(on: Boolean, warming: Boolean, onClick: () -> Unit) {
        val pulse = rememberInfiniteTransition(label = "p")
        val ring by pulse.animateFloat(
            1f, if (on) 1.35f else 1.12f,
            infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing),
                RepeatMode.Reverse), label = "ring"
        )
        val ringAlpha by pulse.animateFloat(
            if (on) 0.35f else 0.18f, 0.05f,
            infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "a"
        )
        val color = when {
            warming -> Warm
            on -> AccentOn
            else -> AccentOff
        }

        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(150.dp)
                    .scale(ring)
                    .alpha(ringAlpha)
                    .background(color, CircleShape)
            )
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = color.copy(alpha = 0.14f),
                modifier = Modifier.size(130.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Surface(shape = CircleShape, color = color, modifier = Modifier.size(72.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PowerSettingsNew, null,
                                tint = Color(0xFF07101F), modifier = Modifier.size(34.dp))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun InfoCard(ip: String?, loading: Boolean, onRefresh: () -> Unit) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White.copy(alpha = 0.05f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("当前 IP", fontSize = 12.sp, color = Color(0xFF8B93A7))
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        loading -> "查询中…"
                        ip == null -> "—"
                        else -> ip
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = onRefresh, contentPadding = PaddingValues(0.dp)) {
                    Text("刷新", fontSize = 13.sp, color = Color(0xFF60A5FA))
                }
            }
        }
    }

    private fun startVpn(ctx: Context) {
        val i = Intent(ctx, CcmVpnService::class.java)
            .setAction(CcmVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i)
        } else {
            ctx.startService(i)
        }
    }
}

/** 直接问 ipinfo；失败返回 null，UI 显示 — */
private suspend fun fetchIp(): String? = withContext(Dispatchers.IO) {
    try {
        val c = URL("https://ipinfo.io/ip").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.requestMethod = "GET"
        c.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}
