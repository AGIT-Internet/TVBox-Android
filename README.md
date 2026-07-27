# TVBox Android · 局域网 Web 服务版

这是一个基于 TVBox Android 壳二次开发的实验性分支。

普通 TVBox 主要面向安装设备本身：用户在电视或手机上完成选源、搜索和播放。本项目在保留原生 TVBox 功能与安卓源运行环境的基础上，在应用内部加入了 **NanoHTTPD Web Server、浏览器 UI、局域网 API、媒体代理与后台保活服务**。一台 Android 设备开启服务后，同一局域网内的电脑、电视、平板和手机都可以直接通过浏览器使用。

项目不需要在电脑上安装 Android Emulator、Docker、Podman、Termux 或额外的 Node.js 服务。Android 设备本身就是源运行环境和局域网服务器。

> 本项目是独立实验分支，并非 TVBox、FreeBox 或 LunaTV 的官方版本。

## 下载

前往 [GitHub Releases](https://github.com/AGIT-Internet/TVBox-Android/releases/latest) 下载 APK。

当前提供以下 Debug 构建：

| APK | 架构 | Python 源 | 适用场景 |
| --- | --- | --- | --- |
| `TVBox_debug-java.apk` | `armeabi-v7a` + `arm64-v8a` | 不包含 | 通用版本，兼容 32/64 位设备 |
| `TVBox_debug-java64.apk` | `arm64-v8a` | 不包含 | 64 位设备，安装包更小 |
| `TVBox_debug-python64.apk` | `arm64-v8a` | 包含 | 需要 Python 源支持的 64 位设备 |

目前 Release 中上传的是 Debug APK，请根据自己的设备架构选择。大多数近年的 Android 电视、盒子和手机可优先使用 `java64`；不确定架构时使用 `java`。

## 与普通 TVBox 的区别

| 能力 | 普通 TVBox | 本项目 |
| --- | --- | --- |
| 原生 Android TVBox 界面 | 支持 | 支持 |
| 安卓 Jar / JS / Python 源运行环境 | 在本机运行 | 仍在 Android 本机运行 |
| 浏览器访问 | 通常不提供 | 内置完整 Web UI |
| 局域网多端使用 | 每台设备分别安装 | 一台 Android 设备提供服务，多端浏览器访问 |
| 搜索、分类、详情与选集 | 仅原生界面 | 原生界面与 Web 端均可使用 |
| 历史、收藏与播放进度 | 保存在 Android 本机 | Web 端直接读写同一份本地数据 |
| 直播、EPG 与直播收藏 | 原生界面使用 | 增加 Web API 与浏览器操作 |
| 媒体请求头与流代理 | 由原生播放器处理 | 内置 `/proxy`，供浏览器播放使用 |
| 访问控制 | 通常无局域网服务 | 可选 6 位配对码、设备会话和一键注销 |
| 后台运行 | 以普通应用生命周期为主 | 前台服务、WakeLock、WifiLock、健康检查和自动恢复 |
| PC 端 Android 兼容 | 需要改源或额外运行环境 | Android 设备负责兼容，PC 只需要浏览器 |

本项目解决的核心问题不是“让 JVM 直接加载所有 Android Jar”，而是让 Android 继续做它擅长的事：运行依赖 Android 环境的源；浏览器端只负责交互和播放。这样可以避开大量 Android API、混淆 Jar、原生库和运行环境差异带来的兼容成本。

## 项目背景

方案来源于 [FreeBox issue #100：更好的安卓源兼容方案](https://github.com/kknifer7/FreeBox/issues/100) 中的讨论和实践。相关过程包括：

- [B/S 架构 + Android Emulator 的首次实现](https://github.com/kknifer7/FreeBox/issues/100#issuecomment-5045644469)；
- [将 Server 移到 Android 设备的设想](https://github.com/kknifer7/FreeBox/issues/100#issuecomment-5053362398)；
- [原生 TVBox 内置 Web Server 的初版实现](https://github.com/kknifer7/FreeBox/issues/100#issuecomment-5059453544)。

项目早期尝试过 **B/S 架构 + Google Android Emulator**：由电脑启动完整 Android 环境，再让 Web 前端访问模拟器内的源运行时。这个方案兼容性较直接，但分发体积可能达到数 GB，还要处理不同操作系统、CPU 架构、虚拟化支持和模拟器维护。

后续将方案调整为当前形态：

- 直接修改原生 TVBox Android 应用；
- 在应用内部运行 Web Server；
- 复用 TVBox 已有的源、数据库、播放解析与网络能力；
- 将 LunaTV 风格的 Web UI 打包进 APK；
- 由真实 Android 设备为局域网内所有浏览器提供服务。

相比模拟器方案，这一版本更轻量，也更符合“一台设备开启服务，局域网全端使用”的目标。

## Web 端能力

当前 Web 服务已接入：

- 首页推荐、站点和分类；
- 分类分页；
- 多站搜索与搜索历史；
- 影片详情、线路和选集；
- 播放地址解析与媒体代理；
- 历史记录、收藏和播放进度；
- 进度写入冲突检测；
- 直播分组、线路切换、收藏和最近播放；
- JSON EPG 与 XMLTV EPG；
- 图片代理、字幕代理和带请求头的媒体代理；
- 健康状态与请求诊断。

Web UI 与 API 均由 APK 内置资源提供，默认监听：

```text
http://<Android 设备局域网 IP>:9979
```

## 使用方法

1. 在 Android 电视、盒子或手机上安装合适的 APK。
2. 正常导入自己的 TVBox 配置。
3. 进入应用的“用户”页面，打开“服务器”。
4. 开启局域网服务，并按系统提示允许后台运行或忽略电池优化。
5. 将页面显示的访问地址复制到同一局域网设备的浏览器中。
6. 如需限制访问，开启“访问安全”，在浏览器输入应用显示的 6 位配对码。

默认端口为 `9979`。如果服务启动失败，请检查该端口是否被其他应用占用，以及设备是否已连接局域网。

## 访问安全

访问安全为可选功能：

- 配对码为 6 位数字，有效期 5 分钟；
- 配对成功后浏览器获得随机会话令牌；
- 可以在 Android 设置页查看已配对设备数量；
- 刷新配对码会注销所有已配对设备；
- 1 分钟内连续输错 5 次，将限制该地址继续配对 60 秒；
- NanoHTTPD 自动恢复时，会保留当前应用进程内的已配对会话。

服务当前使用普通 HTTP，而不是 HTTPS。配对机制用于阻止同一局域网内的未授权访问，但不提供传输加密。请勿将 `9979` 端口直接映射到公网，也不要在不可信的公共 Wi-Fi 中开启服务。

## 后台保活与自动恢复

早期版本必须保持应用前台亮屏运行。当前版本已经加入：

- `dataSync` 类型前台服务；
- `PARTIAL_WAKE_LOCK` CPU WakeLock；
- 高性能 WifiLock；
- 电池优化豁免入口；
- 每 10 秒一次的本机 HTTP 健康检查；
- 连续两次健康检查失败后自动重启 NanoHTTPD；
- 网络变化后立即检查服务并刷新通知；
- 服务异常恢复时保留当前配对会话；
- 设备重启后按已保存的开关状态恢复服务；
- 设置页显示前台服务、双 Lock、电池白名单、健康检查、连续失败和恢复次数。

项目目前保持 `targetSdkVersion 28`。Android 15 对面向 API 35 及以上应用施加的 `dataSync` 前台服务 6 小时限制不适用于当前构建；不过不同厂商仍可能有额外的后台清理策略，建议将应用加入系统后台白名单。

## 已知限制

- 仍然需要一台持续在线的 Android 设备作为服务器。
- 安卓源兼容性取决于源本身，本项目不保证所有 Jar、JS 或 Python 源均可用。
- 需要弹出二维码、输入框、通知或其他原生界面的源，Web 端可能无法完成交互。
- 需要 WebView 嗅探或复杂网页解析的播放线路暂不支持。
- 部分源会启动自己的本地代理服务，将播放地址转交浏览器后可能失效。
- 设备 IP 变化后，需要使用设置页或通知中显示的新地址。
- 后台保活不能完全绕过所有厂商系统的省电与进程清理策略。
- Web UI 仍处于早期阶段，视觉和交互会继续调整。

## 截图

以下截图来自本方案的早期 Web UI，实际界面可能随版本更新：

![TVBox 局域网 Web 首页](https://github.com/user-attachments/assets/4afedba3-f013-436d-b35a-0f7ee9400d56)

![TVBox 局域网 Web 播放页面](https://github.com/user-attachments/assets/f3d32513-5b06-4758-988b-a096e3de03ea)

## 本地构建

Java 通用版：

```powershell
.\gradlew.bat :app:assembleJavaDebug --no-daemon
```

Java 64 位版：

```powershell
.\gradlew.bat :app:assembleJava64Debug --no-daemon
```

Python 64 位版：

```powershell
.\gradlew.bat :app:assemblePython64Debug --no-daemon
```

Python 版使用 Chaquopy 12.0.1，需要本机安装 Python 3.8。Windows 可使用 Python 3.8.10，并在不提交到 Git 的 `local.properties` 中配置：

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
buildPython=C:/Users/your-name/AppData/Local/Programs/Python/Python38/python.exe
```

构建产物位于：

```text
app/build/outputs/apk/
```

## 配置格式参考

本项目继续使用 TVBox 配置格式。项目本身不提供、维护或推荐任何影视源。

```json
{
  "spider": "./your.jar",
  "wallpaper": "./api/img",
  "sites": [],
  "parses": [],
  "hosts": [],
  "lives": [],
  "rules": [],
  "doh": [
    {
      "name": "腾讯",
      "url": "https://doh.pub/dns-query"
    },
    {
      "name": "阿里",
      "url": "https://dns.alidns.com/dns-query"
    }
  ]
}
```

常见字段：

- `searchable`：是否允许搜索，`0` 关闭，`1` 启用；
- `filterable`：首页是否支持筛选，`0` 否，`1` 是；
- `playerType`：播放器类型，`0` 系统，`1` IJK，`2` EXO；
- 采集接口类型：`0` XML，`1` JSON，`3` Jar，`4` Remote；
- `parses` 类型：`0` 嗅探/内置播放器，`1` 返回直链；
- 直播参数可包含 `ua`、`epg` 和 `logo`。

## 致谢

- TVBox 及其社区维护者；
- [FreeBox](https://github.com/kknifer7/FreeBox) 与 issue #100 中参与安卓源兼容讨论的开发者；
- [LunaTV](https://github.com/MoonTechLab/LunaTV) 提供的 Web UI 思路；
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)。

## 免责声明

本项目仅作为技术研究和个人媒体管理工具，不提供、不内置、不维护、不推荐任何影视、直播或解析资源。

用户应确保所使用的配置、接口、媒体和数据已取得合法授权，并自行承担使用第三方资源产生的法律、安全、隐私和流量风险。请遵守所在地区法律法规并尊重知识产权。

## 开源协议

本项目按仓库中的 [GNU Affero General Public License v3.0](LICENSE) 发布。通过网络向用户提供本项目功能时，请同时遵守 AGPL-3.0 的源码公开要求。
