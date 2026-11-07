# 控制菜单边缘磨砂

- 上游：[Dimezis/BlurView](https://github.com/Dimezis/BlurView)。
- 固定版本 3.2.0，提交 `e81564e1338d962b7050336c56917cff26e96c3c`，Apache-2.0。
- `third_party/blurview` 保留原始 Java、资源及完整许可，作为独立 Android library 集成，避免依赖运行时下载或 JitPack 构建可用性。
- 本地适配：新增 SDK 35／minSdk 26 的 Gradle 配置；移除原 manifest 的 package 属性，由 AGP namespace 提供。没有改写模糊算法。
- 只对菜单顶部 44 dp 区域使用 BlurView，内容目标与 BlurView 分离以避免递归；Android 12+ 使用上游 RenderNode／RenderEffect 路径，旧系统使用上游兼容实现。控件与文字绘制在模糊层之上，正文保持不透明底色。
- 同步增加圆角与低透明度边缘高光，保留当前主题暗色。真实低版本设备与长期性能仍需分别验收，不因接入库而声称达到整机帧率目标。
