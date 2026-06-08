package com.autoaim.valorant;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 无畏契约手游 专属配置
 * 
 * 游戏特点：
 * - 5v5 战术射击，爆头伤害极高（步枪一枪头秒杀）
 * - 所有英雄 hitbox 统一大小
 * - 支持 144Hz/165Hz 高帧率
 * - 动态分辨率渲染
 * - 美漫画风，角色轮廓清晰
 */
public class ValorantConfig {

    private static final String PREFS_NAME = "valorant_aim_config";

    // ============================================================
    // 游戏参数（基于无畏契约手游特性）
    // ============================================================

    /** 游戏内灵敏度（需要和玩家设置匹配） */
    public float gameSensitivity = 1.0f;

    /** 开镜灵敏度倍率 */
    public float adsMultiplier = 0.7f;

    /** 游戏 FOV（无畏契约手游约 103°） */
    public float gameFOV = 103.0f;

    /** 屏幕分辨率（根据设备自动检测） */
    public int screenWidth = 2400;
    public int screenHeight = 1080;

    // ============================================================
    // 检测参数（针对无畏契约优化）
    // ============================================================

    /** 检测置信度阈值 - 无畏契约角色轮廓清晰，可以设较高 */
    public float confThreshold = 0.50f;

    /** NMS 阈值 */
    public float nmsThreshold = 0.45f;

    /** 检测输入尺寸 */
    public int inputSize = 192;

    /** 检测频率（每N帧检测一次，165Hz下不需要每帧检测） */
    public int detectEveryNFrames = 2;

    // ============================================================
    // 瞄准参数（无畏契约专属）
    // ============================================================

    /** 瞄准部位：0=自动（优先头）, 1=头部, 2=胸部, 3=腹部 */
    public int aimPart = 0;

    /** 头部优先距离阈值（像素）- 近距离优先瞄头 */
    public float headshotDistanceThreshold = 200.0f;

    /** 爆头偏移 Y（从检测框顶部向下偏移的比例，0.05 = 头部位置） */
    public float headOffsetY = 0.08f;

    /** 胸部偏移 Y */
    public float chestOffsetY = 0.25f;

    /** 腹部偏移 Y */
    public float bodyOffsetY = 0.40f;

    // ============================================================
    // PID 参数（针对无畏契约调优）
    // ============================================================

    /**
     * 无畏契约特点：
     - 步枪（Vandal/Phantom）需要精准点射，PID 要稳
     - 冲锋枪（Spectre/Stinger）需要跟枪，PID 要快
     - 狙击（Operator）需要一击必杀，PID 要准
     */

    // 步枪模式（Vandal/Phantom）- 稳定优先
    public float rifle_Kp = 0.55f;
    public float rifle_Ki = 0.015f;
    public float rifle_Kd = 0.35f;

    // 冲锋枪模式 - 速度优先
    public float smg_Kp = 0.70f;
    public float smg_Ki = 0.025f;
    public float smg_Kd = 0.25f;

    // 狙击模式 - 精准优先
    public float sniper_Kp = 0.40f;
    public float sniper_Ki = 0.010f;
    public float sniper_Kd = 0.45f;

    // 手枪模式
    public float pistol_Kp = 0.60f;
    public float pistol_Ki = 0.020f;
    public float pistol_Kd = 0.30f;

    // ============================================================
    // 压枪参数（无畏契约主要武器）
    // ============================================================

    /**
     * 压枪补偿表 - 每发子弹的垂直偏移（像素/帧）
     * 无畏契约的弹道是固定的，可以预编程
     */

    // Vandal 压枪模式（前15发）
    public static final float[] VANDAL_RECOIL_Y = {
        0, -3, -5, -7, -9, -10, -11, -11, -10, -9,
        -8, -7, -6, -5, -4
    };
    public static final float[] VANDAL_RECOIL_X = {
        0, 0, -1, -1, 2, 2, -2, -2, 3, -3,
        2, -2, 1, -1, 0
    };

    // Phantom 压枪模式
    public static final float[] PHANTOM_RECOIL_Y = {
        0, -2, -4, -6, -8, -9, -10, -10, -9, -8,
        -7, -6, -5, -4, -3
    };
    public static final float[] PHANTOM_RECOIL_X = {
        0, 0, -1, 1, -1, 2, -2, 2, -2, 3,
        -2, 2, -1, 1, 0
    };

    // ============================================================
    // 触控参数（拟人化）
    // ============================================================

    /** 贝塞尔曲线分段数 */
    public int bezierSegments = 6;

    /** 移动抖动范围（像素） */
    public float jitterRange = 2.5f;

    /** 最小移动延迟（ms） */
    public int minMoveDelay = 5;

    /** 最大移动延迟（ms） */
    public int maxMoveDelay = 12;

    /** 开火延迟范围（ms）- 模拟人类反应时间 */
    public int minFireDelay = 50;
    public int maxFireDelay = 120;

    // ============================================================
    // 英雄检测（可选，用于识别特定英雄）
    // ============================================================

    /** 是否启用英雄识别 */
    public boolean enableAgentDetection = false;

    /** 无畏契约英雄列表 */
    public static final String[] AGENTS = {
        // 决斗位
        "Jett",      // 杰特
        "Reyna",     // 蕾娜
        "Phoenix",   // 不死鸟
        "Raze",      // 雷兹
        "Yoru",      // 幽影
        "Neon",      // 霓虹
        "Iso",       // 伊索
        // 先锋位
        "Breach",    // 铁臂
        "Sova",      // 苏破
        "Skye",      // 斯凯
        "KAY/O",     // K/O
        "Fade",      // 幽冥
        "Gekko",     // 盖可
        // 控场位
        "Brimstone", // 铸星者
        "Omen",      // 梦魇
        "Viper",     // 蝰蛇
        "Astra",     // 星界
        "Harbor",    // 海港
        "Clove",     // 克洛芙
        // 哨兵位
        "Sage",      // 贤者
        "Cypher",    // 零
        "Killjoy",   // 奇乐
        "Chamber",   // 钢锁
        "Deadlock",  // 暗锁
        "Vyse",      // 维斯
    };

    // ============================================================
    // 地图配置
    // ============================================================

    /** 地图名称 */
    public static final String[] MAPS = {
        "Bind", "Haven", "Split", "Ascent", "Icebox",
        "Breeze", "Fracture", "Pearl", "Lotus", "Sunset",
        "Abyss", "Corrode"
    };

    /** 当前地图（影响预瞄点位） */
    public String currentMap = "";

    // ============================================================
    // 热管理阈值（天玑9300+）
    // ============================================================

    /** 正常温度阈值 */
    public float tempCool = 38.0f;

    /** 温热阈值 */
    public float tempWarm = 42.0f;

    /** 过热阈值 */
    public float tempHot = 46.0f;

    // ============================================================
    // 持久化
    // ============================================================

    public void save(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        editor.putFloat("conf_threshold", confThreshold);
        editor.putFloat("nms_threshold", nmsThreshold);
        editor.putInt("input_size", inputSize);
        editor.putInt("aim_part", aimPart);
        editor.putFloat("head_offset_y", headOffsetY);

        editor.putFloat("rifle_kp", rifle_Kp);
        editor.putFloat("rifle_ki", rifle_Ki);
        editor.putFloat("rifle_kd", rifle_Kd);
        editor.putFloat("smg_kp", smg_Kp);
        editor.putFloat("smg_ki", smg_Ki);
        editor.putFloat("smg_kd", smg_Kd);
        editor.putFloat("sniper_kp", sniper_Kp);
        editor.putFloat("sniper_ki", sniper_Ki);
        editor.putFloat("sniper_kd", sniper_Kd);

        editor.putFloat("jitter_range", jitterRange);
        editor.putInt("bezier_segments", bezierSegments);
        editor.putInt("min_fire_delay", minFireDelay);
        editor.putInt("max_fire_delay", maxFireDelay);

        editor.putString("current_map", currentMap);
        editor.apply();
    }

    public void load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        confThreshold = prefs.getFloat("conf_threshold", confThreshold);
        nmsThreshold = prefs.getFloat("nms_threshold", nmsThreshold);
        inputSize = prefs.getInt("input_size", inputSize);
        aimPart = prefs.getInt("aim_part", aimPart);
        headOffsetY = prefs.getFloat("head_offset_y", headOffsetY);

        rifle_Kp = prefs.getFloat("rifle_kp", rifle_Kp);
        rifle_Ki = prefs.getFloat("rifle_ki", rifle_Ki);
        rifle_Kd = prefs.getFloat("rifle_kd", rifle_Kd);
        smg_Kp = prefs.getFloat("smg_kp", smg_Kp);
        smg_Ki = prefs.getFloat("smg_ki", smg_Ki);
        smg_Kd = prefs.getFloat("smg_kd", smg_Kd);
        sniper_Kp = prefs.getFloat("sniper_kp", sniper_Kp);
        sniper_Ki = prefs.getFloat("sniper_ki", sniper_Ki);
        sniper_Kd = prefs.getFloat("sniper_kd", sniper_Kd);

        jitterRange = prefs.getFloat("jitter_range", jitterRange);
        bezierSegments = prefs.getInt("bezier_segments", bezierSegments);
        minFireDelay = prefs.getInt("min_fire_delay", minFireDelay);
        maxFireDelay = prefs.getInt("max_fire_delay", maxFireDelay);

        currentMap = prefs.getString("current_map", currentMap);
    }
}
