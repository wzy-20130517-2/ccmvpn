# CCMVPN

Android 上把 Kaggle 的出口当 VPN 用。

## 原理

```
目标 App
   ↓ VpnService (TUN 198.18.0.1)
hev-socks5-tunnel (tun2socks)
   ↓ SOCKS5 127.0.0.1:1080
cloudflared access tcp
   ↓ WebSocket over HTTP/2
Cloudflare 边缘
   ↓ QUIC
Kaggle notebook 里的 cloudflared
   ↓
microsocks
   ↓
目标网站
```

## 前置

Kaggle 那边要跑一个 notebook，起 microsocks（监听 1080）并用 cloudflared 把 1080 映射出来。
App 连的是隧道暴露的 hostname（默认 `socks.nexus-ai.kdns.fr`）。

## 三种模式

| 模式 | 做法 | 说明 |
|---|---|---|
| 全局 | VpnService 接管整机 | 所有 App 都走隧道 |
| 分应用 | `addAllowedApplication` | 默认全勾，取消的不走 |
| 端口 | 不建 TUN，启动本地 SOCKS5 | 只有把代理设成 `127.0.0.1:<port>` 的应用生效 |

端口模式不建 TUN 是因为 Android 的 VpnService 只能按应用放行，没法按目标端口过滤。

## 实测性能（2026-11-28）

| 场景 | 速度 |
|---|---|
| 单次 2MB | 268 ~ 487 KB/s |
| 单次 10MB | **56 KB/s**（掉速 7 倍） |
| 波动范围 | 0 ~ 862 KB/s |

**小请求（网页、API）能用，大流量（视频）不行。**

## 为什么上不去

三条都是外部的、改不了：

1. **地理路径** —— Kaggle 机器在哪，Cloudflare 就连哪的边缘。实测分到布鲁塞尔的机器时连巴黎边缘，中国到巴黎绕半个地球。
2. **Cloudflare 免费版对非 HTML 流量限速** —— 单次传输超过 2MB 左右就触发降速。这是策略限制，改参数没用。
3. **`cloudflared access tcp` 只能走 HTTP/2** —— 底层是 TCP，丢包就拥塞窗口崩溃，长传输掉速。

## 已经做的优化

- `tcp-buffer-size` 4096 → **65536**（默认一次只能塞 4KB，高延迟链路上吞吐被卡死）
- SOCKS5 **pipeline** 开启（握手不等回复，省一次往返）
- MTU 1500 → **1400**（隧道场景避免分片）
- TUN 地址与 hev 配置对齐（`198.18.0.1`），否则 tun2socks 认不出自己的口，包全被丢弃
- DNS 用公共服务器（`1.1.1.1` / `8.8.8.8`），由 hev 通过 SOCKS5 UDP 转发
- App 自己的 IP 查询显式走 `127.0.0.1:1080`（App 被 `addDisallowedApplication` 排除，不显式走代理就会查到本机 IP）

## 编译

往 main 推代码，GitHub Actions 出 APK。

## 几个踩过的坑

- **Android 14+ 的 `startForeground()` 必须传 service type**，否则系统几秒后直接杀进程，现象是「点了连接，3 秒自己关了」。
- **Android 11+ 包可见性**：不声明 `<queries>` 时 `queryIntentActivities` 只返回 9 个左右的应用，跟权限无关。
- **CI 的 Gradle 版本**：runner 预装的是 9.x，AGP 8.x 用不了（Gradle 9.6 删了 AGP 依赖的内部 API）。workflow 里显式下 8.9 的 wrapper。
- **Kotlin 版本**：`hevtunnel` 是 Kotlin 2.2 编译的，项目用 2.1 读不了它的元数据。
