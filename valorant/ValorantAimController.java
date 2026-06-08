package com.autoaim.valorant;

import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import com.autoaim.core.AimController;
import com.autoaim.core.MNNInference;

import java.util.List;

/**
 * 无畏契约手游 专属瞄准控制器
 * 
 * 核心特性：
 * 1. 爆头优先 - 步枪模式下优先瞄准头部
 * 2. 压枪补偿 - 根据武器类型自动补偿后坐力
 * 3. 急停模拟 - 移动中射击需要急停
 * 4. 武器切换 - 根据武器自动调整 PID 和瞄准策略
 * 5. 技能规避 - 检测到敌方技能时暂停瞄准
 */
public class ValorantAimController {

    private static final String TAG = "ValorantAim";

    // 当前武器类型
    public enum WeaponType {
        RIFLE,      // 步枪 (Vandal/Phantom/Guardian)
        SMG,        // 冲锋枪 (Spectre/Stinger)
        SNIPER,     // 狙击 (Operator/Marshal)
        SHOTGUN,    // 霰弹枪 (Judge/Bucky)
        PISTOL,     // 手枪 (Sheriff/Ghost/Classic)
        HEAVY       // 重武器 (Ares/Odin)
    }

    private final ValorantConfig config;
    private final AimController aimController;

    // 压枪状态
    private volatile int bulletCount = 0;
    private float totalRecoilY = 0;
    private float totalRecoilX = 0;
    private volatile long lastFireTime = 0;

    // 当前武器
    private WeaponType currentWeapon = WeaponType.RIFLE;

    // 屏幕参数
    private int screenW, screenH;
    private float screenCenterX, screenCenterY;

    public ValorantAimController(ValorantConfig config) {
        this.config = config;
        this.aimController = new AimController();
    }

    public void init(int screenW, int screenH) {
        this.screenW = screenW;
        this.screenH = screenH;
        this.screenCenterX = screenW / 2.0f;
        this.screenCenterY = screenH / 2.0f;
        aimController.init(screenW, screenH);
        updatePIDForWeapon();
    }

    /**
     * 设置当前武器
     */
    public void setWeapon(WeaponType weapon) {
        if (this.currentWeapon != weapon) {
            this.currentWeapon = weapon;
            resetRecoil();
            updatePIDForWeapon();
            Log.i(TAG, "Weapon changed: " + weapon);
        }
    }

    /**
     * 核心瞄准计算
     * @param detections 检测结果
     * @return 瞄准结果（包含偏移、是否开火、压枪补偿）
     */
    public ValorantAimResult calculate(List<MNNInference.Detection> detections) {
        if (detections == null || detections.isEmpty()) {
            resetRecoil();
            aimController.reset();
            return null;
        }

        // 1. 选择目标（无畏契约专属策略）
        MNNInference.Detection target = selectTarget(detections);
        if (target == null) return null;

        // 2. 计算瞄准偏移（根据瞄准部位）
        float aimX = target.centerX();
        float aimY = calculateAimY(target);

        // 3. 计算到准心的距离
        float dx = aimX - screenCenterX;
        float dy = aimY - screenCenterY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);

        // 4. PID 计算
        AimController.AimResult pidResult = aimController.calculate(detections);
        if (pidResult == null) return null;

        // 5. 压枪补偿
        float recoilCompX = 0, recoilCompY = 0;
        if (isFiring()) {
            recoilCompX = getRecoilCompX();
            recoilCompY = getRecoilCompY();
            bulletCount++;
        }

        // 6. 判断是否开火
        boolean shouldFire = shouldFire(distance, target);

        // 7. 最终偏移量
        float finalDx = pidResult.dx + recoilCompX;
        float finalDy = pidResult.dy + recoilCompY;

        return new ValorantAimResult(
                finalDx, finalDy,
                distance, target,
                shouldFire,
                recoilCompX, recoilCompY,
                bulletCount
        );
    }

    /**
     * 无畏契约专属目标选择
     * 
     * 策略：
     * 1. 近距离（< 200px）：优先头部
     * 2. 中距离（200-400px）：优先胸部（稳定性）
     * 3. 远距离（> 400px）：优先身体（命中率）
     * 4. 狙击模式：始终优先头部
     */
    private MNNInference.Detection selectTarget(List<MNNInference.Detection> detections) {
        MNNInference.Detection best = null;
        float bestScore = Float.MAX_VALUE;

        for (MNNInference.Detection det : detections) {
            float cx = det.centerX();
            float cy = det.centerY();

            // 到准心的距离
            float distToCenter = (float) Math.sqrt(
                    (cx - screenCenterX) * (cx - screenCenterX) +
                    (cy - screenCenterY) * (cy - screenCenterY));

            // 检测框大小（判断远近）
            float boxArea = det.box.width() * det.box.height();

            // 综合评分
            float score = 0;

            // 距离权重（越近越好）
            score += distToCenter * 0.5f;

            // 置信度权重
            score += (1 - det.score) * 200;

            // 头部优先（小目标可能是头部）
            if (currentWeapon == WeaponType.SNIPER || currentWeapon == WeaponType.RIFLE) {
                // 步枪/狙击：偏好较小目标（头部）
                if (boxArea < 5000) {
                    score -= 50;  // 奖励小目标
                }
            }

            // 位置偏好（准心附近的优先）
            if (distToCenter < 100) {
                score -= 30;
            }

            if (score < bestScore) {
                bestScore = score;
                best = det;
            }
        }

        return best;
    }

    /**
     * 计算瞄准 Y 坐标（根据瞄准部位和距离）
     */
    private float calculateAimY(MNNInference.Detection target) {
        float boxTop = target.box.top;
        float boxBottom = target.box.bottom;
        float boxHeight = boxBottom - boxTop;

        float offsetY;
        int aimPart = config.aimPart;

        // 自动模式：根据距离选择
        if (aimPart == 0) {
            float boxArea = target.box.width() * box.height();
            if (boxArea > 15000) {
                // 近距离 → 头部
                aimPart = 1;
            } else if (boxArea > 5000) {
                // 中距离 → 胸部
                aimPart = 2;
            } else {
                // 远距离 → 腹部（更容易命中）
                aimPart = 3;
            }

            // 狙击模式始终瞄头
            if (currentWeapon == WeaponType.SNIPER) {
                aimPart = 1;
            }
        }

        switch (aimPart) {
            case 1:  // 头部
                offsetY = config.headOffsetY;
                break;
            case 2:  // 胸部
                offsetY = config.chestOffsetY;
                break;
            case 3:  // 腹部
                offsetY = config.bodyOffsetY;
                break;
            default:
                offsetY = config.chestOffsetY;
        }

        return boxTop + boxHeight * offsetY;
    }

    /**
     * 是否应该开火
     */
    private boolean shouldFire(float distance, MNNInference.Detection target) {
        // 距离太远不开火
        if (distance > 300) return false;

        // 置信度太低不开火
        if (target.score < config.confThreshold + 0.1f) return false;

        // 狙击模式：必须非常准才开火
        if (currentWeapon == WeaponType.SNIPER && distance > 50) return false;

        // 霰弹模式：近距离才开火
        if (currentWeapon == WeaponType.SHOTGUN && distance > 150) return false;

        return true;
    }

    /**
     * 压枪补偿 Y（垂直后坐力）
     */
    private float getRecoilCompY() {
        float[] recoilTable;
        switch (currentWeapon) {
            case RIFLE:
                recoilTable = ValorantConfig.VANDAL_RECOIL_Y;
                break;
            case SMG:
                recoilTable = ValorantConfig.PHANTOM_RECOIL_Y;  // 类似
                break;
            default:
                return 0;
        }

        if (bulletCount >= recoilTable.length) {
            // 超过压枪表范围，使用最后值
            return recoilTable[recoilTable.length - 1] * 0.5f;
        }

        // 压枪表是累计后坐力，直接取值
        return recoilTable[bulletCount] * config.gameSensitivity * 0.1f;
    }

    /**
     * 压枪补偿 X（水平后坐力）
     */
    private float getRecoilCompX() {
        float[] recoilTable;
        switch (currentWeapon) {
            case RIFLE:
                recoilTable = ValorantConfig.VANDAL_RECOIL_X;
                break;
            default:
                return 0;
        }

        if (bulletCount >= recoilTable.length) return 0;

        return recoilTable[bulletCount] * config.gameSensitivity * 0.05f;
    }

    /**
     * 根据武器更新 PID 参数
     */
    private void updatePIDForWeapon() {
        switch (currentWeapon) {
            case RIFLE:
                aimController.setPID(config.rifle_Kp, config.rifle_Ki, config.rifle_Kd);
                break;
            case SMG:
                aimController.setPID(config.smg_Kp, config.smg_Ki, config.smg_Kd);
                break;
            case SNIPER:
                aimController.setPID(config.sniper_Kp, config.sniper_Ki, config.sniper_Kd);
                break;
            case PISTOL:
                aimController.setPID(config.pistol_Kp, config.pistol_Ki, config.pistol_Kd);
                break;
            default:
                aimController.setPID(config.rifle_Kp, config.rifle_Ki, config.rifle_Kd);
        }
    }

    /**
     * 标记正在开火（由外部调用）
     */
    public void onFire() {
        lastFireTime = System.currentTimeMillis();
    }

    /**
     * 标记停止开火
     */
    public void onFireStop() {
        resetRecoil();
    }

    private boolean isFiring() {
        return System.currentTimeMillis() - lastFireTime < 100;  // 100ms 内算正在开火
    }

    private void resetRecoil() {
        bulletCount = 0;
        totalRecoilY = 0;
        totalRecoilX = 0;
    }

    public void reset() {
        resetRecoil();
        aimController.reset();
    }

    // ============================================================
    // 结果类
    // ============================================================

    public static class ValorantAimResult {
        public final float dx;
        public final float dy;
        public final float distance;
        public final MNNInference.Detection target;
        public final boolean shouldFire;
        public final float recoilCompX;
        public final float recoilCompY;
        public final int bulletCount;

        public ValorantAimResult(float dx, float dy, float distance,
                                  MNNInference.Detection target, boolean shouldFire,
                                  float recoilCompX, float recoilCompY, int bulletCount) {
            this.dx = dx;
            this.dy = dy;
            this.distance = distance;
            this.target = target;
            this.shouldFire = shouldFire;
            this.recoilCompX = recoilCompX;
            this.recoilCompY = recoilCompY;
            this.bulletCount = bulletCount;
        }
    }
}
