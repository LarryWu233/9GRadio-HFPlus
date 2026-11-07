# 声音录制与播放来源

alpha21 使用平台编码器和成熟播放器，未自行实现 AAC、Opus 或播放解码算法。

| 部分 | 来源、固定版本及适配 |
| --- | --- |
| 声音编码 | Android [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec) 同步缓冲接口；AAC-LC → M4A、Opus → OGG。设备编码器能力决定可选格式；Opus 编码及 OGG 封装要求 API 29+。应用以 PCM 16-bit 送入编码器，目标码率不代表编码精度或严格恒定的文件码率。 |
| 封装 | Android [MediaMuxer](https://developer.android.com/reference/android/media/MediaMuxer)，按编码器输出格式建立轨道，保留时间戳并等待 EOS 完成；不自行编写 MP4／OGG 容器。 |
| 重采样 | 仅当输入不是 48 kHz 而选择 Opus 时，复用项目已有 `PolyphaseResampler`，按声道处理并保持原始输入时长。没有再写一个重采样内核。 |
| WAV | 扩展公开源码已有 RIFF PCM 写入方式，支持 16／24-bit 整数和 32-bit IEEE float、奇数字节填充与大小回写，复用缓冲区。保留旧 `.wav` API 的 16-bit 默认值。RIFF 仍有约 4 GB 限制，未实现 RF64。 |
| 播放 | [AndroidX Media3](https://developer.android.com/media/media3/session/background-playback) `media3-exoplayer` 和 `media3-session` **1.7.1**，[Apache-2.0](licenses/Apache-2.0.txt)，通过 Google Maven 固定依赖。上游源码：[androidx/media tag 1.7.1](https://github.com/androidx/media/tree/1.7.1)，许可：[Apache-2.0](https://github.com/androidx/media/blob/1.7.1/LICENSE)。使用 ExoPlayer、MediaSessionService、MediaController 负责解码、暂停、定位、音频焦点及媒体通知；应用仅提供录音文件和界面控制。 |

录制适配位于 `AudioFileRecorder`：有界队列及专用线程使编码不阻塞 AudioTrack 写入；跟不上时报告录制失败，不静默丢弃录音块或拖慢监听。参数按下一次录制生效；设备不支持导入的编码格式时退回可用格式。默认 AAC 128 kb/s；WAV 提供位深，压缩格式提供目标码率。无损 WAV 更适合后续弱信号音频解码，有损编码可能破坏这类数据。

播放器仅接受应用录音目录内的声音文件。回放时暂停实时监听输出，接收、解码与录制仍运行；停止或播放结束后恢复监听，不修改用户保存的音量。暂停录音回放时仍保持实时监听静音，避免两个声音来源交替。IQ 保持原有回放入口，没有新增 IQ 暂停或随机定位。

Media3 不提供额外本机 `.so`；设备编解码能力与格式支持以 Android 为准。没有新增 MP3 编码或声称压缩格式无损。来源与验证范围见本轮验证记录，Android 16、旧版系统及长期运行仍需分别验收。
