package com.autoaim.core;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * 热管理器 - 防止天玑9300+ 过热降频
 * 
 * 天玑9300+ 全大核（4×X4 + 4×A720），长时间满载会：
 * 1. 表面温度升高 → 用户体验差
 * 2. 触发温控 → CPU/GPU/APU 降频 → 推理变慢
 * 3. 严重时强制关闭前台应用
 * 
 * 策略：动态调节推理频率和模型精度
 */
public class ThermalManager {

    private static final String TAG = "ThermalManager";

    // 温度阈值（摄氏度）
    private static final float TEMP_COOL = 38.0f;      // 正常，全力运行
    private static final float TEMP_WARM = 42.0f;      // 温热，适度降频
    private static final float TEMP_HOT = 46.0f;       // 热，大幅降频
    private static final float TEMP_CRITICAL = 50.0f;   // 过热，最低性能

    // 温度读取路径（天玑9300+）
    private static final String[] THERMAL_PATHS = {
            "/sys/class/thermal/thermal_zone0/temp",    // CPU
            "/sys/class/thermal/thermal_zone1/temp",    // GPU
            "/sys/class/thermal/thermal_zone2/temp",    // APU
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
    };

    private PowerManager powerManager;
    private String thermalPath = null;

    // 当前状态
    private ThermalLevel currentLevel = ThermalLevel.COOL;
    private float currentTemp = 0;
    private long lastCheckTime = 0;
    private static final long CHECK_INTERVAL = 3000;  // 3秒检查一次

    public enum ThermalLevel {
        COOL,       // 正常：每帧检测，全精度
        WARM,       // 温热：每2帧检测
        HOT,        // 热：每3帧检测，降分辨率
        CRITICAL    // 过热：每4帧检测，最低精度
    }

    public void init(Context context) {
        powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

        // 找到可用的温度节点
        for (String path : THERMAL_PATHS) {
            if (new File(path).canRead()) {
                thermalPath = path;
                Log.i(TAG, "Thermal sensor: " + path);
                break;
            }
        }

        if (thermalPath == null) {
            Log.w(TAG, "No thermal sensor found, using default level");
        }
    }

    /**
     * 获取当前温度
     */
    public float getTemperature() {
        if (thermalPath == null) return 40.0f;  // 默认值

        try (RandomAccessFile reader = new RandomAccessFile(thermalPath, "r")) {
            String line = reader.readLine();
            if (line != null) {
                float temp = Float.parseFloat(line.trim());
                // 有些设备返回毫度（如 42000），有些返回度（如 42）
                if (temp > 1000) temp /= 1000.0f;
                return temp;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read temperature", e);
        }
        return 40.0f;
    }

    /**
     * 更新热状态（定期调用）
     */
    public ThermalLevel update() {
        long now = System.currentTimeMillis();
        if (now - lastCheckTime < CHECK_INTERVAL) return currentLevel;
        lastCheckTime = now;

        currentTemp = getTemperature();

        ThermalLevel newLevel;
        if (currentTemp < TEMP_COOL) {
            newLevel = ThermalLevel.COOL;
        } else if (currentTemp < TEMP_WARM) {
            newLevel = ThermalLevel.WARM;
        } else if (currentTemp < TEMP_HOT) {
            newLevel = ThermalLevel.HOT;
        } else {
            newLevel = ThermalLevel.CRITICAL;
        }

        if (newLevel != currentLevel) {
            Log.w(TAG, String.format("Thermal level changed: %s → %s (%.1f°C)",
                    currentLevel, newLevel, currentTemp));
            currentLevel = newLevel;
        }

        return currentLevel;
    }

    /**
     * 获取推荐的跳帧数
     * 1 = 每帧都检测
     * 2 = 每2帧检测1次
     * 3 = 每3帧检测1次
     */
    public int getSkipFrames() {
        update();
        switch (currentLevel) {
            case COOL:     return 1;
            case WARM:     return 2;
            case HOT:      return 3;
            case CRITICAL: return 4;
            default:       return 1;
        }
    }

    /**
     * 获取推荐的模型输入尺寸
     */
    public int getRecommendedInputSize() {
        update();
        switch (currentLevel) {
            case COOL:     return 256;  // 高精度
            case WARM:     return 192;  // 标准
            case HOT:      return 192;  // 标准
            case CRITICAL: return 128;  // 最低
            default:       return 192;
        }
    }

    /**
     * 获取推荐的推理线程数
     */
    public int getRecommendedThreads() {
        update();
        switch (currentLevel) {
            case COOL:     return 4;   // 全大核
            case WARM:     return 3;
            case HOT:      return 2;
            case CRITICAL: return 2;
            default:       return 4;
        }
    }

    public ThermalLevel getCurrentLevel() { return currentLevel; }
    public float getCurrentTemp() { return currentTemp; }

    /**
     * 释放资源
     */
    public void release() {
        // nothing to release
    }
}
