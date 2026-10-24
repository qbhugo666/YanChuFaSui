# v0.59.3 展示页上线包

上传目录：`publish-ready/`，压缩包：`intro-upload.zip`。压缩包根目录直接包含 `index.html` 和 `assets/`。

当前生产网站：`https://yanchufasui.pages.dev/`，托管于 Cloudflare Pages 的 `yanchufasui` 项目。在该项目创建新部署，上传 `intro-upload.zip`；入口为 `index.html`，无需构建命令。

桌面演示使用 H.264 MP4，720px 宽、24fps、无音轨，播放索引前置；手机宽度使用循环 WebP 动图，以兼容微信等内置浏览器。保留既有静音循环、离屏暂停、静态海报及灵敏度裁切效果，原始素材和原网页保留。

四段视频共 1,908,699 字节，另附四张移动端 WebP 动图。每次重新打包后须核对 ZIP 根目录、资源完整性、线上下载入口和分享 Logo；本地文件变更不等于线上已部署。

包内不包含 App 源码、模型、诊断日志、内部文档或未使用的网页截图。页面保留用户已批准公开的创作者经历、现有 Open Graph 标签及黑色 Logo。当前包下载入口指向 GitHub v0.59.3 APK；部署前须确认对应 Release 和 `VoiceControl-v0.59.3.apk` 已公开且可正常下载。

本次仅将下载入口切换到 v0.59.3；页面布局、演示、Open Graph 标签与黑色 Logo 均保持。部署后核对首页下载地址、Logo、四组演示及反馈与联系入口；Cloudflare 显示成功后，还需打开生产网址核对。
