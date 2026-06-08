package com.autoaim.core;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流水线控制器 - 截图/推理/触控三级并行
 *
 * Stage 1: 截图线程 (ScreenCapture 的 HandlerThread) → frameQueue
 * Stage 2: 推理线程 (InferenceThread) 消费 frameQueue，产出 aimQueue
 * Stage 3: 触控线程 (TouchThread) 消费 aimQueue，驱动 TouchInput
 *
 * 各阶段通过 BlockingQueue 连接，互不阻塞。
 */
public class PipelineController {

    private static final String TAG = "Pipeline";

    // 流水线各模块
    private final ScreenCapture screenCapture;
    private final MNNInference inference;
    private final AimController aimController;
    private final TouchInput touchInput;
    private final ThermalManager thermalManager;

    // Stage 1→2 队列：截图 → 推理
    private final BlockingQueue<FrameData> frameQueue = new LinkedBlockingQueue<>(2);

    // Stage 2→3 队列：推理 → 触控（有界，旧结果自动丢弃）
    private final BlockingQueue<AimCommand> aimQueue = new LinkedBlockingQueue<>(1);

    // 状态
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicInteger frameCount = new AtomicInteger(0);
    private final AtomicInteger dropCount = new AtomicInteger(0);

    // 配置
    private float confThreshold = 0.45f;
    private float nmsThreshold = 0.5f;
    private float fireThreshold = 80.0f;
    private boolean autoFire = true;

    // 性能统计
    private long lastFpsTime = 0;
    private int fpsFrameCount = 0;
    private float currentFps = 0;
    private long avgInferenceMs = 0;

    // 线程
    private Thread inferenceThread;
    private Thread touchThread;

    public PipelineController(ScreenCapture capture, MNNInference inference,
                              AimController aim, TouchInput touch, ThermalManager thermal) {
        this.screenCapture = capture;
        this.inference = inference;
        this.aimController = aim;
        this.touchInput = touch;
        this.thermalManager = thermal;
    }

    /**
     * 启动流水线
     */
    public void start() {
        if (isRunning.get()) return;
        isRunning.set(true);

        // Stage 2: 推理线程
        inferenceThread = new Thread(this::inferenceLoop, "InferenceThread");
        inferenceThread.setPriority(Thread.MAX_PRIORITY - 1);
        inferenceThread.start();

        // Stage 3: 触控线程
        touchThread = new Thread(this::touchLoop, "TouchThread");
        touchThread.setPriority(Thread.MAX_PRIORITY);
        touchThread.start();

        // Stage 1: 截图（回调触发，推入 frameQueue）
        screenCapture.start(this::onFrame);

        lastFpsTime = System.currentTimeMillis();
        Log.i(TAG, "Pipeline started (3-stage)");
    }

    /**
     * Stage 1 回调 - 截图线程生产帧
     */
    private void onFrame(Bitmap bitmap, long timestamp) {
        if (!isRunning.get()) return;

        // 热管理：跳帧
        int skipFrames = thermalManager.getSkipFrames();
        int count = frameCount.incrementAndGet();
        if (skipFrames > 1 && count % skipFrames != 0) {
            dropCount.incrementAndGet();
            return;
        }

        // 推入队列，满了就丢（推理跟不上）
        FrameData frame = new FrameData(bitmap, timestamp);
        if (!frameQueue.offer(frame)) {
            dropCount.incrementAndGet();
        }
    }

    /**
     * Stage 2 - 推理循环：消费 frameQueue，产出 aimQueue
     */
    private void inferenceLoop() {
        Log.i(TAG, "Inference thread started");

        while (isRunning.get()) {
            try {
                FrameData frame = frameQueue.take();
                if (!isRunning.get()) break;

                long t0 = System.nanoTime();

                // 推理
                List<MNNInference.Detection> detections = inference.detect(
                        frame.bitmap, confThreshold, nmsThreshold);

                long inferenceMs = (System.nanoTime() - t0) / 1_000_000;
                updateInferenceStats(inferenceMs);

                // 计算瞄准
                AimController.AimResult aimResult = aimController.calculate(detections);
                if (aimResult == null) continue;

                float currentX = aimController.screenCenterX;
                float currentY = aimController.screenCenterY;
                boolean shouldFire = autoFire && aimResult.inFireZone(fireThreshold);

                // 推入触控队列（容量1，旧的自动丢弃）
                AimCommand cmd = new AimCommand(
                        currentX, currentY,
                        currentX + aimResult.dx, currentY + aimResult.dy,
                        shouldFire);

                // 清空旧命令再推入新的（保证只处理最新结果）
                aimQueue.clear();
                aimQueue.offer(cmd);

                updateFps();

            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.e(TAG, "Inference error", e);
            }
        }

        Log.i(TAG, "Inference thread stopped");
    }

    /**
     * Stage 3 - 触控循环：消费 aimQueue，驱动 TouchInput
     */
    private void touchLoop() {
        Log.i(TAG, "Touch thread started");

        while (isRunning.get()) {
            try {
                AimCommand cmd = aimQueue.take();
                if (!isRunning.get()) break;

                // 移动准心
                touchInput.moveToAsync(cmd.fromX, cmd.fromY, cmd.toX, cmd.toY);

                // 自动开火
                if (cmd.shouldFire) {
                    touchInput.tapAsync(cmd.toX, cmd.toY);
                }

            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.e(TAG, "Touch error", e);
            }
        }

        Log.i(TAG, "Touch thread stopped");
    }

    /**
     * 停止流水线
     */
    public void stop() {
        isRunning.set(false);
        frameQueue.clear();
        aimQueue.clear();

        screenCapture.stop();

        if (inferenceThread != null) {
            inferenceThread.interrupt();
            inferenceThread = null;
        }
        if (touchThread != null) {
            touchThread.interrupt();
            touchThread = null;
        }

        Log.i(TAG, String.format("Pipeline stopped. Total frames: %d, dropped: %d, avg FPS: %.1f",
                frameCount.get(), dropCount.get(), currentFps));
    }

    // ============================================================
    // 性能统计
    // ============================================================

    private void updateFps() {
        fpsFrameCount++;
        long now = System.currentTimeMillis();
        long elapsed = now - lastFpsTime;
        if (elapsed >= 1000) {
            currentFps = fpsFrameCount * 1000.0f / elapsed;
            fpsFrameCount = 0;
            lastFpsTime = now;
            Log.i(TAG, String.format("FPS: %.1f, inference: %dms, dropped: %d",
                    currentFps, avgInferenceMs, dropCount.get()));
        }
    }

    private void updateInferenceStats(long ms) {
        avgInferenceMs = (avgInferenceMs * 7 + ms) / 8;
    }

    // ============================================================
    // 参数调节
    // ============================================================

    public void setConfThreshold(float threshold) { this.confThreshold = threshold; }
    public void setNmsThreshold(float threshold) { this.nmsThreshold = threshold; }
    public void setFireThreshold(float pixels) { this.fireThreshold = pixels; }
    public void setAutoFire(boolean enabled) { this.autoFire = enabled; }

    public float getCurrentFps() { return currentFps; }
    public long getAvgInferenceMs() { return avgInferenceMs; }
    public int getDropCount() { return dropCount.get(); }

    /**
     * 帧数据（Stage 1→2）
     */
    private static class FrameData {
        final Bitmap bitmap;
        final long timestamp;

        FrameData(Bitmap bitmap, long timestamp) {
            this.bitmap = bitmap;
            this.timestamp = timestamp;
        }
    }

    /**
     * 瞄准指令（Stage 2→3）
     */
    private static class AimCommand {
        final float fromX, fromY;
        final float toX, toY;
        final boolean shouldFire;

        AimCommand(float fromX, float fromY, float toX, float toY, boolean shouldFire) {
            this.fromX = fromX;
            this.fromY = fromY;
            this.toX = toX;
            this.toY = toY;
            this.shouldFire = shouldFire;
        }
    }
}
