package com.wangyao.webrtclib.peer;

import android.util.Log;

import org.webrtc.RTCStats;
import org.webrtc.RTCStatsCollectorCallback;
import org.webrtc.RTCStatsReport;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 弱网质量监测器：周期性调用 {@code PeerConnection.getStats()}，
 * 从 WebRTC 统计报告中提取网络质量指标并做「分级 + 迟滞」判定。
 *
 * 指标来源（libwebrtc stats 类型）：
 *   - candidate-pair(选中项): currentRoundTripTime —— 往返时延
 *                             availableOutgoingBitrate —— GCC 拥塞控制估算的可用上行带宽
 *   - inbound-rtp(video):    packetsReceived / packetsLost 增量 —— 下行丢包率
 *                            bytesReceived 增量 —— 实测下行码率
 *   - outbound-rtp(video):   bytesSent 增量 —— 实测上行码率
 *
 * 分级：EXCELLENT(优) / GOOD(良) / POOR(差) / BAD(极差)。
 * 迟滞防抖：连续 2 个采样更差才降级，连续 4 个采样更好才恢复，避免临界抖动来回切换。
 */
public class NetworkQualityMonitor {
    private static final String TAG = "NetworkQualityMonitor";
    private static final long INTERVAL_MS = 2000;
    /** 降级需要连续命中的坏采样数；恢复需要连续命中的好采样数。 */
    private static final int DEGRADE_STREAK = 2;
    private static final int RECOVER_STREAK = 4;

    public enum Quality {
        EXCELLENT("优"), GOOD("良"), POOR("差"), BAD("极差");
        public final String label;
        Quality(String label) { this.label = label; }
    }

    public interface QualityListener {
        /** 质量等级发生变化时回调（含首次判定）。detail 为可读指标串。 */
        void onQualityChanged(String userId, Quality quality, String detail);
    }

    private final Peer peer;
    private final String userId;
    private final QualityListener listener;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> task;
    private final AtomicBoolean started = new AtomicBoolean(false);

    // ---- 上一次采样的累计值（用于增量计算） ----
    private long prevTimeMs = 0;
    private long prevRxPackets = -1, prevRxLost = -1, prevRxBytes = -1, prevTxBytes = -1;

    private Quality current = null;
    private int worseStreak = 0, betterStreak = 0;

    public NetworkQualityMonitor(Peer peer, String userId, QualityListener listener) {
        this.peer = peer;
        this.userId = userId;
        this.listener = listener;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        Log.i(TAG, "start monitor: " + userId);
        task = scheduler.scheduleWithFixedDelay(this::poll,
                INTERVAL_MS, INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        started.set(false);
        if (task != null) task.cancel(false);
        scheduler.shutdown();
        Log.i(TAG, "stop monitor: " + userId);
    }

    private void poll() {
        try {
            peer.getStats(new RTCStatsCollectorCallback() {
                @Override
                public void onStatsDelivered(RTCStatsReport report) {
                    try {
                        analyze(report);
                    } catch (Exception e) {
                        Log.w(TAG, "analyze: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "getStats failed (peer closing?): " + e.getMessage());
        }
    }

    // ============================ 指标提取 ============================

    private static final class Metrics {
        double rttMs = -1;          // 往返时延
        double lossPct = -1;        // 下行丢包率 %
        double rxKbps = -1;         // 实测下行码率
        double txKbps = -1;         // 实测上行码率
        Double availOutBps = null;  // GCC 估算可用上行带宽
    }

    private void analyze(RTCStatsReport report) {
        Metrics m = new Metrics();
        long now = System.currentTimeMillis();
        long rxPackets = -1, rxLost = -1, rxBytes = -1, txBytes = -1;

        for (RTCStats s : report.getStatsMap().values()) {
            Map<String, Object> d = s.getMembers();
            switch (s.getType()) {
                case "candidate-pair":
                    if (isTruthy(d.get("nominated")) || isTruthy(d.get("selected"))
                            || isTruthy(d.get("inUse"))) {
                        Double rtt = num(d.get("currentRoundTripTime"));
                        if (rtt != null) m.rttMs = rtt * 1000.0; // 秒 -> 毫秒
                        m.availOutBps = num(d.get("availableOutgoingBitrate"));
                    }
                    break;
                case "inbound-rtp":
                    if (isVideo(d)) {
                        Long p = lnum(d.get("packetsReceived"));
                        Long l = lnum(d.get("packetsLost"));
                        Long b = lnum(d.get("bytesReceived"));
                        if (p != null) rxPackets = p;
                        if (l != null) rxLost = l;
                        if (b != null) rxBytes = b;
                    }
                    break;
                case "outbound-rtp":
                    if (isVideo(d)) {
                        Long b = lnum(d.get("bytesSent"));
                        if (b != null) txBytes = b;
                    }
                    break;
                default:
                    break;
            }
        }

        // 增量计算丢包率与码率
        if (prevTimeMs > 0 && rxPackets >= 0 && rxLost >= 0 && prevRxPackets >= 0) {
            double dtS = Math.max(0.001, (now - prevTimeMs) / 1000.0);
            long dPackets = Math.max(0, rxPackets - prevRxPackets);
            long dLost = Math.max(0, rxLost - prevRxLost); // 恢复时可能为负，钳 0
            long total = dPackets + dLost;
            if (total > 0) m.lossPct = 100.0 * dLost / total;
            if (rxBytes >= 0 && prevRxBytes >= 0) {
                m.rxKbps = Math.max(0, rxBytes - prevRxBytes) * 8.0 / 1024.0 / dtS;
            }
            if (txBytes >= 0 && prevTxBytes >= 0) {
                m.txKbps = Math.max(0, txBytes - prevTxBytes) * 8.0 / 1024.0 / dtS;
            }
        }
        prevTimeMs = now;
        if (rxPackets >= 0) prevRxPackets = rxPackets;
        if (rxLost >= 0) prevRxLost = rxLost;
        if (rxBytes >= 0) prevRxBytes = rxBytes;
        if (txBytes >= 0) prevTxBytes = txBytes;

        if (m.rttMs < 0 && m.lossPct < 0 && m.availOutBps == null) return; // 无有效样本
        Quality raw = grade(m);
        String detail = String.format("RTT=%s 丢包=%s 下行=%s 上行=%s%s",
                fmt(m.rttMs, "%.0fms"), fmt(m.lossPct, "%.1f%%"),
                fmt(m.rxKbps, "%.0fkbps"), fmt(m.txKbps, "%.0fkbps"),
                m.availOutBps != null
                        ? String.format(" 可用上行=%.0fkbps", m.availOutBps / 1000) : "");
        applyHysteresis(raw, detail);
    }

    /** 指标无数据(-1)时显示为 '-'，避免误导性的负值。 */
    private static String fmt(double v, String f) {
        return v < 0 ? "-" : String.format(f, v);
    }

    /** 按阈值给单次采样定级（任一指标命中更差档即取更差档）。 */
    private Quality grade(Metrics m) {
        int level = Quality.EXCELLENT.ordinal();
        level = Math.max(level, threshold(m.rttMs, 100, 200, 400));
        level = Math.max(level, threshold(m.lossPct, 2, 5, 15));
        if (m.availOutBps != null) {
            // 带宽按 kbps 判定：>=400 优 / >=250 良 / >=120 差 / <120 极差
            level = Math.max(level, thresholdBandwidth(m.availOutBps / 1000.0, 400, 250, 120));
        }
        return Quality.values()[level];
    }

    /** good/mid/bad 三阈值：v<good→优(0)，v<mid→良(1)，v<bad→差(2)，否则极差(3)。 */
    private static int threshold(double v, double good, double mid, double bad) {
        if (v < 0) return 0; // 无数据不判
        if (v < good) return 0;
        if (v < mid) return 1;
        if (v < bad) return 2;
        return 3;
    }

    /** 反向阈值（带宽越大越好）：v>=hi→优, v>=good→良, v>=mid→差, 否则极差。 */
    private static int thresholdBandwidth(double v, double hi, double good, double mid) {
        if (v >= hi) return 0;
        if (v >= good) return 1;
        if (v >= mid) return 2;
        return 3;
    }

    /** 迟滞：连续 DEGRADE_STREAK 次更差才降级；连续 RECOVER_STREAK 次更好才升级。 */
    private synchronized void applyHysteresis(Quality raw, String detail) {
        if (current == null) {
            current = raw;
            notifyQuality(raw, detail);
            return;
        }
        if (raw.ordinal() > current.ordinal()) {
            betterStreak = 0;
            if (++worseStreak >= DEGRADE_STREAK) {
                // 一步到位降到最差观测档
                current = raw;
                worseStreak = 0;
                notifyQuality(raw, detail);
            }
        } else if (raw.ordinal() < current.ordinal()) {
            worseStreak = 0;
            if (++betterStreak >= RECOVER_STREAK) {
                // 恢复按档渐进，每次只回升一级
                current = Quality.values()[current.ordinal() - 1];
                betterStreak = 0;
                notifyQuality(current, detail);
            }
        } else {
            worseStreak = 0;
            betterStreak = 0;
        }
    }

    private void notifyQuality(Quality q, String detail) {
        Log.i(TAG, "quality -> " + q.label + " (" + detail + ") user=" + userId);
        if (listener != null) listener.onQualityChanged(userId, q, detail);
    }

    // ============================ 小工具 ============================

    private static boolean isVideo(Map<String, Object> d) {
        Object k = d.containsKey("kind") ? d.get("kind") : d.get("mediaType");
        return k == null || "video".equals(String.valueOf(k));
    }

    private static boolean isTruthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        return "true".equals(String.valueOf(o));
    }

    private static Double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : null;
    }

    private static Long lnum(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : null;
    }
}
