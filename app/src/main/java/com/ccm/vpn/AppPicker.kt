package com.ccm.vpn

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PickerBgTop = Color(0xFF0B1020)
private val PickerBgBottom = Color(0xFF161B33)
private val PickerAccent = Color(0xFF4ADE80)

data class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?)

/**
 * 只列「有启动图标」的应用。
 * 分应用代理真正能选的是所有包，但没图标的应用用户看不见也理解不了，
 * 列出来只会把列表撑爆。
 */
private suspend fun loadLaunchableApps(ctx: Context): List<AppEntry> = withContext(Dispatchers.IO) {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    pm.queryIntentActivities(intent, 0)
        .mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == ctx.packageName) return@mapNotNull null
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
            val icon = runCatching {
                ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
            AppEntry(pkg, label, icon)
        }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

@Composable
fun AppPickerScreen(ctx: Context, onBack: () -> Unit) {
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    BackHandler(enabled = true) { onBack() }

    LaunchedEffect(Unit) {
        val loaded = loadLaunchableApps(ctx)
        apps = loaded
        // 第一次进来：全部勾上。之前存过就用存的。
        selected = ModeStore.apps(ctx) ?: loaded.map { it.pkg }.toSet()
    }

    // 每次勾选就落盘，退出时不用再存一遍
    fun persist(s: Set<String>) {
        selected = s
        ModeStore.setApps(ctx, s)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(PickerBgTop, PickerBgBottom)))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 44.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "返回", tint = Color.White)
            }
            Text(
                "选择应用",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                val all = apps?.map { it.pkg }?.toSet() ?: emptySet()
                persist(if (selected.size == all.size) emptySet() else all)
            }) {
                Text(
                    if (apps != null && selected.size == apps!!.size) "全不选" else "全选",
                    fontSize = 13.sp, color = PickerAccent
                )
            }
        }

        Text(
            "已选 ${selected.size} / ${apps?.size ?: 0} —— 只有勾选的应用走隧道",
            fontSize = 12.sp,
            color = Color(0xFF8B93A7),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        Spacer(Modifier.height(4.dp))

        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PickerAccent, strokeWidth = 2.dp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.pkg }) { app ->
                    val checked = app.pkg in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.White.copy(alpha = 0.06f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            if (app.icon != null) {
                                Image(
                                    bitmap = app.icon,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(6.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontSize = 15.sp, color = Color.White,
                                maxLines = 1)
                            Text(app.pkg, fontSize = 11.sp, color = Color(0xFF4B5563),
                                maxLines = 1)
                        }
                        Switch(
                            checked = checked,
                            onCheckedChange = { on ->
                                persist(if (on) selected + app.pkg else selected - app.pkg)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF07101F),
                                checkedTrackColor = PickerAccent,
                                uncheckedThumbColor = Color(0xFF6B7280),
                                uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                            )
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
