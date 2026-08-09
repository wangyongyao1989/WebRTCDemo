#!/bin/bash
# =============================================================================
# download_prebuilt.sh — 从网络下载稳定的 WebRTC 预编译产物（替代手动交叉编译）
# =============================================================================
#
# 无需 Ubuntu/Linux 环境，直接在 macOS 上运行即可获取：
#   1. .so 库（4 个 ABI: arm64-v8a, armeabi-v7a, x86, x86_64）
#   2. WebRTC Java SDK 源码（189 个 .java 文件，与 .so 版本严格匹配）
#   3. C++ 头文件（1050+ 个 .h 文件，供后续 native 开发使用）
#
# 数据来源：
#   - .so + Java 源码：GetStream/webrtc-android（GitHub，活跃维护，版本 1.3.x）
#     https://github.com/GetStream/webrtc-android
#   - C++ 头文件：shiguredo-webrtc-build（M142 稳定版 SDK tarball）
#     https://github.com/shiguredo-webrtc-build/webrtc-build
#
# 用法：
#   chmod +x download_prebuilt.sh
#   ./download_prebuilt.sh                # 下载最新版
#   ./download_prebuilt.sh 1.3.8          # 下载指定 GetStream 版本
#
# 依赖：curl, unzip, tar（macOS 自带）
# =============================================================================

set -euo pipefail

GETSTREAM_VERSION="${1:-latest}"
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
WEBRTCLIB_DIR="$PROJECT_ROOT/webrtclib"
TMP_DIR="${TMPDIR:-/tmp}/webrtc-prebuilt-$$"

echo "================================================"
echo " WebRTC 预编译产物下载脚本"
echo " GetStream 版本: $GETSTREAM_VERSION"
echo " 临时目录: $TMP_DIR"
echo "================================================"

mkdir -p "$TMP_DIR"

# ---------------------------- 1. 下载 .so + Java 源码 ----------------------------
download_getstream() {
    echo ">>> [1/3] 下载 GetStream/webrtc-android（.so + Java 源码）..."

    local repo_zip="$TMP_DIR/getstream-repo.zip"
    local repo_dir="$TMP_DIR/getstream-repo"

    if [ "$GETSTREAM_VERSION" = "latest" ]; then
        local download_url="https://github.com/GetStream/webrtc-android/archive/refs/heads/main.zip"
    else
        local download_url="https://github.com/GetStream/webrtc-android/archive/refs/tags/$GETSTREAM_VERSION.zip"
    fi

    echo "    下载: $download_url"
    curl -sL -o "$repo_zip" "$download_url"
    echo "    解压..."
    mkdir -p "$repo_dir"
    cd "$repo_dir"
    unzip -qo "$repo_zip"

    # 找到解压后的目录名
    local src_dir
    src_dir=$(find "$repo_dir" -maxdepth 1 -type d -name "webrtc-android-*" | head -1)
    if [ -z "$src_dir" ]; then
        echo "    错误: 无法找到解压目录"
        exit 1
    fi

    # 1.1 替换 .so 文件
    echo "    替换 .so 文件..."
    local jnilibs_dst="$WEBRTCLIB_DIR/src/main/jniLibs"
    rm -rf "$jnilibs_dst"/*
    for abi in arm64-v8a armeabi-v7a x86 x86_64; do
        mkdir -p "$jnilibs_dst/$abi"
        cp "$src_dir/stream-webrtc-android/libs/$abi/libjingle_peerconnection_so.so" "$jnilibs_dst/$abi/"
        echo "      ✓ $abi ($(du -h "$jnilibs_dst/$abi/libjingle_peerconnection_so.so" | cut -f1))"
    done

    # 1.2 替换 Java 源码
    echo "    替换 Java 源码..."
    local java_dst="$WEBRTCLIB_DIR/src/main/java/org/webrtc"
    rm -rf "$java_dst"
    cp -R "$src_dir/stream-webrtc-android/src/main/java/org/webrtc" "$java_dst"
    find "$java_dst" -name "OWNERS" -delete 2>/dev/null || true
    find "$java_dst" -name "*.md" -delete 2>/dev/null || true
    local java_count
    java_count=$(find "$java_dst" -name "*.java" | wc -l)
    echo "      ✓ $java_count 个 Java 文件"
}

# ---------------------------- 2. 下载 C++ 头文件 ----------------------------
download_headers() {
    echo ">>> [2/3] 下载 shiguredo C++ 头文件（M142 SDK tarball）..."

    local sdk_url="https://github.com/shiguredo-webrtc-build/webrtc-build/releases/download/m142.7444.2.1/webrtc.android_sdk.tar.gz"
    local sdk_tarball="$TMP_DIR/webrtc-android-sdk.tar.gz"
    local sdk_dir="$TMP_DIR/webrtc-sdk"

    echo "    下载: $sdk_url"
    curl -sL -o "$sdk_tarball" "$sdk_url"
    echo "    解压..."
    mkdir -p "$sdk_dir"
    tar xzf "$sdk_tarball" -C "$sdk_dir"

    # 仅拷贝关键公开 API 头文件（非全部 52K 文件）
    local include_src="$sdk_dir/webrtc/include"
    local include_dst="$WEBRTCLIB_DIR/src/main/cpp/include"
    rm -rf "$include_dst"
    mkdir -p "$include_dst"

    for dir in api sdk rtc_base pc common_video media call system_wrappers; do
        if [ -d "$include_src/$dir" ]; then
            cp -R "$include_src/$dir" "$include_dst/"
        fi
    done

    local header_count
    header_count=$(find "$include_dst" -name "*.h" | wc -l)
    echo "      ✓ $header_count 个头文件"
}

# ---------------------------- 3. 验证 ----------------------------
verify() {
    echo ">>> [3/3] 验证产物..."
    local jnilibs_dir="$WEBRTCLIB_DIR/src/main/jniLibs"
    local java_dir="$WEBRTCLIB_DIR/src/main/java/org/webrtc"
    local include_dir="$WEBRTCLIB_DIR/src/main/cpp/include"

    echo "    .so 文件:"
    for abi in arm64-v8a armeabi-v7a x86 x86_64; do
        local so="$jnilibs_dir/$abi/libjingle_peerconnection_so.so"
        if [ -f "$so" ]; then
            echo "      ✓ $abi: $(du -h "$so" | cut -f1)"
        else
            echo "      ✗ $abi: 缺失!"
        fi
    done

    echo "    Java 源码: $(find "$java_dir" -name "*.java" | wc -l | tr -d ' ') 个文件"
    echo "    C++ 头文件: $(find "$include_dir" -name "*.h" | wc -l | tr -d ' ') 个文件"

    echo ""
    echo "================================================"
    echo " 下载完成！"
    echo " 接下来在项目根目录执行:"
    echo "   ./gradlew :app:assembleDebug"
    echo "================================================"
}

# ---------------------------- 清理 ----------------------------
cleanup() {
    rm -rf "$TMP_DIR"
}

# ---------------------------- 主流程 ----------------------------
main() {
    download_getstream
    download_headers
    verify
    cleanup
}

main
