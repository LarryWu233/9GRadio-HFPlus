# Material 3 · 9GRadio Views 设计规范

## 技术栈与边界

检查基线为 alpha22。应用使用 Kotlin、XML、ViewBinding／DataBinding、AppCompat、Material Components **1.12.0**、AndroidX Preference、ViewPager2／BottomSheet；频谱、瀑布和调谐轮为自定义 View。没有 Compose 插件、Compose 依赖或运行时。本轮保持该技术栈及全部接收、显示、解码、录制、地图与设置内容。

采用用户指定的 [hamen/material-3-skill](https://github.com/hamen/material-3-skill/blob/master/skills/material-3/SKILL.md)，读取日期 2026-11-08，所读 SKILL.md 的 SHA-256 为 `2c92ce231a02fee866a45ec858be0ba92af9d18f8b42042e56d7ccbcda6d0fb2`。参考其中语义颜色、色调表面、形状、排版、8 dp 间距和无障碍原则；其 Compose 优先建议不作为迁移本应用的要求。Android Views 的 API 以现有依赖对应的[官方接入文档](https://github.com/material-components/material-components-android/blob/1.12.0/docs/getting-started.md)、[Slider](https://github.com/material-components/material-components-android/blob/1.12.0/docs/components/Slider.md) 和 [MaterialSwitch](https://github.com/material-components/material-components-android/blob/1.12.0/docs/components/Switch.md) 为准。

Material 3 Views 主题与控件已经包含在项目现有依赖中，无需升级构建链路或增加 Compose。主主题采用 `Theme.Material3.Dark.NoActionBar`。Preference 保持 AndroidX 的绑定、持久化和监听逻辑，只换行布局、间距与开关 widget。

## 颜色

保留经典、海湾、鼠尾草、纯黑、碳蓝、冰川、柔暗七个主题及原有选择值。保持全暗色，不自动启用壁纸动态配色或覆盖用户已选的频谱／瀑布方案。

| 语义角色 | 项目映射 | 用法 |
| --- | --- | --- |
| Surface / lowest | `radioMain` | 页面和系统栏底色 |
| Container low | `radioSurface` | 设置行、轻量输入区域 |
| Container | `radioCard` | 菜单与卡片层次 |
| Container high / highest | `radioElevated` / `radioSelected` | 播放器、选中容器 |
| Primary / onPrimary | `radioAccent` / `radioMain` | 高亮、启用开关；亮色填充配深色内容 |
| Secondary | `radioSlider` | 低饱和蓝绿色滑条及次级强调 |
| OnSurface / variant | `radioText` / `radioSecondary` | 正文／辅助内容 |
| Outline / variant | `radioMuted` / `radioOutline` | 输入边界／弱分隔线，二者分开 |

海湾主题的滑条颜色提高亮度，确保在蓝色背景上可辨认。禁用状态用语义色和透明度，而非另一组硬编码灰色。连接状态保留绿／红，录制动作保留红色，地图及雷达的信号语义色不套用普通按钮配色。

## 形状、间距与排版

统一资源位于 `radio_design_tokens.xml`，供 XML 和 Kotlin 共同读取。

| 项目 | 规范 |
| --- | --- |
| 间距 | 8 dp 基准，4 dp 为紧凑微间距；常用 8／12／16／24／32 dp |
| 页面左右留白 | 16 dp；紧凑表格保留列宽和横向读取方式 |
| 圆角 | 小 8、卡片 12／16、弹窗与面板 28 dp；普通按钮为胶囊形 |
| 标题 | Material 3 TitleSmall 14 sp / medium；设置正文标题 16 sp |
| 正文与标签 | BodyMedium / Label，14 sp；辅助内容 12 sp |
| 数值 | 频率段码、BW／SPAN、Step 与表格等宽数字保留现有专用字体，避免误读位数 |
| 触控 | 新交互以 48 dp 为基准；调频拨杆 64 dp 高。既有紧凑表格与顶栏不强行放大为大卡片 |

较少的并列模式继续使用原来的表格／分组选择，保持整个选项区域可见；不会把所有选项都改为横向滚动或独立大胶囊。地图页、录音页展开仍在原控制菜单内，频率区及折叠逻辑保留。

## 控件

- **Slider**：复用 Material 3 Slider，轨道 8 dp、握柄 4×28 dp、两侧留缝；值域、步长、回调和单位保持原样。高亮握柄与低饱和轨道分工，禁用状态随主题变化。
- **Switch**：统一为 MaterialSwitch。明亮轨道表示开启，深色对比握柄；关闭为中性轮廓。RF、降噪、地图切换与内容页保留原来的状态同步及能力限制。设置页用兼容 SwitchPreferenceCompat 的 MaterialSwitch widget，不重写存储逻辑。
- **按钮**：普通动作、轻量动作和选中容器分层；使用主题 token 的胶囊／圆角样式。模式表格保留连贯的网格边界；录音控制图标继续无彩色底框。
- **设置面板**：同类行使用一致的深色表面、16 dp 圆角、标题／辅助文字层级与间距，分类标题保留；长说明可换行，单选框及设置值不删减。

## Tune 快速调频拨杆

沿用 `SpringTunerView` 的公开回调及原来的 `TuningMath.rate`，输入改用现有 Material Slider：浅色握柄带抓握刻线，两端方向符号，中心至握柄之间显示速度区域，偏移越大越强调。无需额外的屏幕操作说明。

松手、取消触摸、失去焦点、隐藏或离开前台，立即停止步进并提交最终频率。AndroidX DynamicAnimation **1.0.0**（现有 Material 的传递依赖）仅使握柄视觉回中，回中动画不再发出调谐命令。键盘／TalkBack 为有界步进，不能开启无人操作的持续调频。细调频率轮和 Step 轮保留。

这是平台控件与既有调频控制的界面适配，没有重写驱动、USB 控制、DSP、重采样、解码或音频后端。没有引入神经网络或新的信号处理链。

## 检查方式

构建后检查七套主题的正文对比度、设置单选框、开关保存与恢复、滑条禁用状态、调频回中／取消／后台停止，以及已有菜单、地图手势、录音播放器与模拟降噪开关规则。通过源码对照验证原 XML 控件 ID 与 Preference key 没有被移除。真实设备、横屏／大字号及性能结果分别记录，不因使用 Material 3 就声称完成全部设备适配。
