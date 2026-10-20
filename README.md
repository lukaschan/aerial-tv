<div align="center">

<img src="docs/brand/icon.png" width="96" alt="Aerial 图标">

# Aerial

**给国产电视的一块干净桌面。**

参照 tvOS 设计的 Android TV 桌面：没有广告，没有推荐流，只有你的 App、你的壁纸和 Apple TV 航拍屏保。<br>
为小米、TCL 等国产电视而做，开机自动接管，待机也不会被清理。

**中文** · [English](README.en.md)

<a href="https://aerial.qust.me"><img alt="官网" src="https://img.shields.io/badge/%E5%AE%98%E7%BD%91-1f6feb?style=for-the-badge&logo=safari&logoColor=white"></a>
<a href="https://github.com/pengchujin/aerial-tv/releases/latest"><img alt="下载 APK" src="https://img.shields.io/badge/%E4%B8%8B%E8%BD%BD%20APK-34a853?style=for-the-badge&logo=android&logoColor=white"></a>
<a href="#用-agent-安装推荐"><img alt="让 Agent 帮你装" src="https://img.shields.io/badge/%E8%AE%A9%20Agent%20%E5%B8%AE%E4%BD%A0%E8%A3%85-d97757?style=for-the-badge&logo=claude&logoColor=white"></a>
<a href="#第一步打开电视的-adb-调试"><img alt="开启 ADB" src="https://img.shields.io/badge/%E5%BC%80%E5%90%AF%20ADB-555555?style=for-the-badge&logo=gnubash&logoColor=white"></a>

<a href="https://github.com/pengchujin/aerial-tv/releases/latest"><img alt="版本" src="https://img.shields.io/github/v/release/pengchujin/aerial-tv?style=flat-square&label=%E7%89%88%E6%9C%AC&color=2997ff"></a>
<a href="LICENSE"><img alt="许可证 MIT" src="https://img.shields.io/badge/%E8%AE%B8%E5%8F%AF%E8%AF%81-MIT-555555?style=flat-square"></a>
<img alt="Android TV 5.0+" src="https://img.shields.io/badge/Android%20TV-5.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white">

![Aerial 首页](site/img/home.jpg)

| 电视自带的桌面 | 换上 Aerial 之后 |
|:---:|:---:|
| <img src="site/img/stock-xiaomi.jpg" alt="电视自带的桌面：满屏剧集推荐、会员和限免角标"> | <img src="site/img/home.jpg" alt="Aerial 首页"> |

</div>

## 功能

### 首页

<p align="center">
  <img src="site/img/focus.jpg" width="49%" alt="聚焦在 Netflix 上">
  <img src="site/img/grid.jpg" width="49%" alt="往下滚动后的应用网格">
</p>

- **时钟**：大号细体时钟压在壁纸上，壁纸底部和左侧自动压暗。往下滚动时，时钟收进状态栏。
- **应用网格**：每行 5 个，聚焦时浮起并显示名字。默认加一圈白色描边，离得远也看得清；在「控制中心 → Aerial 设置」可以改回 tvOS 的原样。
- **图标**：有电视横幅就用横幅；方形图标用自己的底色铺满磁贴；自适应图标拆成两层做视差。
- **全部 App**：小米「应用中心」「高清播放器」「米家」这类没有标准入口的系统 App 也会显示。
- **信号源磁贴**：把 HDMI、AV、电视天线固定到首页，按一下直接切换（控制中心 → 信号源 → 显示在主屏幕）。
- **应用栏单行显示**（本 fork 的改动）：首页应用只排一行，左右滚动；磁贴大小不变，不再往下排第二行。
- **信号源自动化**（本 fork 的改动）：在「控制中心 → Aerial 设置 → 信号源自动化」里选一个信号源（比如接 Apple TV 的 HDMI），可以开启：开机后自动切换到该信号源；该信号源有信号时自动唤醒电视并优先切换过去；正在看该信号源时信号中断满 10 秒，电视自动关机（给小米老电视这类 CEC 联动关机不可靠的机型用）。电视彻底关机/深度待机时 App 无法运行，唤醒仍需电视自身的 CEC 设置配合。
- **返回键**：和 tvOS 一样，在网格里按返回回到第一行，在第一行按返回开始屏保。

### 长按菜单

![长按菜单](site/img/menu.jpg)

长按 OK 键：**移动**（其他磁贴自动让位）、**从主屏幕隐藏**、**应用信息**、**卸载**。

### 控制中心

![控制中心](site/img/control-center.jpg)

按遥控器的菜单键（≡）打开，或在第一行按上键。包括信号源（显示 CEC 设备）、网络、设置，音量和亮度滑块，以及壁纸与屏保、隐藏的 App、Aerial 设置。网络和设置会打开电视自己的面板。

### 屏保

<p align="center">
  <img src="site/img/screensaver-aerial.jpg" width="49%" alt="航拍屏保：俯瞰优胜美地">
  <img src="site/img/screensaver-clock.jpg" width="49%" alt="时钟屏保">
</p>

- **航拍**：114 段 Apple TV 航拍影片，从 Apple 的服务器直接播放，镜头移动时显示地名。左右键切换上一段 / 下一段。
- **照片**：每日精选和你的照片缓慢推移。
- **时钟**：每分钟换一个位置，防止烧屏。

在使用 Android 标准屏保的电视（比如 TCL）上，Aerial 就是系统屏保。小米用自家的屏保，Aerial 会在桌面空闲时抢先启动，挡在小米的广告屏保前面。

### 壁纸与手机设置

<p align="center">
  <img src="site/img/wallpaper.jpg" width="74%" alt="壁纸与屏保面板">
  <img src="site/img/phone.jpg" width="20%" alt="手机上的设置页">
</p>

- **壁纸**：必应每日精选（国内直连）、8 组内置渐变，或者你自己的照片。
- **用手机设置**：扫描壁纸面板上的二维码，在手机上传照片、选壁纸、换屏保。页面只在面板打开时开放，地址带随机令牌。

## 安装

不需要 root，**不卸载、不停用任何系统应用**，一条命令就能还原。

### 第一步：打开电视的 ADB 调试

电脑和电视连同一个网络。先在电视上找到 IP 地址（一般在「设置 → 网络」或「关于」里），再打开 ADB：

| 电视 | 怎么打开 |
|---|---|
| **小米 / Redmi** | 设置 → 关于 → **产品型号**，连续按确认键，直到提示已进入开发者模式。再到 设置 → 账号与安全，打开 **ADB 调试** 和 **安装未知来源的应用** |
| **TCL / 雷鸟** | 设置 → 关于 → **本机信息**，依次按遥控器的 **上、下、左、右**（间隔不超过 1 秒），底部会出现「更多」，在里面打开 **ADB** |
| **原生 Android TV / Google TV**、海信等 | 设置 → 设备偏好设置 → 关于 → 连续按 **版本号** 7 次；再到 开发者选项 打开 **USB 调试**（也可能叫「网络调试」或「ADB 调试」） |

电脑第一次连接时，电视会问「允许 USB 调试吗？」，选 **允许** 并勾上「始终允许」。

### 用 Agent 安装（推荐）

把下面这段交给 Claude Code、Codex、Cursor 这类能在你电脑上运行命令的 Agent，把 `<电视IP>` 换成你的电视 IP：

```text
请帮我把开源电视桌面 Aerial（https://github.com/pengchujin/aerial-tv）安装到我的电视上，并让它成为开机自动启动的桌面。
我的电视 IP 是：<电视IP>

请按这个顺序做：
1. 读一下仓库 README 的「安装」部分和 docs/devices/ 下的机型笔记。
2. 确认电脑上有 adb、curl、python3（没有 adb 也可以，安装脚本会自动下载 Google 的 platform-tools）。
3. 运行 adb connect <电视IP>:5555。连不上就提醒我按 README 在电视上打开 ADB 调试；显示 unauthorized 就提醒我在电视上点「允许」。
4. 运行一键安装：
   curl -fsSL https://raw.githubusercontent.com/pengchujin/aerial-tv/main/tools/get.sh | KEEP_ADB=1 TV=<电视IP> bash
   如果是 TCL，电视右上角会弹出「多屏互动：正在发起应用推送」，提醒我在电视上选「同意」。
5. 装完后用 adb shell dumpsys window | grep mCurrentFocus 确认前台是 app.orchard.tv；征得我同意后再 adb reboot 一次，确认开机直接进入 Aerial。
6. 不要卸载或停用任何系统应用，不要删除电视上不是这次安装创建的文件。失败时把命令输出原样给我看，再参考 docs/devices/ 排查。
```

### 一行命令（macOS / Linux）

```bash
curl -fsSL https://raw.githubusercontent.com/pengchujin/aerial-tv/main/tools/get.sh | KEEP_ADB=1 TV=192.168.1.20 bash
```

脚本会下载最新版 APK 安装，授予只有 ADB 能给的权限（见下表），预编译让它更流畅，然后接管桌面和屏保、放行开机自启。

- `KEEP_ADB=1`：电视关掉 ADB 调试后（小米待机唤醒时会关），Aerial 立刻重新打开。不需要可以去掉。
- **固件拒绝安装桌面类 App**：在命令最后加 `-s guard`，改装不声明 HOME 的版本。
- **TCL**：TCL 拒绝一切 ADB 安装，脚本会改用电视自带的「多屏互动」推送，你在电视上选一次「同意」即可；没有多屏互动时退回用当贝市场。

还原成原来的桌面：

```bash
curl -fsSL https://raw.githubusercontent.com/pengchujin/aerial-tv/main/tools/get.sh | TV=192.168.1.20 bash -s restore
```

### 手动安装

到 [Releases](https://github.com/pengchujin/aerial-tv/releases/latest) 下载 `aerial-standard.apk`（固件拒绝安装桌面类 App 时用 `aerial-guard.apk`），然后：

```bash
adb connect 192.168.1.20:5555
adb install -r aerial-standard.apk
adb shell pm grant app.orchard.tv android.permission.WRITE_SECURE_SETTINGS
adb shell cmd notification allow_listener app.orchard.tv/app.orchard.tv.guard.RevivalListener
adb shell appops set app.orchard.tv SYSTEM_ALERT_WINDOW allow
adb shell am start -n app.orchard.tv/.MainActivity
```

Aerial 启动后会自己打开桌面守护。屏保、亮度、TCL 自启等完整步骤见 [tools/install.sh](tools/install.sh)。

## 它怎么接管桌面，又怎么不被杀掉

国产电视大多只认自家桌面，待机时还会清理第三方 App。Aerial 先温和、后强硬：

1. **正常申请**：申请 HOME 角色、设为默认桌面。原生 Android TV 和 Google TV 到这一步就够了。
2. **桌面守护**：固件坚持用自家桌面时（小米、TCL 都是），由一个无障碍服务接管：官方桌面一到前台，就把 Aerial 拉回来。它只看哪个窗口到了前台，不读屏幕内容。
3. **被杀后复活**：小米待机会强制停止所有第三方 App。Aerial 注册了一个通知监听（不读任何通知），系统会在约 0.5 秒内重新绑定它，Aerial 借此复活，并用 `WRITE_SECURE_SETTINGS` 重新打开守护。
4. **开机自启**：开机时系统绑定通知监听，Aerial 随之启动。TCL 的「自启动管理」默认拦截第三方 App，安装脚本会用 `appops` 放行。
5. **ADB 常开**（可选）：小米唤醒后会关掉 ADB 调试，开了 `KEEP_ADB` 后 Aerial 会马上打开它，方便以后升级。

### ADB 授予的权限

| 权限 | 用途 |
|---|---|
| `WRITE_SECURE_SETTINGS` | 重新打开被系统关掉的守护；保持 Aerial 为系统屏保；`KEEP_ADB`；写入小米的背光值 |
| 通知监听 | 被强制停止或开机后由系统拉起，不读取任何通知 |
| 悬浮窗（`SYSTEM_ALERT_WINDOW`） | 开机后从后台打开 Aerial |
| 修改系统设置 | 用标准接口调节亮度 |
| 定位 | 只用来读取 Wi-Fi 名称 |
| TCL `APP_AUTO_START` / `APP_ASSOC_START` | 允许开机自启（只有 TCL 有） |

## 已验证的电视

| 机型 | 系统 | 状态 | 笔记 |
|---|---|---|---|
| 小米 MiTV-MFTR0 | Android 14 / HyperOS TV | 日常使用中 | [笔记](docs/devices/xiaomi-mitv-mftr0.md) |
| 小米 MiTV-MFFU0 | Android 14 / HyperOS TV | 安装、开机自启已验证 | [笔记](docs/devices/xiaomi-mitv-mffu0.md) |
| Redmi X65 2026（MiTV-MFTR0） | Android 14 / HyperOS TV | 用户验证：安装、重启后自动进入（[#3](https://github.com/pengchujin/aerial-tv/issues/3)） | 同 MiTV-MFTR0 |
| TCL MT5879 国行 | Android 11 | 日常使用中 | [笔记](docs/devices/tcl-mt5879-cn.md) |
| 索尼 XRJ-77A80J 国行（BRAVIA VH2） | Android 10 | 用户验证：安装、开机自启、待机唤醒、屏保（[#16](https://github.com/pengchujin/aerial-tv/issues/16)） | — |
| 索尼 BRAVIA VH1 国行 | Android 12 | 用户验证；亮度改为打开索尼的图像设置（[#2](https://github.com/pengchujin/aerial-tv/issues/2)） | — |

其他品牌（海信、创维等）应该也能用，但还没在真机上验证。欢迎[提交 Issue](https://github.com/pengchujin/aerial-tv/issues) 或 PR 补充你的机型。

## 已知限制

- **需要联网**：航拍按 1080p H.264 实时播放，码率约 15–20 Mbps；每日精选也要联网。
- **HDMI 设备名**：真实名称（如「Apple TV」）需要系统权限才读得到，所以只显示「已连接」。
- **TCL 音量条**：调音量时会同时弹出 TCL 自己的音量条，这是让音量真正生效的必要条件。
- **小米屏保**：停在别的 App 里空闲时，仍然会出现小米自己的屏保。
- **网页安装**：浏览器连不上电视的网络 ADB，电视的 USB 口也不能被电脑当成设备连接，所以没法「打开网页就装」。

## 开发

需要 JDK 17、Android SDK 36。

```bash
./gradlew assembleStandardRelease assembleGuardRelease
BUILD=1 KEEP_ADB=1 TV=<电视IP> tools/install.sh      # 用本地编译的 APK 安装
```

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)：代码结构与数据流
- [docs/HANDOFF.md](docs/HANDOFF.md)：现状、调试技巧、必须遵守的规则
- [docs/devices/](docs/devices/)：各机型的实测笔记，包括 TCL 多屏互动的推送协议
- [AGENTS.md](AGENTS.md)：给 AI 代理的开发说明
- [site/](site/)：官网（纯静态，中文 `/`、英文 `/en/`）

设计上的取舍：按 960×540dp 的画布等比缩放，不同密度的电视看起来一样；电视 GPU 很弱，每帧最多一次全屏绘制，空闲时不跑动画；焦点动画用弹簧，可以中途打断；字体用 Inter（OFL 许可）。

航拍目录 `app/src/main/assets/aerials.json` 由 Apple 公开的 `resources-16.tar` 生成，只有影片地址和地名，不含影片本身。

## 许可

[MIT](LICENSE)。Inter 字体使用 [SIL Open Font License](FONT-LICENSE-OFL.txt)。

Aerial 是独立的开源项目，与 Apple、小米、TCL、索尼均无关联。航拍影片版权归 Apple 所有，由 Apple 的服务器直接播放；每日精选图片来自必应，版权归原作者所有。
