package com.autoaim.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.autoaim.R;
import com.autoaim.core.AutoAimService;
import com.autoaim.valorant.ValorantConfig;

/**
 * 主界面 - 启动/配置
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_MEDIA_PROJECTION = 1001;
    private static final int REQUEST_OVERLAY_PERMISSION = 1002;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 1003;

    private ValorantConfig config;
    private boolean isRunning = false;

    // UI 控件
    private Button btnStart;
    private Button btnStop;
    private TextView tvStatus;
    private TextView tvTempInfo;
    private Spinner spinnerWeapon;
    private Spinner spinnerAimPart;
    private SeekBar seekConf;
    private TextView tvConfValue;
    private Switch switchAutoFire;
    private Switch switchGyroscope;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createLayout());

        config = new ValorantConfig();
        config.load(this);

        checkPermissions();
    }

    private View createLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        root.setBackgroundColor(0xFF1A1A2E);

        // 标题
        root.addView(createText("🎮 AutoAim - 无畏契约手游", 24, 0xFFFFFFFF));
        root.addView(createText("天玑9300+ 专属优化版", 14, 0xFF888888));
        root.addView(createText("", 8, 0)); // spacer

        // 状态
        tvStatus = createText("状态：未启动", 16, 0xFF00FF00);
        root.addView(tvStatus);

        tvTempInfo = createText("温度：--°C", 14, 0xFFFF6600);
        root.addView(tvTempInfo);
        root.addView(createText("", 8, 0));

        // 武器选择
        root.addView(createText("当前武器", 16, 0xFFAAAAAA));
        spinnerWeapon = new Spinner(this);
        String[] weapons = {"Vandal (步枪)", "Phantom (步枪)", "Operator (狙击)",
                "Spectre (冲锋枪)", "Sheriff (手枪)", "Judge (霰弹枪)"};
        ArrayAdapter<String> weaponAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, weapons);
        spinnerWeapon.setAdapter(weaponAdapter);
        spinnerWeapon.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                switch (pos) {
                    case 0: case 1: config.currentWeapon = ValorantConfig.WeaponType.RIFLE; break;
                    case 2: config.currentWeapon = ValorantConfig.WeaponType.SNIPER; break;
                    case 3: config.currentWeapon = ValorantConfig.WeaponType.SMG; break;
                    case 4: config.currentWeapon = ValorantConfig.WeaponType.PISTOL; break;
                    case 5: config.currentWeapon = ValorantConfig.WeaponType.SHOTGUN; break;
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(spinnerWeapon);

        // 瞄准部位
        root.addView(createText("瞄准部位", 16, 0xFFAAAAAA));
        spinnerAimPart = new Spinner(this);
        String[] aimParts = {"自动（近头远身）", "头部", "胸部", "腹部"};
        ArrayAdapter<String> aimAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, aimParts);
        spinnerAimPart.setAdapter(aimAdapter);
        spinnerAimPart.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                config.aimPart = pos;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(spinnerAimPart);

        // 置信度
        root.addView(createText("置信度阈值", 16, 0xFFAAAAAA));
        LinearLayout confRow = new LinearLayout(this);
        confRow.setOrientation(LinearLayout.HORIZONTAL);
        seekConf = new SeekBar(this);
        seekConf.setMax(80);
        seekConf.setProgress((int)(config.confThreshold * 100));
        tvConfValue = createText(String.format("%.2f", config.confThreshold), 14, 0xFF00FF00);
        seekConf.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                config.confThreshold = progress / 100.0f;
                tvConfValue.setText(String.format("%.2f", config.confThreshold));
            }
        });
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        confRow.addView(seekConf, seekLp);
        confRow.addView(tvConfValue, new LinearLayout.LayoutParams(120, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(confRow);

        // 开关
        switchAutoFire = new Switch(this);
        switchAutoFire.setText("自动扳机");
        switchAutoFire.setTextColor(0xFFFFFFFF);
        root.addView(switchAutoFire);

        switchGyroscope = new Switch(this);
        switchGyroscope.setText("陀螺仪辅助");
        switchGyroscope.setTextColor(0xFFFFFFFF);
        root.addView(switchGyroscope);

        root.addView(createText("", 16, 0));

        // 按钮
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        btnStart = new Button(this);
        btnStart.setText("▶ 启动");
        btnStart.setBackgroundColor(0xFF4CAF50);
        btnStart.setOnClickListener(v -> startAutoAim());
        btnRow.addView(btnStart, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        btnStop = new Button(this);
        btnStop.setText("■ 停止");
        btnStop.setBackgroundColor(0xFFFF4444);
        btnStop.setEnabled(false);
        btnStop.setOnClickListener(v -> stopAutoAim());
        btnRow.addView(btnStop, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        root.addView(btnRow);

        root.addView(createText("", 16, 0));

        // 说明
        root.addView(createText("使用说明：", 14, 0xFFAAAAAA));
        root.addView(createText("1. 安装 Shizuku 并通过无线调试启动", 12, 0xFF888888));
        root.addView(createText("2. 授权本应用的 Shizuku 权限", 12, 0xFF888888));
        root.addView(createText("3. 选择武器和瞄准设置", 12, 0xFF888888));
        root.addView(createText("4. 点击启动，切换到游戏", 12, 0xFF888888));
        root.addView(createText("5. 游戏中按住开火键触发自瞄", 12, 0xFF888888));

        return root;
    }

    private TextView createText(String text, float sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setPadding(0, 8, 0, 8);
        return tv;
    }

    // ============================================================
    // 权限检查
    // ============================================================

    private void checkPermissions() {
        // 悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
        }

        // 通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATION_PERMISSION);
            }
        }

        // Shizuku 检查
        checkShizuku();
    }

    private void checkShizuku() {
        try {
            Class.forName("rikka.shizuku.Shizuku");
            // Shizuku 可用
            Log.i(TAG, "Shizuku available");
        } catch (ClassNotFoundException e) {
            Toast.makeText(this, "请安装 Shizuku", Toast.LENGTH_LONG).show();
        }
    }

    // ============================================================
    // 启动/停止
    // ============================================================

    private void startAutoAim() {
        if (isRunning) return;

        // 保存配置
        config.save(this);

        // 请求屏幕截图权限
        MediaProjectionManager mpm = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);

        tvStatus.setText("状态：请求权限中...");
    }

    private void stopAutoAim() {
        Intent intent = new Intent(this, AutoAimService.class);
        intent.putExtra("action", "stop");
        startService(intent);

        isRunning = false;
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        tvStatus.setText("状态：已停止");
    }

    private void launchService(int resultCode, Intent data) {
        Intent intent = new Intent(this, AutoAimService.class);
        intent.putExtra("action", "start");
        intent.putExtra("resultCode", resultCode);
        intent.putExtra("data", data);

        // 传递配置
        intent.putExtra("aim_enabled", true);
        intent.putExtra("auto_fire", switchAutoFire.isChecked());
        intent.putExtra("conf_threshold", config.confThreshold);
        intent.putExtra("input_size", config.inputSize);

        startForegroundService(intent);

        isRunning = true;
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        tvStatus.setText("状态：运行中 ✓");

        Toast.makeText(this, "AutoAim 已启动，切换到游戏即可", Toast.LENGTH_LONG).show();
    }

    // ============================================================
    // Activity Result
    // ============================================================

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                launchService(resultCode, data);
            } else {
                tvStatus.setText("状态：权限被拒绝");
                Toast.makeText(this, "需要屏幕截图权限才能运行", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
