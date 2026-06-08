package com.autoaim.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 悬浮控制面板 - 游戏中操作
 * 
 * 功能：
 * - 自瞄开关 + PID 调节
 * - 自动扳机开关
 * - 模型切换
 * - 置信度调节
 * - 实时 FPS / 温度显示
 */
public class FloatingControlPanel {

    private final Context context;
    private WindowManager windowManager;
    private WindowManager.LayoutParams layoutParams;

    // 悬浮球
    private View floatingBall;
    // 控制面板
    private View controlPanel;
    private boolean isPanelVisible = false;

    // 面板控件
    private Switch switchAim;
    private Switch switchAutoFire;
    private SeekBar seekConf;
    private SeekBar seekKp;
    private SeekBar seekKd;
    private TextView tvFps;
    private TextView tvTemp;
    private TextView tvConfValue;

    // 回调
    public interface ConfigCallback {
        void onAimToggle(boolean enabled);
        void onAutoFireToggle(boolean enabled);
        void onConfidenceChanged(float value);
        void onPIDChanged(float kp, float kd);
        void onStop();
    }

    private ConfigCallback callback;

    public FloatingControlPanel(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    /**
     * 显示悬浮球
     */
    public void show(ConfigCallback callback) {
        this.callback = callback;
        createFloatingBall();
        createControlPanel();
    }

    private void createFloatingBall() {
        floatingBall = new ImageButton(context);
        ((ImageButton) floatingBall).setImageResource(android.R.drawable.ic_menu_compass);
        floatingBall.setAlpha(0.8f);
        floatingBall.setBackgroundColor(0x88000000);

        layoutParams = new WindowManager.LayoutParams(
                120, 120,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = 50;
        layoutParams.y = 500;

        // 拖拽 + 点击
        floatingBall.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isDragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = layoutParams.x;
                        initialY = layoutParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true;
                        }
                        layoutParams.x = initialX + (int) dx;
                        layoutParams.y = initialY + (int) dy;
                        windowManager.updateViewLayout(floatingBall, layoutParams);
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!isDragging) {
                            togglePanel();
                        }
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(floatingBall, layoutParams);
    }

    private void createControlPanel() {
        // 用代码构建布局（避免依赖 XML）
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xDD1A1A2E);
        panel.setPadding(24, 16, 24, 16);

        // 标题栏
        LinearLayout titleBar = createHorizontalLayout();
        TextView title = createText("⚙ AutoAim", 16, 0xFFFFFFFF);
        ImageButton btnClose = new ImageButton(context);
        btnClose.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        btnClose.setBackgroundColor(0x00000000);
        btnClose.setOnClickListener(v -> togglePanel());
        titleBar.addView(title, layoutParams(0, 1.0f));
        titleBar.addView(btnClose, layoutParams(80, 0));
        panel.addView(titleBar);

        // 自瞄开关
        switchAim = new Switch(context);
        switchAim.setText("自瞄");
        switchAim.setChecked(true);
        switchAim.setTextColor(0xFFFFFFFF);
        switchAim.setOnCheckedChangeListener((v, checked) -> {
            if (callback != null) callback.onAimToggle(checked);
        });
        panel.addView(switchAim);

        // 自动扳机
        switchAutoFire = new Switch(context);
        switchAutoFire.setText("自动扳机");
        switchAutoFire.setTextColor(0xFFFFFFFF);
        switchAutoFire.setOnCheckedChangeListener((v, checked) -> {
            if (callback != null) callback.onAutoFireToggle(checked);
        });
        panel.addView(switchAutoFire);

        // 置信度
        panel.addView(createText("置信度阈值", 14, 0xFFAAAAAA));
        LinearLayout confRow = createHorizontalLayout();
        seekConf = new SeekBar(context);
        seekConf.setMax(90);
        seekConf.setProgress(45);
        tvConfValue = createText("0.45", 12, 0xFF00FF00);
        seekConf.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float val = progress / 100.0f;
                tvConfValue.setText(String.format("%.2f", val));
                if (callback != null) callback.onConfidenceChanged(val);
            }
        });
        confRow.addView(seekConf, layoutParams(0, 1.0f));
        confRow.addView(tvConfValue, 80);
        panel.addView(confRow);

        // PID - Kp
        panel.addView(createText("响应速度 (Kp)", 14, 0xFFAAAAAA));
        seekKp = new SeekBar(context);
        seekKp.setMax(100);
        seekKp.setProgress(60);
        seekKp.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (callback != null) callback.onPIDChanged(progress / 100.0f, seekKd.getProgress() / 100.0f);
            }
        });
        panel.addView(seekKp);

        // PID - Kd
        panel.addView(createText("平滑度 (Kd)", 14, 0xFFAAAAAA));
        seekKd = new SeekBar(context);
        seekKd.setMax(100);
        seekKd.setProgress(30);
        seekKd.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (callback != null) callback.onPIDChanged(seekKp.getProgress() / 100.0f, progress / 100.0f);
            }
        });
        panel.addView(seekKd);

        // 状态信息
        panel.addView(createText("状态", 14, 0xFFAAAAAA));
        LinearLayout statusRow = createHorizontalLayout();
        tvFps = createText("FPS: --", 12, 0xFF00FF00);
        tvTemp = createText("Temp: --°C", 12, 0xFFFF6600);
        statusRow.addView(tvFps, layoutParams(0, 1.0f));
        statusRow.addView(tvTemp, layoutParams(0, 1.0f));
        panel.addView(statusRow);

        // 停止按钮
        android.widget.Button btnStop = new android.widget.Button(context);
        btnStop.setText("停止");
        btnStop.setBackgroundColor(0xFFFF4444);
        btnStop.setTextColor(0xFFFFFFFF);
        btnStop.setOnClickListener(v -> {
            if (callback != null) callback.onStop();
        });
        panel.addView(btnStop);

        controlPanel = panel;

        WindowManager.LayoutParams panelParams = new WindowManager.LayoutParams(
                700, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        panelParams.gravity = Gravity.CENTER;

        // 初始不显示
        controlPanel.setVisibility(View.GONE);
        windowManager.addView(controlPanel, panelParams);
    }

    private void togglePanel() {
        isPanelVisible = !isPanelVisible;
        controlPanel.setVisibility(isPanelVisible ? View.VISIBLE : View.GONE);
    }

    /**
     * 更新状态显示
     */
    public void updateStatus(float fps, float temp) {
        if (tvFps != null) {
            tvFps.setText(String.format("FPS: %.1f", fps));
        }
        if (tvTemp != null) {
            tvTemp.setText(String.format("%.0f°C", temp));
            tvTemp.setTextColor(temp > 46 ? 0xFFFF0000 : temp > 42 ? 0xFFFF6600 : 0xFF00FF00);
        }
    }

    /**
     * 隐藏所有
     */
    public void dismiss() {
        try {
            if (floatingBall != null) windowManager.removeView(floatingBall);
            if (controlPanel != null) windowManager.removeView(controlPanel);
        } catch (Exception ignored) {}
    }

    // ============================================================
    // UI 工具方法
    // ============================================================

    private TextView createText(String text, float sp, int color) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setPadding(0, 8, 0, 8);
        return tv;
    }

    private LinearLayout createHorizontalLayout() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams layoutParams(int width, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                width == 0 ? 0 : width, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.weight = weight;
        return lp;
    }

    private LinearLayout.LayoutParams layoutParams(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    /**
     * SeekBar 简化监听器
     */
    private static class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {}
    }
}
