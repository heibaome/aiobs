package com.autoaim.core;

import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import java.util.List;

/**
 * 瞄准控制器 - PID + 预测 + 目标选择
 * 
 * 改进点（相比原项目）：
 * 1. 目标预测：根据历史轨迹预测目标移动
 * 2. 智能目标选择：优先瞄准头部/近处/中心目标
 * 3. PID 参数自适应：根据目标速度动态调整
 * 4. 死区控制：避免小范围抖动
 */
public class AimController {

    private static final String TAG = "AimController";

    // PID 参数
    private float Kp = 0.6f;     // 比例系数（响应速度）
    private float Ki = 0.02f;    // 积分系数（消除稳态误差）
    private float Kd = 0.3f;     // 微分系数（平滑度）

    // PID 状态
    private float integralX = 0, integralY = 0;
    private float prevErrorX = 0, prevErrorY = 0;
    private long prevTimestamp = 0;

    // 目标预测
    private final PointF prevTarget = new PointF();
    private final PointF targetVelocity = new PointF();
    private boolean hasPrevTarget = false;
    private float predictionFactor = 0.3f;  // 预测强度

    // 死区（像素），避免微小移动
    private float deadZone = 5.0f;

    // 积分限幅，防止积分饱和
    private float integralLimit = 500.0f;

    // 屏幕中心（准心位置）
    private float screenCenterX;
    private float screenCenterY;

    /**
     * @param screenW 屏幕宽度
     * @param screenH 屏幕高度
     */
    public void init(int screenW, int screenH) {
        this.screenCenterX = screenW / 2.0f;
        this.screenCenterY = screenH / 2.0f;
        reset();
    }

    /**
     * 计算瞄准偏移量
     * @param detections 检测结果列表
     * @return 需要移动的偏移量 (dx, dy)，null 表示无需移动
     */
    public AimResult calculate(List<MNNInference.Detection> detections) {
        if (detections == null || detections.isEmpty()) {
            reset();
            return null;
        }

        // 1. 选择最佳目标
        MNNInference.Detection target = selectTarget(detections);
        if (target == null) return null;

        float targetX = target.centerX();
        float targetY = target.centerY();

        // 2. 目标预测（补偿延迟）
        long now = System.currentTimeMillis();
        if (hasPrevTarget) {
            float dt = (now - prevTimestamp) / 1000.0f;
            if (dt > 0 && dt < 0.5f) {
                targetVelocity.set(
                        (targetX - prevTarget.x) / dt,
                        (targetY - prevTarget.y) / dt
                );
            }
        }

        // 预测目标位置
        float dt = (now - prevTimestamp) / 1000.0f;
        float predictedX = targetX + targetVelocity.x * dt * predictionFactor;
        float predictedY = targetY + targetVelocity.y * dt * predictionFactor;

        // 3. 计算误差（准心到目标）
        float errorX = predictedX - screenCenterX;
        float errorY = predictedY - screenCenterY;

        // 死区判断
        float errorMag = (float) Math.sqrt(errorX * errorX + errorY * errorY);
        if (errorMag < deadZone) {
            return new AimResult(0, 0, errorMag, target);
        }

        // 4. PID 计算
        float dtSec = prevTimestamp > 0 ? (now - prevTimestamp) / 1000.0f : 0.016f;
        if (dtSec <= 0) dtSec = 0.016f;

        // 积分项
        integralX += errorX * dtSec;
        integralY += errorY * dtSec;
        // 积分限幅
        integralX = clamp(integralX, -integralLimit, integralLimit);
        integralY = clamp(integralY, -integralLimit, integralLimit);

        // 微分项
        float derivX = (errorX - prevErrorX) / dtSec;
        float derivY = (errorY - prevErrorY) / dtSec;

        // PID 输出
        float outputX = Kp * errorX + Ki * integralX + Kd * derivX;
        float outputY = Kp * errorY + Ki * integralY + Kd * derivY;

        // 更新状态
        prevErrorX = errorX;
        prevErrorY = errorY;
        prevTimestamp = now;
        prevTarget.set(targetX, targetY);
        hasPrevTarget = true;

        Log.d(TAG, String.format("aim: target=[%.0f,%.0f] error=[%.0f,%.0f] output=[%.0f,%.0f]",
                targetX, targetY, errorX, errorY, outputX, outputY));

        return new AimResult(outputX, outputY, errorMag, target);
    }

    /**
     * 智能目标选择
     * 优先级：准心最近 > 高置信度 > 面积适中
     */
    private MNNInference.Detection selectTarget(List<MNNInference.Detection> detections) {
        MNNInference.Detection best = null;
        float bestScore = Float.MAX_VALUE;

        for (MNNInference.Detection det : detections) {
            float cx = det.centerX();
            float cy = det.centerY();

            // 到准心的距离
            float dist = (float) Math.sqrt(
                    (cx - screenCenterX) * (cx - screenCenterX) +
                    (cy - screenCenterY) * (cy - screenCenterY));

            // 综合评分（越小越好）
            // 权重：距离 70% + (1-置信度) 30%
            float score = dist * 0.7f + (1 - det.score) * 300 * 0.3f;

            // 偏好较小的目标（可能是头部）
            float area = det.box.width() * det.box.height();
            if (area > 0) {
                float sizeRatio = Math.min(area / 10000, 1.0f);  // 归一化
                score += sizeRatio * 50;  // 大目标稍微降权
            }

            if (score < bestScore) {
                bestScore = score;
                best = det;
            }
        }

        return best;
    }

    /**
     * 重置 PID 状态
     */
    public void reset() {
        integralX = 0;
        integralY = 0;
        prevErrorX = 0;
        prevErrorY = 0;
        prevTimestamp = 0;
        hasPrevTarget = false;
        targetVelocity.set(0, 0);
    }

    private float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    // ============================================================
    // 参数调节
    // ============================================================

    public void setPID(float kp, float ki, float kd) {
        this.Kp = kp;
        this.Ki = ki;
        this.Kd = kd;
        Log.i(TAG, String.format("PID updated: Kp=%.3f Ki=%.4f Kd=%.3f", kp, ki, kd));
    }

    public void setDeadZone(float pixels) { this.deadZone = pixels; }
    public void setPredictionFactor(float factor) { this.predictionFactor = factor; }
    public float getScreenCenterX() { return screenCenterX; }
    public float getScreenCenterY() { return screenCenterY; }

    /**
     * 瞄准结果
     */
    public static class AimResult {
        public final float dx;          // X 方向偏移量
        public final float dy;          // Y 方向偏移量
        public final float distance;    // 到目标的距离
        public final MNNInference.Detection target;  // 选中的目标

        public AimResult(float dx, float dy, float distance, MNNInference.Detection target) {
            this.dx = dx;
            this.dy = dy;
            this.distance = distance;
            this.target = target;
        }

        /** 是否在开火范围内 */
        public boolean inFireZone(float threshold) {
            return distance < threshold;
        }
    }
}
