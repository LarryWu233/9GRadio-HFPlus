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
- **按钮**：普通动作、轻量动作和选中容器分层；使用主题 token 的胶囊／圆角样式。模式选项共享三列布局、12 dp 圆角和 8 dp 间距；录音控制图标继续无彩色底框。
- **设置面板**：同类行使用一致的深色表面、16 dp 圆角、标题／辅助文字层级与间距，分类标题保留；长说明可换行，单选框及设置值不删减。

## Tune 快速调频拨杆

沿用 `SpringTunerView` 的公开回调及原来的 `TuningMath.rate`，输入改用现有 Material Slider：浅色握柄带抓握刻线，不放两端箭头，中心至握柄之间显示速度区域，偏移越大越强调。无需额外的屏幕操作说明。

松手、取消触摸、失去焦点、隐藏或离开前台，立即停止步进并提交最终频率。AndroidX DynamicAnimation **1.0.0**（现有 Material 的传递依赖）仅使握柄视觉回中，回中动画不再发出调谐命令。键盘／TalkBack 为有界步进，不能开启无人操作的持续调频。细调频率轮和 Step 轮保留。

这是平台控件与既有调频控制的界面适配，没有重写驱动、USB 控制、DSP、重采样、解码或音频后端。没有引入神经网络或新的信号处理链。

## 检查方式

构建后检查七套主题的正文对比度、设置单选框、开关保存与恢复、滑条禁用状态、调频回中／取消／后台停止，以及已有菜单、地图手势、录音播放器与模拟降噪开关规则。通过源码对照验证原 XML 控件 ID 与 Preference key 没有被移除。真实设备、横屏／大字号及性能结果分别记录，不因使用 Material 3 就声称完成全部设备适配。

## Alpha24 局部调整

频率精调与 Step 轮使用一层 16 dp 圆角色调刻度槽、主题细轮廓，去掉整个 View 的外层底板和中央读数小框。保留刻度透视、中心指示及惯性；在读数区域避让刻度，避免文字被穿过。该刻度槽是现有自定义 View 对色调容器与 shape token 的适配，并非声称 Material 提供了专用 SDR 轮控件。

模拟、数字、SSTV／SAT／EiBi 使用同一 `ModeChoiceUi`：三列、48 dp 高、12 dp 圆角、8 dp 水平／垂直间距，同一选中及禁用配色。末行留白不绘制假按钮。ETM 开始／停止／清空之间留 8 dp 间距；原值域与动作保持。

## Alpha25 边缘与系统栏

顶栏和状态栏 inset 使用固定的 `radioSurface`，关闭 AppBarLayout 的 lift-on-scroll 及其色调动画。保留 edge-to-edge 和已有 inset 高度；不通过硬编码系统状态栏颜色规避 Android 新版本的绘制规则。[MDC 1.12 AppBarLayout 实现](https://github.com/material-components/material-components-android/blob/1.12.0/lib/java/com/google/android/material/appbar/AppBarLayout.java)。

共享分段选择器在加入 MaterialButtonToggleGroup 前使用 `RelativeCornerSize(0.5f)`，由组件保留最外两端圆角、处理内部分段连接；不在 44 dp 高的控件上沿用 24 dp 固定圆角。取消子按钮装饰投影，避免外侧微小折线及阴影。布局和单选逻辑仍由 Material 控件负责。[分组实现](https://github.com/material-components/material-components-android/blob/1.12.0/lib/java/com/google/android/material/button/MaterialButtonToggleGroup.java)。

控制面板不再绘制顶部额外 12 dp 渐变覆盖层。BlurView 的模糊半径保持 12，色调遮罩 alpha 由 184/255 调至 171/255：不透明度约 72.2% → 67.1%，即透出背景增加约 5.1 个百分点。保留上方圆角裁切及清晰的前景控件。

## Alpha26 收藏、设置与展开

首页调谐手势入口旁使用书签图标，弹出同主题的简洁名称／MHz 频率编辑框；不调谐即可保存。Tune“我的收藏”与“频段预设”共用相同入口行和列表选择形式，支持按名称、备注或频率搜索，当前接收源不支持的频率置灰。新入口直接复用现有 `bookmarks` 表，收藏管理页仍保留分组、标记、导入导出。书签空心／实心图标替代星形，沿用原标记数据与主题色。

声音格式、PCM 位深、目标码率及参数信息直接嵌入设置“录制”分类，使用原偏好 key 与动态显隐；保留旧独立页作为内部兼容入口。分类标题在 Preference 的最终 bind 后设置强调色，保证首次显示及回收重绑一致。

二级 Activity 在统一基类里按 systemBars 与 displayCutout 添加内容留白；主接收页继续使用自己已有的频谱和面板 inset 规则。展开到频率数字下方时，在一次布局完成后的 pre-draw 中，先让 Material BottomSheetBehavior 进入原生 settling，再改变内容尺寸；避免 expandedOffset 的新布局先跳到终点。未另写面板动画引擎。[所用 MDC 1.12 实现](https://github.com/material-components/material-components-android/blob/1.12.0/lib/java/com/google/android/material/bottomsheet/BottomSheetBehavior.java)。

频谱／瀑布菜单图标保留波形和下方两条横线。

## Alpha27 顶栏、主题图标与标识

设置根布局使用 `radioSurface`，正文容器使用 `radioMain`。保留已有系统 inset 留白，状态栏空白区与设置工具栏因此同色，标题／返回仍在安全区域内。

菜单图标状态列表不再写死青蓝色：选中使用 `radioAccent`，未选中使用 `radioSecondary`，由主题提供。图标栏完整裁切为 16 dp 圆角，与外层面板上沿一致；较原 28 dp 更克制。用 45% 的高层色调叠在原模糊上，让底部圆角可见；模糊半径和此前透明度保持。

频段名称和范围分成两个相邻 TextView，中间以留白分隔。名称维持 12 sp，范围 10 sp；跨度至少 1 MHz 的范围显示一位小数，窄频段保留必要精度，单位 MHz。名称可省略，范围优先保留完整；原接收范围及调谐精度不改。模式／速率／场强栏的模式文字背景透明，文字继续用主题色。

CW／CWR、SSTV、SAT、EiBi 等内容页的共享并排操作行使用 48 dp 高度与 8 dp 间距，保留原动作及顺序。

应用标识为本衍生项目新绘制的矢量图形：开放的调谐环、窄信号峰与橙色指示点，不含字母或数字。深海军蓝背景、浅青绿环与浅色信号线取自现有配色体系。图形位于自适应图标安全区域，提供 Android 8+ adaptive-icon，以及 Android 13+ monochrome 前景。桌面标识保持固定品牌色，系统主题图标可使用系统色；应用内部控件仍跟随用户所选皮肤。矢量素材随项目源码许可提供，不冒用原版图标。

图标分层、安全区域与单色资源参考 [Android 官方自适应图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)；该规范使用 XML/vector 资源实现，不构成 Compose 迁移。

## Alpha28 分类标签与收藏配色

频道预设分类不再均分为五个窄按钮。复用现有 Material ChipGroup 的换行和单选能力，标签使用 14 sp、按内容确定宽度、最低 72 dp 宽度和原生 48 dp 触控目标，保留完整文字；横向间距 8 dp、行距 4 dp。分类筛选和当前设备能力判断保持。

预设与收藏筛选共用 `RadioChoiceChips`，使用 radioSelected／radioSurface、radioAccent／radioSecondary、radioOutline 及禁用色。预设允许换行；收藏的分组数量不定，保留横向滚动并显示完整名称，全部／未分组的筛选语义不变。

收藏条目底色改用 radioSurface，模式标签改用 radioSelected／radioAccent，亮色调谐按钮上的文字使用 radioMain。每条收藏原先单独保存的 color 标记不替换、不迁移，继续表达用户的分类，而不是 UI 主题色。
