package com.autoaim.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

/**
 * 主服务 - 串联所有模块
 * 
 * 生命周期：
 * start → init(MNN + ScreenCapture + TouchInput) → start pipeline → stop → release
 * 
 * 适配天玑9300+ 的关键改动：
 * 1. 推理框架：MNN（替代 QNN）
 * 2. NPU 后端：NNAPI → APU 790（替代 Hexagon HTP）
 * 3. 热管理：动态调节推理频率
 * 4. 流水线：截图/推理/触控并行
 */
public class AutoAimService extends Service {

    private static final String TAG = "AutoAimService";
    private static final String CHANNEL_ID = "auto_aim_channel";
    private static final int NOTIFICATION_ID = 1;

    // 各模块
    private MNNInference inference;
    private ScreenCapture screenCapture;
    private AimController aimController;
    private TouchInput touchInput;
    private ThermalManager thermalManager;
    private PipelineController pipeline;

    // 状态
    private boolean isInitialized = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("AutoAim 就绪"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getStringExtra("action");
        if ("start".equals(action)) {
            int resultCode = intent.getIntExtra("resultCode", -1);
            Intent data = intent.getParcelableExtra("data");
            if (resultCode != -1 && data != null) {
                initialize(resultCode, data);
            }
        } else if ("stop".equals(action)) {
            shutdown();
        } else if ("config".equals(action)) {
            updateConfig(intent);
        }

        return START_STICKY;
    }

    /**
     * 初始化所有模块
     */
    private void initialize(int resultCode, Intent projectionData) {
        if (isInitialized) {
            Log.w(TAG, "Already initialized");
            return;
        }

        try {
            // 1. 热管理器
            thermalManager = new ThermalManager();
            thermalManager.init(this);

            // 2. MNN 推理引擎
            // 默认用 NNAPI（天玑9300+ APU 790），失败回退 CPU
            int forwardType = MNNInference.FORWARD_NNAPI;
            String modelName = "yolov8n_int8_192.mnn";  // 默认 INT8 192

            // 根据热状态选择模型
            if (thermalManager.getCurrentLevel() == ThermalManager.ThermalLevel.COOL) {
                modelName = "yolov8n_int8_256.mnn";  // 凉快时用高精度
            }

            inference = new MNNInference(this, modelName, forwardType,
                    thermalManager.getRecommendedThreads());
            Log.i(TAG, "MNN engine initialized: " + modelName);

            // 3. 屏幕截图
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);

            MediaProjectionManager mpm = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            MediaProjection projection = mpm.getMediaProjection(resultCode, projectionData);

            screenCapture = new ScreenCapture();
            // 全屏截图，50% 缩放（天玑9300+ 可以跑更高）
            float scale = thermalManager.getCurrentLevel() == ThermalManager.ThermalLevel.COOL
                    ? 0.75f : 0.5f;
            screenCapture.init(projection, metrics.widthPixels, metrics.heightPixels,
                    0, 0, metrics.widthPixels, metrics.heightPixels, scale);

            // 4. 瞄准控制器
            aimController = new AimController();
            aimController.init(metrics.widthPixels, metrics.heightPixels);

            // 5. 触控输入
            touchInput = new TouchInput();
            touchInput.init();  // 或 initWithShizuku("/dev/input/event2")

            // 6. 流水线控制器
            pipeline = new PipelineController(
                    screenCapture, inference, aimController, touchInput, thermalManager);

            isInitialized = true;
            updateNotification("AutoAim 运行中");
            Log.i(TAG, "All modules initialized");

        } catch (Exception e) {
            Log.e(TAG, "Initialization failed", e);
            shutdown();
        }
    }

    /**
     * 启动流水线
     */
    private void startPipeline() {
        if (!isInitialized || pipeline == null) return;
        pipeline.start();
        updateNotification("AutoAim 检测中...");
        Log.i(TAG, "Pipeline started");
    }

    /**
     * 更新配置（运行时热更新）
     */
    private void updateConfig(Intent intent) {
        if (pipeline == null) return;

        // 自瞄开关
        boolean aimEnabled = intent.getBooleanExtra("aim_enabled", true);
        // 自动扳机
        boolean autoFire = intent.getBooleanExtra("auto_fire", false);
        // 置信度
        float confTh = intent.getFloatExtra("conf_threshold", 0.45f);
        // PID 参数
        float kp = intent.getFloatExtra("kp", 0.6f);
        float ki = intent.getFloatExtra("ki", 0.02f);
        float kd = intent.getFloatExtra("kd", 0.3f);

        pipeline.setConfThreshold(confTh);
        pipeline.setAutoFire(autoFire);
        aimController.setPID(kp, ki, kd);

        Log.i(TAG, String.format("Config updated: aim=%b, fire=%b, conf=%.2f",
                aimEnabled, autoFire, confTh));
    }

    /**
     * 关闭
     */
    private void shutdown() {
        Log.i(TAG, "Shutting down...");

        if (pipeline != null) {
            pipeline.stop();
            pipeline = null;
        }
        if (screenCapture != null) {
            screenCapture.release();
            screenCapture = null;
        }
        if (inference != null) {
            inference.release();
            inference = null;
        }
        if (touchInput != null) {
            touchInput.release();
            touchInput = null;
        }
        if (thermalManager != null) {
            thermalManager.release();
            thermalManager = null;
        }

        isInitialized = false;
        stopForeground(true);
        stopSelf();
    }

    // ============================================================
    // Notification
    // ============================================================

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "AutoAim Service", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("AutoAim 后台运行");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("AutoAim")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }
}
