# libgncounters.so の由来

`app/src/main/jniLibs/arm64-v8a/libgncounters.so` はビルド済みバイナリです（アプリはパッケージ時に
CMake を実行しません）。このディレクトリのソースからビルドしています:

- sha256: `f4355e4a2e22c6f41a61df8dc2cf15d3e67e320ec67d74bacf53ab136f5a9f65`（1,187,456 バイト）
- Arm libGPUCounters タグ `2.5.0`、コミット `378c7b59e2dae87cbbffb0ac9cf96ed13c939823`
  （https://github.com/ARM-software/libGPUCounters、MIT、`LICENSE-libGPUCounters.md` 参照）を
  静的リンク
- NDK 27.3.13750724、arm64-v8a、android-26、c++_static、MinSizeRel、`-z max-page-size=16384`、
  ストリップ済み
- ビルド: `app/src/main/cpp/gncounters/build.sh` を実行後、
  `cp app/src/main/cpp/gncounters/out/libgncounters.so app/src/main/jniLibs/arm64-v8a/`

`build.sh` は libGPUCounters を `third_party/`（gitignore 対象）にクローンし、それ以外や改変された
チェックアウトは拒否します。ビルドは再現可能で、再ビルドしても同じ sha256 になります。依存は
liblog、libm、libdl、libc のみです。エクスポートするのは 4 つの
`Java_app_gamenative_powercontrol_metrics_MaliGpuCounters_*` エントリポイントのみです。
