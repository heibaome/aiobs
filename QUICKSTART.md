# 快速开始 - 无畏契约手游 AI 辅助

## 环境要求

| 项目 | 要求 |
|------|------|
| **手机** | 天玑9300+ (或骁龙8 Gen 1+) |
| **系统** | Android 12+ |
| **电脑** | Windows/Mac/Linux |
| **JDK** | 11+ |
| **Android SDK** | API 34 |

## 一键构建 (推荐)

```bash
# 1. 克隆项目
cd auto-aim-dimensity

# 2. 一键构建
chmod +x setup.sh
./setup.sh

# 3. 安装到手机
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 手动构建

### Step 1: 安装依赖

```bash
# Python 依赖（模型转换用）
pip install ultralytics MNN onnx onnxsim

# 下载 YOLOv8n 权重
wget https://github.com/ultralytics/assets/releases/download/v8.2.0/yolov8n.pt
```

### Step 2: 转换模型

```bash
# 转换为 MNN INT8 量化模型
python tools/convert_model.py --input yolov8n.pt --size 192 --quant int8

# 复制到 assets
mkdir -p app/src/main/assets
cp yolov8n_int8_192.mnn app/src/main/assets/
```

### Step 3: 下载 MNN SDK

```bash
# 从 GitHub 下载 MNN AAR
# https://github.com/alibaba/MNN/releases
# 解压到 app/src/main/cpp/third_party/MNN/
```

### Step 4: 构建 APK

```bash
./gradlew assembleDebug
```

### Step 5: 安装运行

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 使用方法

### 1. 安装 Shizuku

- 从应用商店或 https://shizuku.rikka.app/ 下载
- 打开 Shizuku，选择「通过无线调试启动」
- 在开发者选项中开启「无线调试」
- 按 Shizuku 引导完成配对

### 2. 授权

- 打开 Shizuku → 管理已授权应用
- 找到 AutoAim，开启授权

### 3. 启动

- 打开 AutoAim
- 选择当前武器（Vandal/Phantom/Operator 等）
- 选择瞄准部位（推荐「自动」）
- 点击「启动」
- 切换到无畏契约手游

### 4. 游戏中使用

- **自瞄触发**：按住开火键时自动瞄准
- **压枪补偿**：持续开火时自动补偿后坐力
- **目标切换**：自动选择最优目标（准心最近+高置信度）

## 训练专属模型（进阶）

### 采集数据

```bash
# 连接手机，打开游戏
python valorant/collect_dataset.py --map ascent --duration 600 --interval 1
```

### 标注数据

```bash
# 安装 LabelImg
pip install labelimg

# 标注（YOLO 格式）
labelimg val_dataset/raw/ascent_competitive_xxx/images val.yaml
```

### 训练

```bash
python valorant/train_valorant.py --data val_dataset/val.yaml --epochs 150
```

### 转换部署

```bash
python tools/convert_model.py \
    --input runs/detect/valorant_v1/weights/best.pt \
    --size 192 --quant int8
cp best_int8_192.mnn app/src/main/assets/
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 调试

```bash
# 查看日志
adb logcat -s MNN_Inference MNN_Native Pipeline ThermalManager

# 查看 FPS
adb logcat | grep "FPS:"

# 查看温度
adb logcat | grep "Thermal"
```

## 常见问题

**Q: 推理太慢？**
- 确认使用 INT8 192 模型
- 确认 NPU 后端（NNAPI）已启用
- 检查是否过热降频

**Q: 检测不到敌人？**
- 降低置信度阈值
- 检查截图权限是否正确
- 使用游戏专属数据集微调模型

**Q: 触控不生效？**
- 确认 Shizuku 已授权
- 确认 Shizuku 状态为「运行中」
- 检查设备节点路径

**Q: 温度太高？**
- 热管理会自动降频
- 降低游戏画质
- 使用手机散热器
