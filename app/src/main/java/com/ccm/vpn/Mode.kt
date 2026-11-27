package com.ccm.vpn

/**
 * 三种分流方式，存 SharedPreferences：
 *   GLOBAL  —— 整机所有流量进隧道
 *   APPS    —— 只有勾选的应用进隧道（默认全勾，取消的不走）
 *   PORT    —— 只有目标端口是 [port] 的连接进隧道，其余直连
 */
enum class Mode { GLOBAL, APPS, PORT }

object ModeStore {
    const val PREFS = "ccmvpn"
    const val KEY_MODE = "mode"
    const val KEY_PORT = "port"
    const val KEY_APPS = "apps"        // 勾选的包名，逗号分隔
    const val KEY_HOSTNAME = "hostname"
    const val DEFAULT_HOSTNAME = "socks.nexus-ai.kdns.fr"

    fun mode(ctx: android.content.Context): Mode {
        val v = ctx.getSharedPreferences(PREFS, 0).getString(KEY_MODE, null)
        return runCatching { Mode.valueOf(v ?: "GLOBAL") }.getOrDefault(Mode.GLOBAL)
    }

    fun setMode(ctx: android.content.Context, m: Mode) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_MODE, m.name).apply()
    }

    fun port(ctx: android.content.Context): Int =
        ctx.getSharedPreferences(PREFS, 0).getInt(KEY_PORT, 8080)

    fun setPort(ctx: android.content.Context, p: Int) {
        ctx.getSharedPreferences(PREFS, 0).edit().putInt(KEY_PORT, p).apply()
    }

    /** null = 还没设置过，交给调用方默认全选 */
    fun apps(ctx: android.content.Context): Set<String>? {
        val v = ctx.getSharedPreferences(PREFS, 0).getString(KEY_APPS, null) ?: return null
        return v.split(",").filter { it.isNotBlank() }.toSet()
    }

    fun setApps(ctx: android.content.Context, pkgs: Set<String>) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_APPS, pkgs.joinToString(",")).apply()
    }

    fun hostname(ctx: android.content.Context): String =
        ctx.getSharedPreferences(PREFS, 0).getString(KEY_HOSTNAME, DEFAULT_HOSTNAME) ?: DEFAULT_HOSTNAME
}
