# libgnaffinity.so の由来

`app/src/main/jniLibs/arm64-v8a/libgnaffinity.so` はビルド済みバイナリです（アプリはパッケージ時に
CMake を実行しません）。`jni/gnaffinity.c` からビルドしています:

- sha256: `61272eb70f568de532333ebc6574866eb23b7bd88119975aba2cc16c526650b9`
- NDK 27.3.13750724、arm64-v8a、android-26、MinSizeRel、`-z max-page-size=16384`、ストリップ済み。
  再ビルドしても同じ sha256 になります
- ビルド: `app/src/main/cpp/gnaffinity/build.sh` を実行後、
  `cp app/src/main/cpp/gnaffinity/out/libgnaffinity.so app/src/main/jniLibs/arm64-v8a/`

エクスポートするのは `Java_app_gamenative_powercontrol_TaskAffinity_nativeSetTaskAffinity` のみです。
依存は libc、libm、libdl のみです。
