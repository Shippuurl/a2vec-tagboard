# A2Vec 标定板 —— 手机标定板 App

原生 Android 全屏标定板。**没有浏览器、没有地址栏、没有系统 UI**，屏幕常亮，
板子按设备真实分辨率逐像素重绘；同时自己开一个局域网 HTTP 端口，上位机直接拉参数。

## 为什么不用网页

网页拿不到绝对物理尺寸：浏览器不暴露 `DisplayMetrics.xDpi/yDpi`，`devicePixelRatio`
会被页面缩放影响，所以只能靠用户在页面上比尺子手动标定。原生 app 可以直接读
`DisplayMetrics`，还能全屏不吃掉任何像素。

## 可选布局

长按手机屏幕，选择「12 个 Tag」或「6 个大 Tag」，选择会保存在手机上。
上位机连接后也可通过按钮切换。采集期间不要切换，改动后重新连接并生成姿态计划。

| 方案 | 排列 | ID |
|---|---|---|
| classic-12（默认） | 3 行 × 4 个 | 1–12 |
| compact-6 | 2 行 × 3 个 | 1–6 |

相同可用屏宽且均受宽度限制时，compact 的黑框边长约增大 31%，实际尺寸取决于视图宽高与留白。
保留 tag36h11、165/236 的黑框/格距比例与交错布局。至少四个非退化分布的 Tag 同时可见才能求解。

`tag_size_m = 165 * boardScale / pxPerMm / 1000`；格距与中心坐标使用同一个比例。
服务返回布局、ID 和以 Tag1 中心为原点的坐标，上位机按选定布局计算。
DPI 是设备提供的估计，仍应实测黑框边长确认。

## 标定端口

App 启动后监听 `0.0.0.0:8899`，屏幕底部会显示本机 IP。

| 端点 | 用途 |
|---|---|
| `GET /tagboard.json` | 全部参数：屏幕 px/mm、xdpi/ydpi、板几何、tag ids、centers_m、tag_size_m |
| `GET /tagboard/params` | 同上但只保留标定必需的最小集 |
| `GET /tagboard/board.png` | **设备上真正渲染出来的板**，用来和参考图逐像素比对 |
| `POST /tagboard/layout?layout=compact-6` | 切换布局并返回新参数 |
| `POST /tagboard/pose` | 上位机把算出的相机位姿回传，屏幕直接显示，便于现场核对 |
| `GET /health` | 存活检查 |

上位机一行取参：

```bash
curl -s http://<手机IP>:8899/tagboard/params | python3 -m json.tool
```

## 局域网发现（1.2.4+）

上位机不用再手抄手机 IP：App 每 3 秒向组播 `239.255.0.1:8888` 发一次 64 字节 `ANNOUNCE`；
上位机启动或点「搜索局域网设备」时发 `PROBE`，App **随机延迟 0~50 ms** 再单播回 `RESPONSE`，
避免多台设备同时应答把上位机的接收缓冲打满。

| 项 | 值 |
|---|---|
| 组播 | `239.255.0.1` · UDP `8888`（同时发一次 `255.255.255.255` 受限广播兜底） |
| 报文 | 64 B 定长：魔数 `0x524F424F` + 版本 + 类型 + TCP 端口 + 屏幕尺寸 + SN(16) + 名称(32) + CRC16 |
| 类型 | `1=ANNOUNCE` · `2=PROBE` · `3=RESPONSE` |
| 校验 | 魔数/CRC 不对的包直接丢弃 |
| 线程 | 独立最低优先级线程，只收发小包，不碰画板 / HTTP / SSE |

需要 `INTERNET`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE`（MulticastLock），已在清单中声明。

## 构建

不用 Gradle，直接用 SDK 里的 `aapt2` + `javac` + `d8` + `zipalign` + `apksigner`：

```bash
./build.sh          # 产物 build/TagBoard.apk
```

依赖（都在 `$HOME/android-sdk`，可用 `ANDROID_SDK_HOME` 覆盖）：
`build-tools/36.0.0`、`platforms/android-34/android.jar`，以及 JDK 21。

## 安装

手机开 USB 调试后：

```bash
$HOME/android-sdk/platform-tools/adb install -r build/TagBoard.apk
```

装完直接点图标即可，横屏全屏。点一下屏幕可重新进入沉浸式（万一被手势唤出导航栏）。

## 验证

`verify_render.py` 会编译 `BoardSpec` + `tools/RenderMain`（**app 的真源码**，不是复制一份参数），
渲染出板子后与 `biaoding/out/tagboard_3x4_checker.png` 逐像素比对，再用
`pupil_apriltags` 检出并核对编号与 `centers_px`：

```bash
$MVP android/TagBoard/verify_render.py
$MVP android/TagBoard/verify_render.py --layout compact-6
```

期望输出：classic 为 12/12、compact 为 6/6 检出，hamming 全 0，中心偏差 < 0.5 px。

改动 `BoardSpec` 的几何参数后**必须**重跑这个脚本。

## 物理尺寸怎么算（容易错的地方）

`tag_size_m` **不能**用屏宽直接推，必须用板上 1 个基线像素的真实物理长度：

```
meters_per_base_px = (板的最终缩放比例) / pxPerMm / 1000
                   = boardScale / (xDpi/25.4) / 1000
tag_size_m         = 165 * meters_per_base_px
```

`boardScale = min((视图宽-2*边距)/boardWidthPx, (视图高-2*边距)/boardHeightPx)` ——
所以任何影响视图尺寸的东西（挖孔避让、导航栏、系统栏）都会改变 tag 的物理尺寸。

## 验证过的坑

- **挖孔区默认不让画**：vivo X100 Pro 上 App 拿到的是 `2680x1260` 而不是 `2800x1260`，
  少了挖孔那 120px。结果是板子只占屏宽 95.7%，tag 物理尺寸系统性偏小 4.3%。
  必须 `android:windowLayoutInDisplayCutoutMode="shortEdges"`（manifest + theme 都要写）。
- **`tag_size_m` 拿屏宽当板宽**：板子按比例缩放后不一定铺满屏宽（高度可能先到限制），
  直接按屏宽算会偏。必须用上式的 `boardScale`。
- **绘制顺序**：tag 白底 -> 黑框 -> 数据格。先画白底再画框、或先画数据再画框，
  都会把上一笔擦掉（黑框丢了 tag 就检不出）。
- **棋盘奇偶**：黑方块在 `(行+列)` 为奇的格；写反了整板偏移 16px 且完全检不出。
- **tag 尺寸**：几何上是 165/236 格，但内部数据区只有 6 格，别把 165 当成数据区宽度。
- **`android.permission.INTERNET`**：Android 上创建**任何** socket 都要它，
  纯监听不联网也一样，缺了会 `socket failed: EPERM`。
- **参考 PNG 的 tag 是偏心的**（X 方向偏 16 基线像素 = 屏上 1.28mm，屏上能明显看出来）。
  本 app 已改成居中；`centers_m` 以 tag1 中心为原点，相对格距不受影响。

## 双指缩放与参数推送（1.2）

双指张开：增大 Tag，减少行列；收拢：减小 Tag，增加行列。范围为 1×1 到 20×20，使用 tag36h11 ID 1–400，保留原错行棋盘几何。长按仍可选 classic-12 / compact-6。
渲染复用每码 8×8 纹理，避免创建 9536×4720 的整板位图。PNG 导出为屏幕实际显示尺寸，JSON 的 image_px 仍是基线几何尺寸。

首次连接可 GET `/tagboard.json`；随后 GET `/tagboard/events` 建立 SSE 订阅。事件格式为 `data: {完整参数 JSON}\n\n`，首条为最近发布快照。手指操作期间不发布，全部离开后 1 秒无操作才发布；后续布局切换和屏幕尺寸改变也合并延迟发布。每 15 秒发送注释心跳，心跳不含参数。断线后重新订阅即可，不需要周期获取 JSON。

构建默认使用 Android build-tools 36.0.0（可通过 ANDROID_BUILD_TOOLS 覆盖），修复 34.0.0 D8 对新匿名监听器编译崩溃的问题。版本 1.2 / versionCode 3。

离线验证：`verify_render.py --layout grid-1x1`、`--layout grid-20x20`；后者基线和 1080/1260/1920/2156/2800 屏宽均可检出 400/400 个码。真实相机下检出率仍受距离、屏幕分辨率与对焦影响。
