# 猫猫去广告（MaomaoDNS）

手机上一键开关「私人 DNS 广告拦截」的小 App。

---

## 装了没有？

打开手机桌面，找 **「猫猫去广告」**（绿色圆形图标，白色盾牌）。

---

## 怎么用

打开 App，就一个大按钮：

- 显示 **已开启**（绿色）→ 广告拦截生效中，点红色按钮「关掉它」可关闭
- 显示 **已关闭**（灰色）→ 没有拦截，点绿色按钮「开启拦截」可开启

**「诊断一下」** 按钮会当场解析几个广告域名和正常域名，直接看到效果。

---

## 原理

用的是 **私人 DNS（DNS over TLS）**：

```
开启后：
  ad.doubleclick.net            → 0.0.0.0  ← 黑洞
  pagead2.googlesyndication.com → 0.0.0.0  ← 黑洞
  ads.youtube.com               → 0.0.0.0  ← 黑洞
  hm.baidu.com                  → 0.0.0.0  ← 黑洞

  www.baidu.com                 → 183.2.172.177  ← 正常
  www.qq.com                    → 58.49.216.194  ← 正常
```

广告服务器连不上，广告就加载不出来。

**服务器**：`dns.18bit.cn`（国内，实测可用）
**备用**：`dns.ipv4dns.com`（如果主服务器挂了，可手动改代码里的常量重新构建）

---

## 能拦什么，拦不住什么

| 类型 | 能不能拦 |
|---|---|
| 网页广告（域名级别的） | ✓ 能 |
| 追踪统计（Google Analytics 之类） | ✓ 能 |
| App 里的图片广告（独立域名） | ✓ 多数能 |
| **App 开屏广告**（和内容同域名） | ✗ 拦不住 |
| **视频贴片广告**（同域名内嵌） | ✗ 拦不住 |
| **系统推送广告** | ✗ 拦不住（那个要改系统设置） |

---

## 如果某个 App 出问题

有些 App 会因为广告域名被黑洞而加载失败或者闪退。

**打开 App，点「关掉它」就行**，一秒恢复。

或者进系统设置：
```
设置 → 网络和互联网 → 私人 DNS → 关闭
```

---

## Shizuku 通道（v2.0 新增）

App 里第 5 个标签 `[SYS]` 就是这条通道。

**Shizuku 是什么**：一个不需要 root 就能把 adb shell 权限借给 App 的框架。
它的后台服务 `shizuku_server` 以 `shell`（uid 2000）身份运行，所以 App 拿到授权后
就等于拿到了 `adb shell` —— 不用连电脑、不用敲命令。

**界面会显示 4 项状态**：

| 字段 | 含义 |
|---|---|
| INSTALL | 手机有没有装 Shizuku |
| SERVER | `shizuku_server` 在不在跑 |
| PERMISSION | 本 App 有没有被授权 |
| UID | 授权后能拿到的身份（正常是 2000） |

**没授权时**：点「请求授权」，Shizuku 会自己弹一个框，点「允许」即可。
**没装 / 没启动时**：界面会给出对应提示和下载入口，不会崩。

**Advantage 一：DNS 开关不再依赖 adb 授权**
没有 `WRITE_SECURE_SETTINGS` 时，开关会自动改走 Shizuku 执行
`settings put global private_dns_mode ...`，效果一样。
状态页的 `CHANNEL` 会显示当前走的是 `direct` 还是 `shizuku`。
也可以点「Shizuku 自授权限」，让它执行
`pm grant com.cat.dnsswitch android.permission.WRITE_SECURE_SETTINGS` 把权限补上。

**Advantage 二：能对系统推广 SDK（`com.opos.ads`）做三个动作**

| 按钮 | 实际执行 | 效果 |
|---|---|---|
| 挂起 ADS | `pm suspend --user 0/10 com.opos.ads` | 真的挂起，能挡住普通启动 |
| 强停 | `am force-stop --user 0/10 com.opos.ads` | 立刻掐掉当前进程 |
| 压制后台 | `appops set ... RUN_IN_BACKGROUND ignore` | 抑制后台活动和唤醒 |

**必须说清楚的事**：这个包是 ColorOS 预装的推广 SDK，
下面这些路猫猫一条条试过，**全部被系统拦死**：

```
pm disable-user --user 0 com.opos.ads   → 静默退回 enabled=1
pm uninstall --user 0 com.opos.ads      → DELETE_FAILED_INTERNAL_ERROR
pm hide com.opos.ads                    → SecurityException: MANAGE_USERS
pm disable-user .../<组件>               → SecurityException: Shell cannot change component state
pm revoke com.opos.ads INTERNET         → not a changeable permission type
```

而且挂起/强停之后，系统会在 1~2 秒内把它的进程重新拉起来（3 个进程常驻）。
**没有 root 就没有彻底关掉它的办法** —— 这一点不吹。
真正让广告加载不出来的是上面的私人 DNS：域名解析到 `0.0.0.0`，
SDK 活着但拉不到素材。`[SYS]` 面板里的按钮是用来「压制」它，不是「杀死」它。

---

## 日志导出（v2.4 新增）

日志一长，截图就没用了，所以能直接导出成 `.txt`。

**三个入口**：
- `[LOG]` 页底部 → `> 导出 RUN_LOG (TXT)`
- `[BLK]` 页底部 → `> 导出 BLOCK_LOG (TXT)`
- `[SYS]` 页底部 → `> 导出全部日志 (TXT)`（两份合成一个文件）

**怎么存的**：走系统文件选择器（`ACTION_CREATE_DOCUMENT`），
自己选目录，默认落在 `Download/`，**不需要任何存储权限**。
文件名格式 `maomao-run-20261017-141839.txt`。

**导出的文件长这样**（头部带环境信息，出问题时整份发出去就够定位了）：

```
# MAOMAO://DNS  LOG EXPORT
# TIME    2026-10-17 14:19:10
# VERSION 2.4 (10)
# DEVICE  OPPO PGAM10
# ANDROID 14 (SDK 34)
# SPACE   user 0
# DNS     mode=hostname  spec=dns.18bit.cn
# DIALECT direct
# SHIZUKU 已授权  v=13
# TYPE    RUN_LOG + BLOCK_LOG
# LINES   42
# ----------------------------------------
===== RUN_LOG =====
...
===== BLOCK_LOG =====
...
```

同时导出的结果也会写进 RUN_LOG（`EXPORT → OK/CANCEL/FAIL`），这样“导出失败”本身也有记录。

---

## 图标、名称与问候（v2.5 / v2.6）

- **应用名**：`普瑞赛斯病毒`（v2.6 起，之前叫 `MAOMAO://DNS`）。
  改的是 `AndroidManifest.xml` 里 `application` / `MainActivity` 的 `android:label`
  和 `res/values/strings.xml` 的 `app_name`；包名 `com.cat.dnsswitch` 没变，
  所以旧数据、权限、Shizuku 授权都不会丢。
- **图标**：换成主人给的猫猫头像，四档密度 `72/96/144/192` 都重新生成过。
  源码图存成 `icon-src.jpg`，改图标时直接替换它再重跑一遍缩放即可。
- **进门问候**：启动时弹一句 `HH.mm:又见面了博士.`，
  `HH.mm` 是当前时分，例如 `14.23:又见面了博士.`。
  同时状态页第一行常驻 `WELCOME`，显示同一句话。

> 改名后桌面上的**旧快捷方式不会跟着变**，长按删掉、重新从应用列表拖出来即可。

---

## 彩蛋：关闭罗德岛全舰防御系统（v2.7 新增）

状态页最下面那颗红框按钮，按下去会真的做这些事：

1. `private_dns_mode` 改成 `off` —— 拦截全关
2. 用 Shizuku 把 `com.opos.ads` 解挂起、`appops` 恢复默认 —— 压制也全关
3. 用 Shizuku `pm revoke` 自己的 `WRITE_SECURE_SETTINGS` —— 把自己的权限也撤掉
4. 屏幕全黑 1 秒
5. 黑底白字放一张普瑞赛斯，四句台词逐条浮现，末尾署名「—— 普瑞赛斯」
   （三组台词随机抽一组，每次不一样）
6. 主线程抛 `RuntimeException("PRTS FATAL: total defense shutdown")` —— App 真的崩溃闪退

**第 3 步有个安全闸**：只有 Shizuku 还处于已授权状态时才会撤权限，
否则跳过（撤了就再也补不回来，得插电脑）。

**恢复办法**（崩了之后）：
- 重新打开 App → 首页「开启拦截」，拦截就回来了
- 权限没了 → `[SYS]` 页点「Shizuku 自授权限」，或插电脑跑一次
  `adb shell pm grant com.cat.dnsswitch android.permission.WRITE_SECURE_SETTINGS`

---

## 技术细节

- **包名**：`com.cat.dnsswitch`
- **版本**：2.8（versionCode 14）
- **大小**：180.9 KB
- **minSdk**：24（Android 7.0）
- **targetSdk**：33
- **签名**：`CN=MaomaoDNS, O=Cat, L=Guangzhou`（私钥 `dns.keystore` **不入库**，由作者私下保管）
- **权限**：`WRITE_SECURE_SETTINGS`（改系统设置）、`moe.shizuku.manager.permission.API_V23`
- **Shizuku 客户端库**：`api` / `provider` / `shared` / `aidl` 四个 13.1.5 的 AAR（手工拆包取 `classes.jar`）

**授权命令**（有 Shizuku 时可以在 App 里点按钮完成，不然就走 adb）：
```
adb shell pm grant com.cat.dnsswitch android.permission.WRITE_SECURE_SETTINGS
```

> 注意：Shizuku 13.x 把 `Shizuku.newProcess` 收成了 `private`，
> 本项目用反射调用它（自己的库不受 hidden API 限制），见 `ShizukuBridge.java`。

---

## 重新构建

源码在 `D:\Code\111\dnsapk\`：

```
dnsapk\
├── AndroidManifest.xml        （含 ShizukuProvider 声明）
├── res\                       （图标 + 字符串）
├── src\com\cat\dnsswitch\
│   ├── MainActivity.java      （5 个 Tab + 右上角 [SET]）
│   ├── SettingsActivity.java  （设置页：ROOT / SHELL 授权 / 下载 / 使用说明 / 版本作者）
│   ├── AppRuleService.java    （无障碍，按应用切 DNS）
│   └── ShizukuBridge.java     （Shizuku 授权 + 命令通道）
├── libs\                      （Shizuku 四个 AAR 拆出来的 classes.jar）
├── dns.keystore               （签名私钥，已被 .gitignore 排除）
├── build-v6.ps1               （构建脚本）
└── out\v6\MaomaoDNS-v6.apk    （产物，不入库）
```

改完代码后：
```
powershell -ExecutionPolicy Bypass -File D:\Code\111\dnsapk\build-v6.ps1
```

**构建流程**（7 步，全用 Android SDK 命令行工具，不需要 Android Studio）：
1. `aapt2 compile` 编译资源
2. `aapt2 link` 生成 R.java + 未签名 APK
3. `javac` 编译 Java
4. `d8` 转 dex
5. 把 classes.dex 塞进 APK
6. `zipalign` 对齐
7. `apksigner` 签名

---

猫猫做的，2026-10-17。
