# 无线中继接入 · 交付变更摘要

**日期**：2026-11-23
**提交**：`f1ccaff` → `origin/master`
**仓库**：`onebody/QiYun-RMC58-App`
**版本**：0.9（versionCode=9）

---

## 一、任务起源

让 Android 应用在原有 USB CDC 串口接入之外，新增一条 **无线中继** 路径——ESP32-S3 Wi-Fi 中继节点把 C5 探头的原始 UART 字节通过 WebSocket 转发过来，手机与中继同 Wi-Fi（路由器或手机热点）即可，**不改动固件**（协议冻结），**不新增依赖**，**不新增权限**。

## 二、架构决策

### 2.1 为什么手机做服务端

固件 relay 是 WebSocket **客户端**，主动拨号 `ws://<host>:<port>/node?id=widscan58-xxxx`，没有监听服务端的代码。因此 App 必须充当 serve.mjs 的 `/node` 端。

### 2.2 关键实现点

| 项 | 说明 |
|---|---|
| 监听端口 | `0.0.0.0:8080`，可在配置页修改（范围 1024–65535） |
| WS 入站单帧上限 | 固件 RX_MAX_ITEM = 128 字节，App 出站按 **≤120B 分帧** |
| WS 出站写入 | **单线程 writer + LinkedBlockingQueue**，规避 Android StrictMode `NetworkOnMainThreadException`（CommandRunner 在主线程 pump 命令） |
| 重拨替换 | 新连接自动 `finish()` 旧 peer（原因码 `"replaced"`），与 serve.mjs 行为一致 |
| 空闲保活 | 15 秒无帧发 ping，3 次静默断开 |
| REBOOT 保护 | 中继链路下发 `HEAD REBOOT`，让 relay 显式转发给 C5，避免误重启中继节点本身 |
| 握手命令 | `PROTOCOL MONITOR` → `SCAN STOP` → `restoreCommands()` → `SPEC` → `PROFILE?` → `STATUS?` → `SPURS?` → `SCAN START` → `INFO`，USB 与中继共用同一套 |

## 三、文件清单

### 3.1 新增（6 个）

```
mobile/src/main/java/com/onebody/widscan58/BoardLink.kt          (+17)  USB/中继统一链路接口
mobile/src/main/java/com/onebody/widscan58/RelaySheet.kt         (+120) 监听面板 UI
mobile/src/main/java/com/onebody/widscan58/relay/RelayServer.kt  (+435) RFC6455 服务端 + Peer
mobile/src/main/java/com/onebody/widscan58/relay/RelayLink.kt    (+29)  120B 分帧 Relay
mobile/src/test/java/.../relay/RelayServerTest.kt                (+177) 握手/帧/ping/重拨/1024B 大帧
```

### 3.2 修改（5 个）

```
core/.../Store.kt                 +24/-3   网络输入帧加 try-catch 防数组越界
mobile/.../Widscan58Activity.kt  +114/-1   菜单/生命周期/attachRelay 握手
mobile/.../UsbSerialLink.kt       +5/-8    实现 BoardLink 接口
mobile/.../Prefs.kt               +13      relayPort / relayListen 偏好
mobile/.../strings.xml (×2)       +36      中英文化文本
```

**合计**：11 个文件，**+958 / -20**

## 四、修复的 Bug

| 问题 | 原因 | 修复 |
|---|---|---|
| `NetworkOnMainThreadException` | CommandRunner 在主线程 pump 命令，WS 写直接操作 socket | Peer 增加 writer 线程 + LinkedBlockingQueue，`send()` 仅入队 |
| 畸形帧数组越界 | CRC 通过但声明 payload 长度超出实际载荷，`parseSpectrum` 越界 | Store.ingest 对 `StreamItem.Frame` 分支整体 try-catch，记 ERROR 日志，不 crash UI |

## 五、测试覆盖

| 层级 | 用例 | 结果 |
|---|---|---|
| JVM 单元 | `:core:test` | ✅ 通过 |
| JVM 单元 | `:mobile:testDebugUnitTest`（含 RelayServerTest 5 个用例） | ✅ 通过 |
| 模拟器端到端 | 握手 → 扫描 → 断开 → 重拨 | ✅ 完整握手 12 条命令，频谱/通道/遥测三类帧正常绘制，无看门狗重触发 |
| 帧边界 | CHUNK=1024B（对齐固件 PIPE_CHUNK）连续 20s | ✅ 80 轮数据无异常 |
| 真机实测 | 用户确认 | ✅ 功能可用 |
| 崩溃检查 | logcat FATAL/AndroidRuntime | ✅ 无 |

## 六、安全治理

| 检查项 | 范围 | 结果 |
|---|---|---|
| 凭据关键词 | token / secret / password / key / authorization / credential | ✅ 空命中 |
| 硬编码密钥格式 | AKIA… / sk-… / ghp_… / xox-… / JWT | ✅ 空命中 |
| 手机号 | 1[3-9]\d{9} | ✅ 空命中 |
| 邮箱 | [a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,} | ✅ 空命中 |
| keystore.properties 是否已入库 | git 历史 | ✅ 从未提交过 |
| .gitignore 覆盖 | keystore.properties / *.jks / *.keystore / *.p12 / local.properties | ✅ 已在跟踪列表，完整覆盖 |

## 七、交付产物

| 产物 | 路径 | 说明 |
|---|---|---|
| Release APK | `mobile/build/outputs/apk/release/mobile-release.apk` | 1.5 MB，release 签名，已安装到 emulator-5554 |
| Debug APK | `mobile/build/outputs/apk/debug/mobile-debug.apk` | 开发版 |
| GitHub 提交 | `onebody/QiYun-RMC58-App@f1ccaff` | 已推送 |

## 八、已知未覆盖

- **真实中继硬件联调**：需用户接上 ESP32-S3 relay 实际拨号（用户已验证功能可用）
- **USB 路径回归**：模拟器没有 USB host 设备，无法自动化覆盖；但 `assembleDebug`/`testDebugUnitTest` 通过，且本次未改 USB 传输逻辑

## 九、未改动清单（确认未越界）

- 固件仓库 `repo/`：**未改动**（协议冻结）
- 应用权限 `AndroidManifest.xml`：**未新增**（仅已有 INTERNET）
- Gradle 依赖：**未新增**
- 不相关业务逻辑（SpectrumParser、PanoramaView、所有 Sheet）：**未改动**
