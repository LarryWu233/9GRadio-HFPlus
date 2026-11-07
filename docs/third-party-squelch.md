# SQL 检测与实验性测量的来源

## 现有问题

原监听与录制门控使用 `FftEngine.lastPeakDb`，即整个 FFT 的最高峰，通带外强台也会使当前频道无法静噪。旧门控关闭条件还要求低于用户门限 3 dB，而非门限线本身。显示和判决取样时刻、频谱平均、200 ms 保持也会带来短暂差异，不能由此直接推导固定的 6–10 dB 校正。

## 对照与复用

- SDR++，固定提交 `f10fb98af8b80de01de94e60c17608e4281e2515`，`core/src/dsp/noise_reduction/power_squelch.h`，GPL-3.0。该版在 VFO 链内测量，而非整屏任意信号；旧实现还标记 TODO，采用平均幅度后 `10*log10`，本次只作结构比较，没有照搬其量纲或声称集成该门控。
- GNU Radio，固定提交 `ec1bf153c9222eca81f59d4e063e73d525a51a1a`，`gr-filter/include/gnuradio/filter/single_pole_iir.h` 与 `gr-analog/lib/pwr_squelch_cc_impl.cc`，GPL-3.0-or-later，FSF 2002/2006/2012。复用其单极点 IIR 递推 `alpha*x + (1-alpha)*previous` 与在线性功率域进行平滑的处理方式。Kotlin 适配将输入换为当前通带 FFT 峰值的线性功率，并由实际帧间隔计算约 40 ms 时间常数；不是移植整个 GNU Radio IQ 静噪模块。完整 GNU Radio 许可已随应用与源码保留。
- Gqrx，固定提交 `716f57858e64f07b29702ecd18b307bef228d22d`，`src/applications/gqrx/mainwindow.cpp::setSqlLevelAuto()`，GPL-3.0-or-later。其自动设置采用当前电平 +3 dB。这里借鉴余量和过高电平拒绝条件，但只建议、由用户应用；加入约 3.2 秒采样、较低时间分位数以减轻间歇话音影响。分位统计和 Android 界面／生命周期属于本项目适配。

## 本项目接口与边界

`ChannelSquelchMeter` 在现有 FFT 上按 BW、USB/LSB 方向及真实硬件中心选取通带，排除带外峰值。SQL 与图形继续使用同一 FFT bin dBFS 参考，不将频道总功率与单 bin 曲线直接混为同一数值。40 ms 平滑减少瞬态；保持现有 200 ms 关闭延迟与 10 ms 音量渐变，关闭判据回到用户门限，重新开启需高出 1 dB 以防抖。

频谱分辨率与平均会影响该参考。测量仅收集真实更新的 FFT 帧，缓存重放不计数；SPAN 未覆盖完整 BW、样本不足、持续电平过高时不提供建议。用户改变频率、BW、模式、采样率、FFT/显示宽度、增益或接收源时使此次结果失效；断开与离开前台会取消。

实验性结果不能区分纯底噪、持续载波和持续干扰，更不能保证保留所有弱航空话音。结果展示参考电平、3 dB 余量、波动和样本数，用户选择应用或取消；不会自动调谐或改动原 SQL。扫描器原有的窄带平均判据不作为此建议值的自动应用对象。数字解码继续取得监听静噪之前的信号。
