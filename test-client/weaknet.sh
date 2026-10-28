#!/bin/bash
# 弱网模拟脚本（macOS dummynet + pf）
# 对 本机(Mac 192.168.1.9) <-> 平板  之间的 UDP 媒体流量注入 带宽/时延/丢包，
# 不影响 TCP 信令（WebSocket 3000 端口），贴近真实弱网（UDP 抖动丢包）。
#
# 用法:
#   sudo ./weaknet.sh on   [带宽kbps] [时延ms] [丢包率0-1]   # 默认 300 150 0.10
#   sudo ./weaknet.sh off                                     # 清除全部注入
#   sudo ./weaknet.sh status                                  # 查看当前 pipe/规则
#
# IP 自动探测（可用环境变量覆盖）：MAC_IP / TABLET_IP。
# 本会话中通过 osascript 'with administrator privileges' 提权执行。
set -e

# 自动探测本机 en0/en1 IP；sudo 下取调用者设备
MAC_IP=${MAC_IP:-$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)}
# 自动探测平板 wlan0 IPv4（adb 已连接时）
detect_tablet_ip() {
  local adb_bin
  adb_bin=$(command -v adb 2>/dev/null) || adb_bin="/Users/${SUDO_USER:-$USER}/Library/Android/sdk/platform-tools/adb"
  [ -x "$adb_bin" ] || return 1
  "$adb_bin" -e shell ip -4 addr show wlan0 2>/dev/null | awk '/inet /{split($2,a,"/");print a[1];exit}'
}
TABLET_IP=${TABLET_IP:-$(detect_tablet_ip || true)}
[ -n "$MAC_IP" ]    || { echo "错误: 未探测到 MAC_IP，请用环境变量指定，如 MAC_IP=192.168.x.x sudo -E $0 ..."; exit 1; }
[ -n "$TABLET_IP" ] || { echo "错误: 未探测到 TABLET_IP（平板未连 adb？），请用环境变量 TABLET_IP=192.168.x.x 指定"; exit 1; }
BW=${2:-300}      # kbit/s
DELAY=${3:-150}   # ms
PLR=${4:-0.10}    # 丢包率

case "$1" in
  on)
    # 双向各一条 pipe：出向 pipe 1（Mac->平板），入向 pipe 2（平板->Mac）
    dnctl pipe 1 config bw ${BW}Kbit/s delay ${DELAY} plr ${PLR}
    dnctl pipe 2 config bw ${BW}Kbit/s delay ${DELAY} plr ${PLR}
    # 注意：macOS pf 的 dummynet 规则放在 anchor 内会被静默忽略，
    # 必须直接写入主规则集（macOS 默认 pf 关闭、主规则集为空，覆盖安全）。
    {
      echo "dummynet out proto udp from ${MAC_IP} to ${TABLET_IP} pipe 1"
      echo "dummynet in  proto udp from ${TABLET_IP} to ${MAC_IP} pipe 2"
    } | pfctl -f -
    pfctl -e || true
    echo "weaknet ON: bw=${BW}kbps delay=${DELAY}ms plr=${PLR} (UDP ${MAC_IP}<->${TABLET_IP})"
    ;;
  off)
    echo "" | pfctl -f - 2>/dev/null || true
    echo "" | pfctl -a weaknet -f - 2>/dev/null || true
    dnctl -q pipe 1 delete 2>/dev/null || true
    dnctl -q pipe 2 delete 2>/dev/null || true
    pfctl -d || true
    echo "weaknet OFF"
    ;;
  status)
    dnctl -q pipe show || true
    pfctl -s Anchors 2>/dev/null || true
    pfctl -a weaknet -s dummynet 2>/dev/null || true
    ;;
  *)
    grep '^#' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
