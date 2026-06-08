package com.autoaim.core;

import android.graphics.PointF;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 触控模拟模块 - 贝塞尔曲线拟人化
 *
 * 两种模式：
 * 1. Shizuku 模式：通过 Shizuku 获得 shell 权限，写 /dev/input/eventX
 * 2. ADB 模式：通过 adb shell input 命令（有延迟，备选）
 *
 * 拟人化策略：
 * - 贝塞尔曲线轨迹（非直线）
 * - 随机速度抖动
 * - 起始/结束减速（ease-in/ease-out）
 * - 随机偏移（模拟手指不精确）
 *
 * 线程安全设计：
 * - 有界线程池(容量1) + DiscardOldestPolicy：推理快于触控时丢弃旧任务
 * - generation 代数机制：新任务到达时旧任务自动中断，避免触控序列重叠
 */
public class TouchInput {

    private static final String TAG = "TouchInput";
    private final Random random = new Random();

    // 有界线程池 + 丢弃旧任务，防止推理快于触控时队列无限增长
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1),
            r -> {
                Thread t = new Thread(r, "TouchInput");
                t.setPriority(Thread.MAX_PRIORITY);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    // 任务代数：新任务到达时递增，旧任务检测到代数变化时提前终止
    private final AtomicInteger generation = new AtomicInteger(0);

    // 设备触控设备节点（需要根据设备调整）
    private String inputDevice = "/dev/input/event2";
    private Process shellProcess;
    private DataOutputStream shellOs;

    // 拟人化参数
    private float jitterRange = 3.0f;
    private int minMoveDelay = 6;
    private int maxMoveDelay = 14;
    private int bezierSegments = 8;
    private boolean useBezier = true;

    // 触控状态
    private int trackingId = 0;

    /**
     * 初始化 - 启动 shell 进程
     */
    public void init() {
        try {
            shellProcess = Runtime.getRuntime().exec("sh");
            shellOs = new DataOutputStream(shellProcess.getOutputStream());
            Log.i(TAG, "Touch input initialized");
        } catch (IOException e) {
            Log.e(TAG, "Failed to init shell", e);
        }
    }

    /**
     * 初始化 - 使用 Shizuku 提权
     */
    public void initWithShizuku(String devicePath) {
        this.inputDevice = devicePath;
        Log.i(TAG, "Touch input initialized with Shizuku, device=" + devicePath);
    }

    /**
     * 移动准心到目标位置（异步，不阻塞推理）
     * 完整的触摸生命周期：down → move → up
     * 新任务到达时自动中断旧任务
     */
    public void moveToAsync(float fromX, float fromY, float toX, float toY) {
        int gen = generation.incrementAndGet();
        executor.submit(() -> {
            try {
                if (useBezier) {
                    moveBezier(fromX, fromY, toX, toY, gen);
                } else {
                    moveLinear(fromX, fromY, toX, toY, gen);
                }
            } catch (Exception e) {
                Log.e(TAG, "Move failed", e);
            }
        });
    }

    /**
     * 点击指定位置（异步）
     */
    public void tapAsync(float x, float y) {
        executor.submit(() -> {
            try {
                float jx = x + (random.nextFloat() - 0.5f) * jitterRange;
                float jy = y + (random.nextFloat() - 0.5f) * jitterRange;
                int ix = Math.round(jx);
                int iy = Math.round(jy);

                touchDown(ix, iy);
                sleep(30 + random.nextInt(50));
                touchUp();
            } catch (Exception e) {
                Log.e(TAG, "Tap failed", e);
            }
        });
    }

    /**
     * 长按（开火）
     */
    public void longPressAsync(float x, float y, int durationMs) {
        executor.submit(() -> {
            try {
                float jx = x + (random.nextFloat() - 0.5f) * jitterRange;
                float jy = y + (random.nextFloat() - 0.5f) * jitterRange;

                touchDown(Math.round(jx), Math.round(jy));
                sleep(durationMs);
                touchUp();
            } catch (Exception e) {
                Log.e(TAG, "Long press failed", e);
            }
        });
    }

    // ============================================================
    // 触控事件协议（完整 Linux Input Multi-Touch Protocol B）
    // ============================================================

    /**
     * 触摸按下
     * ABS_MT_TRACKING_ID → BTN_TOUCH(1) → POSITION → SYN_REPORT
     */
    private void touchDown(int x, int y) {
        trackingId++;
        StringBuilder cmd = new StringBuilder();
        cmd.append(String.format("sendevent %s 3 57 %d\n", inputDevice, trackingId));
        cmd.append(String.format("sendevent %s 1 330 1\n", inputDevice));
        cmd.append(String.format("sendevent %s 3 53 %d\n", inputDevice, x));
        cmd.append(String.format("sendevent %s 3 54 %d\n", inputDevice, y));
        cmd.append(String.format("sendevent %s 0 0 0\n", inputDevice));
        execCommand(cmd.toString());
    }

    /**
     * 触摸移动
     * POSITION → SYN_REPORT
     */
    private void touchMove(int x, int y) {
        StringBuilder cmd = new StringBuilder();
        cmd.append(String.format("sendevent %s 3 53 %d\n", inputDevice, x));
        cmd.append(String.format("sendevent %s 3 54 %d\n", inputDevice, y));
        cmd.append(String.format("sendevent %s 0 0 0\n", inputDevice));
        execCommand(cmd.toString());
    }

    /**
     * 触摸抬起
     * TRACKING_ID(-1) → BTN_TOUCH(0) → SYN_REPORT
     */
    private void touchUp() {
        StringBuilder cmd = new StringBuilder();
        cmd.append(String.format("sendevent %s 3 57 -1\n", inputDevice));
        cmd.append(String.format("sendevent %s 1 330 0\n", inputDevice));
        cmd.append(String.format("sendevent %s 0 0 0\n", inputDevice));
        execCommand(cmd.toString());
    }

    /**
     * 贝塞尔曲线移动 - 拟人化
     * touchDown → 多次 touchMove → touchUp
     * 每段移动前检查代数，新任务到达时提前终止
     */
    private void moveBezier(float fromX, float fromY, float toX, float toY, int gen) {
        float dx = toX - fromX;
        float dy = toY - fromY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);

        if (distance < 2.0f) return;

        PointF cp1 = new PointF(
                fromX + dx * 0.3f + (random.nextFloat() - 0.5f) * jitterRange * 2,
                fromY + dy * 0.3f + (random.nextFloat() - 0.5f) * jitterRange * 2
        );
        PointF cp2 = new PointF(
                fromX + dx * 0.7f + (random.nextFloat() - 0.5f) * jitterRange * 2,
                fromY + dy * 0.7f + (random.nextFloat() - 0.5f) * jitterRange * 2
        );

        touchDown(Math.round(fromX), Math.round(fromY));

        for (int i = 1; i <= bezierSegments; i++) {
            if (generation.get() != gen) {
                touchUp();
                return;
            }

            float t = easeInOutCubic((float) i / bezierSegments);
            PointF point = cubicBezier(
                    new PointF(fromX, fromY), cp1, cp2,
                    new PointF(toX, toY), t
            );

            float jx = point.x + (random.nextFloat() - 0.5f) * jitterRange;
            float jy = point.y + (random.nextFloat() - 0.5f) * jitterRange;

            touchMove(Math.round(jx), Math.round(jy));
            sleep(minMoveDelay + random.nextInt(maxMoveDelay - minMoveDelay));
        }

        touchUp();
    }

    /**
     * 线性移动（备用）
     */
    private void moveLinear(float fromX, float fromY, float toX, float toY, int gen) {
        int steps = Math.max(3, (int)(Math.sqrt(
                (toX - fromX) * (toX - fromX) + (toY - fromY) * (toY - fromY)) / 20));

        touchDown(Math.round(fromX), Math.round(fromY));

        for (int i = 1; i <= steps; i++) {
            if (generation.get() != gen) {
                touchUp();
                return;
            }

            float t = easeInOutCubic((float) i / steps);
            float x = fromX + (toX - fromX) * t + (random.nextFloat() - 0.5f) * jitterRange;
            float y = fromY + (toY - fromY) * t + (random.nextFloat() - 0.5f) * jitterRange;

            touchMove(Math.round(x), Math.round(y));
            sleep(minMoveDelay + random.nextInt(maxMoveDelay - minMoveDelay));
        }

        touchUp();
    }

    private PointF cubicBezier(PointF p0, PointF p1, PointF p2, PointF p3, float t) {
        float u = 1 - t;
        float tt = t * t;
        float uu = u * u;
        float uuu = uu * u;
        float ttt = tt * t;

        return new PointF(
                uuu * p0.x + 3 * uu * t * p1.x + 3 * u * tt * p2.x + ttt * p3.x,
                uuu * p0.y + 3 * uu * t * p1.y + 3 * u * tt * p2.y + ttt * p3.y
        );
    }

    private float easeInOutCubic(float t) {
        return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
    }

    /**
     * 模拟滑动（用于拖拽操作）
     */
    public void swipeAsync(float x1, float y1, float x2, float y2, int durationMs) {
        executor.submit(() -> {
            try {
                touchDown(Math.round(x1), Math.round(y1));
                int steps = Math.max(3, durationMs / 16);
                for (int i = 1; i <= steps; i++) {
                    float t = (float) i / steps;
                    float x = x1 + (x2 - x1) * t;
                    float y = y1 + (y2 - y1) * t;
                    touchMove(Math.round(x), Math.round(y));
                    sleep(durationMs / steps);
                }
                touchUp();
            } catch (Exception e) {
                Log.e(TAG, "Swipe failed", e);
            }
        });
    }

    private void execCommand(String cmd) {
        try {
            if (shellOs != null) {
                shellOs.writeBytes(cmd);
                shellOs.flush();
            }
        } catch (IOException e) {
            Log.e(TAG, "Command failed", e);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }

    // ============================================================
    // 参数调节
    // ============================================================

    public void setJitterRange(float range) { this.jitterRange = range; }
    public void setMoveDelay(int min, int max) { this.minMoveDelay = min; this.maxMoveDelay = max; }
    public void setBezierSegments(int segments) { this.bezierSegments = segments; }
    public void setUseBezier(boolean use) { this.useBezier = use; }

    /**
     * 释放资源
     */
    public void release() {
        executor.shutdownNow();
        try {
            if (shellOs != null) {
                shellOs.writeBytes("exit\n");
                shellOs.flush();
                shellOs.close();
            }
            if (shellProcess != null) {
                shellProcess.destroy();
            }
        } catch (IOException ignored) {}
        Log.i(TAG, "Touch input released");
    }
}
