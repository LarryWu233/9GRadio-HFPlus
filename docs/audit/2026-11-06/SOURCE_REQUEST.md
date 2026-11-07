# Draft request for matching 9GRadio 1.83 source

This is a draft for review; it has not been posted or sent.

Hi, I am preparing an Android Airspy HF+ Discovery integration and would like to preserve the existing 9GRadio 1.83 features.

The official 1.83 APK reports versionCode 83. However, the `app` tree at the `9GRadio_v1.83` tag (`625424bbb0cae43cfbce657607942177cd501178`) is identical to the v1.39 commit (`508392f3c2ebaaaa103ddb258ebb2506f96297d3`): both use tree `da22ac0333b35900335e6bbea3b335b0539d475d`. The build configuration still declares versionCode 39.

The 1.83 APK contains components absent from that public tree, including FileIqSource, AcarsNative / AcarsMultiChannelDecoder, AisDecoder, RedseaProcess, RigControlManager, SatTracker, the newer NR profile declarations, and several native library integrations.

Could you provide the complete corresponding buildable source for 1.83, or point to its repository/commit/archive? This would include Kotlin/Java, resources, JNI and native sources, third-party source versions and patches, CMake configuration, Gradle launcher files, and the required toolchain versions/build instructions.

The APK checked was `9GRadio_v1.83_release.apk`, SHA-256 `c3c78d6c1ef4d84f96630cf65d5403abdcca522ed8bde35a7470c32a554d99a7`.

Thank you.
