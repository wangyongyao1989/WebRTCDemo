#!/bin/bash
# =============================================================================
# build_webrtc.sh — 从源码交叉编译 WebRTC (Android) 最新稳定版
# =============================================================================
#
# 环境要求：
#   - 操作系统：Ubuntu 18.04/20.04/22.04 (WebRTC Android 构建仅支持 Linux)
#   - 依赖：git, python3, curl, lsb-release, sudo
#   - 磁盘空间：≥ 100 GB
#   - 网络：可访问 chromium.googlesource.com
#
# 产出：
#   out/webrtc-android/<abi>/libjingle_peerconnection_so.so
#   以及对应的 Java 源码 (sdk/android/api, sdk/android/src/java, ...)
#
# 用法：
#   chmod +x build_webrtc.sh
#   ./build_webrtc.sh                  # 默认编译 branch-heads/6422 (对应 M125)
#   ./build_webrtc.sh branch-heads/6722 # 指定其他稳定分支
#
# 编译完成后，脚本会自动把 .so 和 Java 源码拷贝到 webrtclib 模块对应位置。
# =============================================================================

set -euo pipefail

# ---------------------------- 配置 ----------------------------
WEBRTC_BRANCH="${1:-branch-heads/6422}"   # M125 稳定分支；可改为更新的分支
WORK_DIR="${WORK_DIR:-$HOME/webrtc-build}"
DEPOT_TOOLS_DIR="$WORK_DIR/depot_tools"
WEBRTC_SRC_DIR="$WORK_DIR/src"
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
WEBRTCLIB_DIR="$PROJECT_ROOT/webrtclib"

# 目标 ABI 列表
ABIS=("arm64-v8a" "armeabi-v7a" "x86" "x86_64")

# CPU 架构 -> GN target_cpu 映射
declare -A GN_CPU_MAP=(
    ["arm64-v8a"]="arm64"
    ["armeabi-v7a"]="arm"
    ["x86"]="x86"
    ["x86_64"]="x64"
)

echo "================================================"
echo " WebRTC Android 编译脚本"
echo " 分支: $WEBRTC_BRANCH"
echo " 工作目录: $WORK_DIR"
echo " ABIs: ${ABIS[*]}"
echo "================================================"

# ---------------------------- 1. 安装系统依赖 ----------------------------
install_deps() {
    echo ">>> [1/6] 安装系统依赖..."
    sudo apt-get update -qq
    sudo apt-get install -y -qq \
        git python3 python3-pip curl lsb-release \
        build-essential libssl-dev libncurses5 \
        openjdk-11-jdk pkg-config
}

# ---------------------------- 2. 安装 depot_tools ----------------------------
install_depot_tools() {
    echo ">>> [2/6] 安装 depot_tools..."
    mkdir -p "$WORK_DIR"
    if [ ! -d "$DEPOT_TOOLS_DIR" ]; then
        git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git "$DEPOT_TOOLS_DIR"
    fi
    export PATH="$DEPOT_TOOLS_DIR:$PATH"
    echo "depot_tools 路径: $DEPOT_TOOLS_DIR"
}

# ---------------------------- 3. 拉取 WebRTC 源码 ----------------------------
fetch_webrtc() {
    echo ">>> [3/6] 拉取 WebRTC 源码（首次约 30-60 分钟）..."
    export PATH="$DEPOT_TOOLS_DIR:$PATH"
    cd "$WORK_DIR"
    if [ ! -d "$WEBRTC_SRC_DIR" ]; then
        fetch --nohooks webrtc_android
    fi
    cd "$WEBRTC_SRC_DIR"
    # 切换到指定稳定分支
    gclient sync --force --delete_unversioned_trees --reset
    git checkout "$WEBRTC_BRANCH" 2>/dev/null || {
        echo "警告: 无法切换到 $WEBRTC_BRANCH，使用 master 分支"
        git checkout master
    }
    gclient sync --force --delete_unversioned_trees --reset
    # 安装额外依赖
    ./build/install-build-deps.sh --android
}

# ---------------------------- 4. 编译各 ABI ----------------------------
build_abi() {
    local abi="$1"
    local target_cpu="${GN_CPU_MAP[$abi]}"
    local out_dir="out/android-$abi"

    echo ">>> 编译 $abi (target_cpu=$target_cpu)..."

    # GN 生成构建文件
    gn gen "$out_dir" --args="
        target_os=\"android\"
        target_cpu=\"$target_cpu\"
        is_debug=false
        is_component_build=false
        rtc_include_tests=false
        rtc_disable_logging=false
        use_custom_libcxx=false
        treat_warnings_as_errors=false
        ffmpeg_branding=\"Chrome\"
        is_clang=true
    "

    # Ninja 编译（仅编译 PeerConnection 相关目标）
    autoninja -C "$out_dir" \
        libjingle_peerconnection_so \
        webrtcRTCFieldTrials_native_unittest

    echo ">>> $abi 编译完成: $WEBRTC_SRC_DIR/$out_dir/libjingle_peerconnection_so.so"
}

build_all() {
    echo ">>> [4/6] 开始编译（每个 ABI 约 30-60 分钟）..."
    export PATH="$DEPOT_TOOLS_DIR:$PATH"
    cd "$WEBRTC_SRC_DIR"
    for abi in "${ABIS[@]}"; do
        build_abi "$abi"
    done
}

# ---------------------------- 5. 拷贝产物到 webrtclib ----------------------------
copy_artifacts() {
    echo ">>> [5/6] 拷贝编译产物到 webrtclib 模块..."

    # 5.1 拷贝 .so 文件
    local jnilibs_dir="$WEBRTCLIB_DIR/src/main/jniLibs"
    mkdir -p "$jnilibs_dir"
    for abi in "${ABIS[@]}"; do
        local so_src="$WEBRTC_SRC_DIR/out/android-$abi/libjingle_peerconnection_so.so"
        local so_dst="$jnilibs_dir/$abi/libjingle_peerconnection_so.so"
        if [ -f "$so_src" ]; then
            cp "$so_src" "$so_dst"
            echo "    ✓ $abi/libjingle_peerconnection_so.so ($(du -h "$so_dst" | cut -f1))"
        else
            echo "    ✗ $abi: .so 不存在，跳过"
        fi
    done

    # 5.2 拷贝 Java 源码（覆盖现有版本）
    local java_dst="$WEBRTCLIB_DIR/src/main/java/org/webrtc"
    mkdir -p "$java_dst"
    rm -rf "$java_dst"/*

    local src_dirs=(
        "$WEBRTC_SRC_DIR/sdk/android/api/org"
        "$WEBRTC_SRC_DIR/sdk/android/src/java/org"
        "$WEBRTC_SRC_DIR/rtc_base/java/src/org"
        "$WEBRTC_SRC_DIR/modules/audio_device/android/java/src/org"
    )
    for src_dir in "${src_dirs[@]}"; do
        if [ -d "$src_dir" ]; then
            cp -R "$src_dir"/* "$java_dst/../" 2>/dev/null || true
        fi
    done

    # 清理非 Java 文件
    find "$java_dst" -name "OWNERS" -delete 2>/dev/null || true
    find "$java_dst" -name "*.md" -delete 2>/dev/null || true

    local java_count
    java_count=$(find "$java_dst" -name "*.java" | wc -l)
    echo "    ✓ Java 源码: $java_count 个文件"
}

# ---------------------------- 6. 验证 ----------------------------
verify() {
    echo ">>> [6/6] 验证产物..."
    local jnilibs_dir="$WEBRTCLIB_DIR/src/main/jniLibs"
    echo "    .so 文件:"
    for abi in "${ABIS[@]}"; do
        local so="$jnilibs_dir/$abi/libjingle_peerconnection_so.so"
        if [ -f "$so" ]; then
            echo "      ✓ $abi: $(du -h "$so" | cut -f1)"
        else
            echo "      ✗ $abi: 缺失!"
        fi
    done
    echo ""
    echo "================================================"
    echo " 编译完成！"
    echo " .so 路径: $jnilibs_dir/<abi>/libjingle_peerconnection_so.so"
    echo " Java 路径: $WEBRTCLIB_DIR/src/main/java/org/webrtc/"
    echo ""
    echo " 接下来在项目根目录执行:"
    echo "   ./gradlew :app:assembleDebug"
    echo "================================================"
}

# ---------------------------- 主流程 ----------------------------
main() {
    install_deps
    install_depot_tools
    fetch_webrtc
    build_all
    copy_artifacts
    verify
}

main "$@"
