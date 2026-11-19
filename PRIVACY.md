# 大尾巴隐私守护 · 隐私政策

**应用名称**：大尾巴隐私守护（Daweiba Privacy Guard）
**包名**：`com.daweiba.privacyguard`
**开发者**：大尾巴（个人开发者）
**最后更新**：2026 年 11 月 19 日
**适用版本**：0.7.1 及以后

---

## 一句话说明

**这个应用默认不联网、不注册账号、不上传任何数据。所有分析都在你的手机本地完成。**

如果你只想看结论，到这里就够了。以下是完整说明。

---

## 1. 我们不收集任何数据

本应用：

- ❌ 不要求你注册或登录
- ❌ 不收集你的姓名、手机号、邮箱、位置、通讯录、照片
- ❌ 不集成任何第三方统计、广告或崩溃上报 SDK
- ❌ 不含任何用户画像、埋点或行为分析
- ❌ 不向任何服务器发送数据——**因为本应用根本没有服务器**

---

## 2. 应用在本地读取哪些信息

以下信息**只在你手机内部处理**，不会离开设备：

| 读取的内容 | 用途 | 是否上传 |
|---|---|---|
| 已安装应用列表（包名、是否为系统应用、签名摘要、申请的权限） | 判断哪些应用可能把你的数据传到别处，并给出卸载 / 停用建议 | **否** |
| 应用的 DNS 查询记录（仅在开启「泄露监控」时） | 判断某个应用是否正在连接已知的数据上报域名 | **否**，仅存在内存中 |
| 已安装应用安装包（APK）内的字符串（仅在你手动触发「深度扫描」时） | 查找应用里内置的上报域名与遥测关键词，作为「包内线索」展示 | **否**，只读，仅存在内存中 |

> 说明：读取已安装应用列表需要 `QUERY_ALL_PACKAGES` 权限。这是本应用**核心功能所必需**——如果不扫描全部应用，就无法告诉你哪个应用在偷偷上报数据。

---

## 3. 网络行为说明（重要）

本应用**默认不连接**任何自有或第三方服务器。以下两项网络行为必须如实告知：第一项在你开启「泄露监控」后自动发生；第二项**只有在你主动填写地址并点击检查时**才会发生。「可疑外联判定」与「深度扫描」都是纯本地分析，不产生任何网络请求。

**① 当你手动开启「泄露监控」后**，应用会建立一个本地 VPN 连接（Android `VpnService`），其作用是：

1. 在本机拦截设备内**其他应用**发出的 DNS 查询
2. 把查询转发到**你当前 Wi-Fi / 移动网络自动获取的 DNS 服务器**（例如运营商 DNS）
3. 在本地比对查询的域名是否命中已知上报域名库，命中则记录一条事件

之所以必须转发，是因为 Android 的 VPN 机制要求：一旦接管 DNS，就必须自己把查询送出去，否则你手机上所有应用都会无法上网。

本应用在这一过程中：

- ✅ 不修改 DNS 查询的内容
- ✅ 不记录你访问了哪些网站
- ✅ 不把查询记录写入文件或上传
- ✅ 不使用任何远程 DNS 服务

**你随时可以关闭泄露监控**，关闭后 VPN 立即断开，网络行为与未安装本应用时完全一致。

### 3.1 可选增强：Shizuku 与「一键停用」

v0.6.0 起，本应用可选地支持通过开源工具 [Shizuku](https://shizuku.rikka.app/) 直接停用 / 恢复预装应用。这一能力：

- **默认关闭**，只有在你主动安装 Shizuku、启动其服务并在结论页点过「授权」之后才会生效
- 属于**设备本地的进程间通信**，不涉及任何网络连接，不向 Shizuku 或我们发送任何数据
- 只执行两条固定命令：`pm disable-user --user 0 <包名>`（停用）与 `pm enable --user 0 <包名>`（恢复）；命令由代码固定，包名经字符白名单校验，不接受任意输入
- 系统核心组件、桌面启动器、本应用自身、Shizuku 自身均被列入禁止停用名单
- 停用前会弹出二次确认，并再次显示该应用的影响说明

即使不安装 Shizuku，本应用全部功能照常使用，结论页按钮只是退回「跳转到系统设置页手动停用」。

> 说明：本应用打包了 Shizuku 的开源客户端库（Apache-2.0 / MIT）用于本地通信。该库不含统计、广告或联网代码，也不读取你的数据。

### 3.2 可选：手动更新域名知识库（v0.6.1 起）

首页底部提供「知识库更新」入口。**默认完全不联网**——更新地址为空，应用不会自行发起任何网络请求。

只有当你**主动填入一个 `https://` 地址并点「检查更新」**时，才会发生一次网络请求：

- 只向**你填写的那个地址**发起一次 HTTPS GET，下载一份域名清单（JSON）
- 请求中**不携带**设备信息、已安装应用列表、DNS 记录或任何标识符
- 不解析、不执行下载内容里的代码；只读取 `domains` 数组中的域名字符串，并校验域名格式
- 校验通过的条目合并进本地知识库，仅用于泄露监控比对；可随时点「清除自定义」恢复内置版本
- 不向任何自有或第三方服务器发送任何数据，不含统计 / 广告 / 崩溃上报

如果你不填地址、不点检查，本应用的网络行为与 0.6.0 完全一致。

> 该地址由你自己提供，其内容可信度由你自行判断；应用**不预设、不内置**任何更新服务器。

---

## 4. 数据存储与保留

| 数据 | 存储位置 | 保留时间 |
|---|---|---|
| 「域名拦截」开关状态 | 手机本地（SharedPreferences，仅一个布尔值） | 直到你卸载应用 |
| 泄露事件日志 | **仅内存**，最多 500 条 | 关闭应用即清空 |
| 扫描结果 | **仅内存** | 关闭应用即清空 |
| 由本应用停用过的包名列表（仅启用可选增强后） | 手机本地（SharedPreferences） | 直到你点「恢复」或卸载应用 |
| 自定义域名条目与更新地址（仅在你主动更新后） | 手机本地（SharedPreferences） | 直到你点「清除自定义」或卸载应用 |
| 系统版本指纹与上次体检时间（OTA 复查提醒） | 手机本地（SharedPreferences） | 直到你卸载应用 |
| 深度扫描得到的包内线索 | **仅内存** | 关闭应用即清空 |

**除上述几个本地开关与清单外，没有任何数据被写入外部存储、云盘或服务器。卸载应用即彻底删除全部数据。**

---

## 5. 第三方共享

**没有。** 本应用不与任何第三方共享、出售或转让任何信息。

---

## 6. 权限说明

| 权限 | 为什么需要 |
|---|---|
| `QUERY_ALL_PACKAGES` | 扫描全部已安装应用，这是核心功能 |
| `INTERNET` | ① 把 DNS 查询转发给你所在网络的 DNS 服务器（见第 3 节）；② 仅在你主动发起时执行知识库更新请求（见 3.2） |
| `FOREGROUND_SERVICE`<br>`FOREGROUND_SERVICE_SPECIAL_USE` | 让泄露监控在后台持续运行，并在通知栏显示运行状态 |
| `POST_NOTIFICATIONS` | 显示泄露监控的运行通知（Android 13+） |
| `BIND_VPN_SERVICE` | 建立本地 DNS 监控通道，需由你手动授权 |
| `moe.shizuku.manager.permission.API_V23` | 可选：向 Shizuku 申请本地 shell 身份，用于一键停用 / 恢复；不授权则该功能不可用 |

本应用**不申请**：位置、通讯录、短信、通话记录、相机、麦克风、存储读写、无障碍服务。

---

## 7. 儿童隐私

本应用不面向 13 岁以下儿童，也不会有意收集儿童信息（事实上不收集任何人的信息）。

---

## 8. 你的权利

由于本应用不保存任何可识别你身份的数据，你无需行使「查询 / 更正 / 导出」权利。**卸载应用即等于删除全部数据。**

如果你想停止一切后台行为：关闭「泄露监控」开关，或在系统设置中撤销本应用的 VPN 授权。若曾填写过更新地址，可在「知识库更新」中点「清除自定义」。

---

## 9. 政策变更

本政策如有修改，会更新本文档顶部的「最后更新」日期，并在应用的新版本说明中注明。若涉及数据处理的实质性变化，会在应用内提示。

---

## 10. 联系方式

如对本政策有疑问，请联系：

**邮箱**：`qikuanhe@163.com`

---

# Privacy Policy (English)

**App**: Daweiba Privacy Guard
**Package**: `com.daweiba.privacyguard`
**Developer**: Daweiba (individual developer)
**Last updated**: November 19, 2026
**Applies to**: 0.7.1 and later

## Summary

**By default this app makes no network connections, has no accounts, and uploads nothing.** All analysis happens on your device.

## 1. Data We Collect

None. No account, no cloud service, no analytics SDK, no advertising SDK, no crash reporting SDK.

## 2. Data Read Locally on Your Device

| Data | Purpose | Uploaded? |
|---|---|---|
| Installed app list (package name, system flag, signature digest, requested permissions) | Identify apps that may leak your data and suggest actions | **No** |
| DNS queries per app (only while Leak Monitor is on) | Detect connections to known telemetry domains | **No**, memory only |
| Strings inside installed APKs (only when you manually run Deep scan) | Find embedded reporting endpoints as in-package clues | **No**, read-only, memory only |

## 3. Network Behavior

By default the app does **not** connect to any server operated by us or any third party. Two network behaviors are disclosed below: the first happens automatically once you enable Leak Monitor; the second happens **only when you explicitly enter an address and tap Check for updates**. Heuristic suspicious-outbound detection and Deep scan are purely local and make no network requests.

**1) Leak Monitor.** When you manually enable it, the app establishes a local VPN (`VpnService`) that:

1. Captures DNS queries sent by **other apps** on your device,
2. Forwards them to the DNS server **your current network provides** (e.g. your carrier's DNS), and
3. Compares queried domains against a built-in list of known telemetry domains locally.

Forwarding is mandatory: Android requires a VPN that takes over DNS to also deliver the queries, otherwise no app on your device could resolve names. The app does **not** modify queries, log browsing history, write query logs to disk, or use any remote DNS service. Disabling Leak Monitor immediately tears down the VPN.

### 3.1 Optional: Shizuku and one-tap disable

Since 0.6.0 the app can optionally use the open-source tool [Shizuku](https://shizuku.rikka.app/) to disable / re-enable preinstalled apps directly.

- **Off by default**: only active after you install Shizuku, start its service, and grant permission once from the report page
- Purely **local inter-process communication** — no network involved, nothing sent to Shizuku or to us
- Only two fixed commands are executed: `pm disable-user --user 0 <pkg>` and `pm enable --user 0 <pkg>`. Commands are hard-coded; package names are validated against a character allowlist; no arbitrary input is accepted
- System core components, the launcher, this app itself, and Shizuku itself are on a do-not-disable list
- A confirmation dialog showing the app's impact note appears before every disable

Without Shizuku, every feature still works — the button simply falls back to opening the system app settings page.

> The app bundles Shizuku's open-source client libraries (Apache-2.0 / MIT) for local communication. They contain no analytics, advertising, or networking code and do not read your data.

### 3.2 Optional: manual knowledge-base update (since 0.6.1)

A "Knowledge base update" entry is available at the bottom of the home screen. **It is offline by default** — the update URL is empty and the app never initiates a request on its own.

Only when you **explicitly enter an `https://` URL and tap Check for updates** does one request occur:

- A single HTTPS GET to **the address you entered**, downloading a JSON list of domains
- The request carries **no** device information, installed-app list, DNS records, or identifiers
- Downloaded content is never executed; only domain strings in the `domains` array are read and validated
- Valid entries are merged into the local knowledge base for leak monitoring only; "Clear custom" restores the built-in list at any time
- Nothing is sent to any server of ours or any third party; no analytics, ads, or crash reporting

If you never enter an address or tap the button, network behavior is identical to 0.6.0.

> You supply the URL yourself and are responsible for trusting its contents; the app ships with **no** built-in update server.

## 4. Storage and Retention

| Data | Location | Retention |
|---|---|---|
| Domain-blocking toggle | Device local (SharedPreferences, one boolean) | Until uninstall |
| Leak event log | **Memory only**, max 500 entries | Cleared when app closes |
| Scan results | **Memory only** | Cleared when app closes |
| Packages disabled by this app (only with the optional enhancement) | Device local (SharedPreferences) | Until you tap Restore or uninstall |
| Custom domain entries and update URL (only after a manual update) | Device local (SharedPreferences) | Until you tap Clear custom or uninstall |
| System build fingerprint and last scan time (OTA re-check reminder) | Device local (SharedPreferences) | Until uninstall |
| In-package clues from Deep scan | **Memory only** | Cleared when app closes |

No data is written to external storage, cloud, or any server. Uninstalling the app deletes everything.

## 5. Third-Party Sharing

None. We do not share, sell, or transfer any information.

## 6. Permissions

| Permission | Why |
|---|---|
| `QUERY_ALL_PACKAGES` | Scanning all installed apps is the app's core function |
| `INTERNET` | ① Forwarding DNS queries to your network's DNS server (§3); ② only when you explicitly start a knowledge-base update (§3.2) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep Leak Monitor running with a visible notification |
| `POST_NOTIFICATIONS` | Show the Leak Monitor status notification (Android 13+) |
| `BIND_VPN_SERVICE` | Establish the local DNS monitoring tunnel, granted by you |
| `moe.shizuku.manager.permission.API_V23` | Optional: request a local shell identity from Shizuku for one-tap disable / restore; unused without your grant |

The app does **not** request: location, contacts, SMS, call logs, camera, microphone, storage, or accessibility service.

## 7. Children's Privacy

Not directed to children under 13. We collect no information from anyone.

## 8. Your Rights

Since no personally identifiable data is stored, there is nothing to query, correct, or export. **Uninstalling the app deletes all data.** To stop all background activity, turn off Leak Monitor or revoke the VPN grant; if you ever entered an update URL, tap "Clear custom" in the knowledge-base dialog.

## 9. Changes

Material changes will update the "Last updated" date above and be noted in the release notes.

## 10. Contact

**Email**: `qikuanhe@163.com`

---

*本文档可自由转载，无需授权。 / This document may be redistributed freely.*
