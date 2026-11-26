# CCMVPN

Android 上把 Kaggle 的出口当 VPN 用。

## 原理

```
App (VpnService TUN)
   ↓ tun2socks (hev-socks5-tunnel)
127.0.0.1:1080 (SOCKS5)
   ↓ cloudflared access tcp
Cloudflare Tunnel
   ↓
Kaggle notebook (microsocks)
   ↓
目标网站
```

## 前置

Kaggle 那边要跑一个 notebook，起 microsocks 并用 cloudflared 把 1080 送出来。
本 App 连的是那个隧道暴露的 hostname（默认 `socks.nexus-ai.kdns.fr`）。

## 编译

往 main 推代码，GitHub Actions 出 APK。

## 已知限制

- `cloudflared access tcp` 单连接约 0.67 MB/s，3 并发约 2 MB/s
- Kaggle 容器没有 CAP_NET_ADMIN，WireGuard/OpenVPN 在服务端跑不了，只能 userspace 代理
- 首次连接要 3 秒预热（cloudflared 和边缘握手）
