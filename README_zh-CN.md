<div align="center">

<img src="website/assets/tile-logo.png" width="88" alt="言出法随图标" />

# 言出法随 · YanChuFaSui

### 把点击、滑动、打字，交给声音。

一个把**中文语音变成手机操作**的 Android 开源工具。<br>
为手部操作不便的人，也为希望减少触摸操作的人，提供另一种使用手机的方式。

[![Release](https://img.shields.io/github/v/release/qbhugo666/YanChuFaSui?style=flat-square&color=246BFE)](https://github.com/qbhugo666/YanChuFaSui/releases/latest)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=flat-square)](#开始使用)
[![Offline](https://img.shields.io/badge/语音处理-完全离线-246BFE?style=flat-square)](#隐私与权限)
[![License](https://img.shields.io/badge/License-Apache--2.0-111111?style=flat-square)](LICENSE)

**[下载正式版](https://github.com/qbhugo666/YanChuFaSui/releases/latest)** · **[看功能演示](#看看声音能做什么)** · **[查看常用指令](#先记住这几句话)** · **[反馈问题](https://github.com/qbhugo666/YanChuFaSui/issues)**

简体中文 ｜ [English](README_en.md)

</div>

---

## 你的下一次点击，可以从一句话开始

> **「显示编号」 → 「点击十八」**<br>
> 屏幕上的按钮有了编号，说出数字，就能点击对应目标。

每天，我们都在手机上重复点击、滑动和输入。<br>
当这些动作变得困难，浏览内容、发消息、切换应用也会变得费力。

**言出法随想让更多人自己完成这些日常操作——用说话，代替一部分触摸。**

它借助 Android 无障碍服务，在你正在使用的界面上完成操作。语音识别和命令判断都在手机本地运行，无需账号，也无需把声音发送到云端。

## 看看声音能做什么

下面是已有的功能演示，部分界面来自早期版本。

<table>
  <tr>
    <td align="center" width="50%">
      <h3>说出编号，点击目标</h3>
      <p>「显示编号」→「点击十八」</p>
      <img src="docs/images/demo-numbers.gif" width="265" alt="语音显示屏幕编号，再点击对应目标的演示" />
    </td>
    <td align="center" width="50%">
      <h3>说出方向，继续浏览</h3>
      <p>「向上滑动」</p>
      <img src="docs/images/demo-swipe.gif" width="265" alt="使用语音滑动手机页面的演示" />
    </td>
  </tr>
  <tr>
    <td align="center">
      <h3>说一句，把文字写进去</h3>
      <p>进入输入框 →「输入」→ 说出内容</p>
      <img src="docs/images/demo-dictation.gif" width="265" alt="使用语音听写向输入框写入文字的演示" />
    </td>
    <td align="center">
      <h3>写错的字，也能用嘴改</h3>
      <p>「把不错替换成很好」</p>
      <img src="docs/images/demo-replace.gif" width="265" alt="使用语音替换输入框内文字的演示" />
    </td>
  </tr>
</table>

## 三种定位方式，适应不同界面

| 你遇到的情况 | 可以怎么说 | 手机会怎么做 |
|---|---|---|
| 按钮上有文字 | 「点击搜索」 | 查找当前页面的文字目标并点击 |
| 目标可以被无障碍识别 | 「显示编号」→「点击十八」 | 给目标编号，再按数字定位 |
| 图标没有文字，或位置不好描述 | 「显示网格」→ 逐级选择格子 →「轻点」 | 用十二宫格缩小范围，再点击格心 |

**网格点击后会继续保留。** 你可以接着定位、轻点；说「隐藏」再收起浮层。

## 从浏览到编辑，覆盖日常操作

| 能力 | 例子 |
|---|---|
| 点击与长按 | 文字点击、编号点击、轻点、双击、两步式长按 |
| 浏览与微调 | 上下左右滑动；「向上摇移」等小步移动 |
| 图片缩放 | 「双指放大」「双指缩小」 |
| 输入与编辑 | 语音听写、删除、清空输入、移动光标、替换文字 |
| 系统操作 | 返回、回桌面、最近任务、音量、通知中心、控制中心、锁屏 |
| 截屏 | 「截屏」，需要 Android 9 或以上 |
| 重复动作 | 「重复三次」，回放上一动作；最多十次 |
| 个人习惯 | 自定义指令、个人词典、灵敏度设置、配置导入与导出 |

### 让常用操作，变成你习惯的说法

在设置里选择一个动作，录入自己的说法，就能建立**自定义指令**。自定义说法沿用现有同音、近音匹配规则；个人词典用于常用词的文字纠错。

已有的说法和词典可以导出备份，再导入。换手机或分享配置时，可以继续使用熟悉的习惯。

## 先记住这几句话

| 想做什么 | 可以说 |
|---|---|
| 选中屏幕上的目标 | 「显示编号」→「点击六」 |
| 定位没有文字的图标 | 「显示网格」→ 选择格子 →「轻点」 |
| 长按一个目标 | 「长按」→「三」 |
| 继续浏览 | 「向上滑动」「向左滑动」 |
| 写入文字 | 进入输入框 →「输入」→ 说出内容 |
| 修改已经写下的文字 | 「把不错替换成很好」 |
| 调整声音 | 「增加音量」「降低音量」 |
| 回到熟悉的位置 | 「返回」「回桌面」「最近任务」 |
| 再做几次上一动作 | 「重复三次」 |
| 结束语音控制 | **「退出」** |

「点击搜索」是点击**当前页面上**的目标；文字听写需要页面上有可用输入框。具体效果取决于应用提供的界面结构。

## 开始使用

**当前正式版：0.58.3 · 安装包约 293 MB · Android 7.0+ · ARM64 / ARMv7**

1. 从 **[正式版下载页](https://github.com/qbhugo666/YanChuFaSui/releases/latest)** 下载 APK 并安装。
2. 打开 App，按照引导授予麦克风权限并开启无障碍服务。
3. 按机型提示设置电池优化豁免、自启动等后台权限。
4. 点击「开始控制」，先试一句「显示编号」。
5. 不需要控制时，说「退出」，结束会话。

日常语音操作在手机上完成。部分设备的无障碍自动恢复能力，需要首次由电脑通过 ADB 授权；未授权时仍可使用普通控制，并按提示手动恢复服务。

<details>
<summary><strong>安装和使用前，你可能想知道</strong></summary>

- **为什么安装包较大？** 内置了离线识别模型，安装后无需为日常识别下载云端服务或联网。
- **能覆盖升级吗？** 旧正式版可以使用同一正式签名升级。开发 Debug 包与正式版签名不同，不能直接覆盖；已有个人配置时先备份，不要直接卸载。
- **所有应用都能操作吗？** 效果取决于界面是否提供无障碍节点、输入框及手势支持。部分目标可以用网格定位。
- **所有 Android 7.0+ 设备功能都相同吗？** 部分系统动作受 Android 版本和厂商限制，例如截屏需要 Android 9+。
- **嘈杂环境也一定能识别吗？** 不能保证。口音、背景声音、起音缺失和近音词都可能造成听错；聊天也可能误匹配指令。需要操作时开始会话，不用时说「退出」。
- **重复显示“已全部派发”是什么意思？** 表示动作请求已全部发送，页面最终效果仍以实际界面为准。

</details>

## 隐私与权限

**语音识别与指令解析在设备本地完成。App 没有申请 INTERNET 网络权限。**

- **无需账号或云端识别服务**，断网时仍能进行语音控制。
- **麦克风**用于控制会话内的语音识别，说「退出」后释放。
- **无障碍服务**用于读取当前界面目标、派发点击和滑动、编辑文字及调用系统动作。
- 个人绑定、词典和使用记录保存在设备上；配置备份与问题反馈由你主动导出，分享前可以自行检查内容。

App 有会话超时保护，媒体音量设有 80% 上限。语音控制的实际效果仍需以当前界面为准。

## 开源，也欢迎你一起完善

如果你认同**“每个人都应该有更多操作手机的选择”**，欢迎给项目一个 **Star**，或者转给可能需要它的人。

你也可以通过 [Issue](https://github.com/qbhugo666/YanChuFaSui/issues) 分享使用问题，或提交 PR 改进代码、文档与兼容性。反馈时写清手机型号、Android 版本、想完成的动作和实际结果，会更容易定位问题；无需公开私人聊天或录音。

**[更新日志](CHANGELOG.md)** · **[历史里程碑](MILESTONES.md)** · **[完整命令词表](app/src/main/assets/commands.json)**

<details>
<summary><strong>开发者：架构、模型与构建</strong></summary>

### 从声音到动作

```mermaid
flowchart LR
    A["你的声音"] --> B["Silero VAD<br/>语音分句"]
    B --> C["SenseVoice<br/>本机转写"]
    C --> D["命令匹配与参数判断"]
    D --> E["Android 无障碍服务"]
    E --> F["点击 · 滑动 · 输入 · 系统操作"]
```

当前 0.58.3 使用普通离线识别与文字判断流程，实验性声音二审已移除。命令执行结果与使用记录相关联；部分入口能观察页面变化，但派发成功不等于页面业务操作一定完成，也不会自动补点。

### 从源码构建

环境：JDK 17、Gradle 8.5、Android SDK 34。本仓库没有 Gradle Wrapper，需自行准备 Gradle。

```powershell
git clone https://github.com/qbhugo666/YanChuFaSui.git
cd YanChuFaSui
```

模型文件不进入源码仓库。请按 [SenseVoice 官方下载说明](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html) 准备 int8 模型，以及 [Silero VAD 官方下载说明](https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html) 的 16 kHz 模型：

| 放入 app/src/main/assets/ 的文件 | 来源 |
|---|---|
| `sensevoice.int8.onnx` | 官方 int8 模型包的 `model.int8.onnx`，重命名后放入 |
| `silero_vad.onnx` | k2-fsa 维护的 16 kHz Silero VAD 模型 |
| `tokens.txt` | 与 SenseVoice 模型配套，仓库已包含 |
| `commands.json` | 项目命令词表，仓库已包含 |

配置 Android SDK 路径后：

```powershell
gradle :app:testDebugUnitTest :app:verifySpeechPackage --max-workers=1
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。正式构建使用 `assembleRelease` 与 `verifySpeechReleasePackage`，签名密钥由发布者自行提供，仓库不包含密钥。部分本机实验测试需要未公开的夹具，缺失时只跳过对应实验。

</details>

## 致谢与许可

感谢 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)、[SenseVoice](https://github.com/FunAudioLLM/SenseVoice)、[Silero VAD](https://github.com/snakers4/silero-vad) 和 [pinyin4j](https://github.com/belerweb/pinyin4j)，为离线识别和中文匹配提供基础。

[Apache-2.0](LICENSE) © 2026 黄信豪 (Hugo)
