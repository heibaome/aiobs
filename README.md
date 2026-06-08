# Auto-Aim for Dimensity 9300+

基于 [xiangsu1145/Auto-aim_android-yolo](https://github.com/xiangsu1145/Auto-aim_android-yolo) 的优化版本，专门适配联发科天玑9300+ (APU 790) 芯片。

## 与原项目的差异

| 模块 | 原项目 (骁龙) | 本项目 (天玑) |
|------|--------------|--------------|
| 推理框架 | QNN (Qualcomm) | **MNN** (阿里巴巴) |
| NPU 加速 | Hexagon HTP | **APU 790** via NNAPI |
| 流水线 | 串行 | **三级并行** (截图/推理/触控) |
| 触控模拟 | Shizuku + input | Shizuku + **贝塞尔曲线拟人化** |
| 热管理 | 无 | **动态调节** (跳帧/降精度/降分辨率) |
| 瞄准控制 | 基础 PID | PID + **目标预测** + 智能选敌 |
| 输入分辨率 | 固定 192 | **动态** 128/192/256 |

## 性能预期 (天玑9300+)

| 配置 | 推理延迟 | FPS | 精度 |
|------|---------|-----|------|
| INT8 192 (NNAPI/APU) | ~5-8ms | 30-40 | 够用 |
| INT8 256 (NNAPI/APU) | ~10-15ms | 20-28 | 较好 |
| INT8 192 (OpenCL/GPU) | ~10-15ms | 20-30 | 够用 |
| INT8 192 (CPU/NEON) | ~15-20ms | 15-25 | 够用 |

## 项目结构

```
auto-aim-dimensity/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── java/com/autoaim/
│       │   ├── core/
│       │   │   ├── MNNInference.java      # MNN 推理引擎（替换 QNN）
│       │   │   ├── ScreenCapture.java      # 屏幕截图（双缓冲）
│       │   │   ├── TouchInput.java         # 触控模拟（贝塞尔曲线）
│       │   │   ├── AimController.java      # 瞄准控制（PID + 预测）
│       │   │   ├── PipelineController.java # 流水线控制器
│       │   │   ├── ThermalManager.java     # 热管理器
│       │   │   └── AutoAimService.java     # 主服务
│       │   └── ui/                         # UI 界面
│       ├── cpp/
│       │   ├── CMakeLists.txt
│       │   └── mnn_inference.cpp           # JNI 层
│       └── assets/
│           ├── yolov8n_int8_192.mnn       # 默认模型
│           └── yolov8n_int8_256.mnn       # 高精度模型
├── tools/
│   └── convert_model.py                    # 模型转换脚本
└── README.md
```

## 快速开始

### 1. 模型转换

```bash
# 安装依赖
pip install ultralytics onnx onnxsim mnn

# 转换模型（YOLOv8n → ONNX → MNN INT8）
python tools/convert_model.py --input yolov8n.pt --size 192 --quant int8
python tools/convert_model.py --input yolov8n.pt --size 256 --quant int8

# 如果要针对特定游戏微调
yolo detect train data=your_game.yaml model=yolov8n.pt epochs=100 imgsz=192
python tools/convert_model.py --input runs/detect/train/weights/best.pt --size 192 --quant int8
```

### 2. 构建 APK

```bash
# 将模型放入 assets
cp *.mnn app/src/main/assets/

# 下载 MNN AAR (v2.9+)
# https://github.com/alibaba/MNN/releases
# 放入 app/libs/

# 构建
./gradlew assembleRelease
```

### 3. 安装使用

1. 安装 APK
2. 安装 [Shizuku](https://shizuku.rikka.app/)，通过无线调试启动
3. 授权本应用的 Shizuku 权限
4. 打开应用，授予权限，选择模型，点击启动

## 核心优化详解

### 1. 推理引擎：MNN 替代 QNN

原项目用 QNN (Qualcomm Neural Network)，只能在骁龙上跑。
MNN 是阿里巴巴开源的推理框架，对联发科芯片有专门优化。

```java
// 原项目
QNNInference inference = new QNNInference(context, "model.bin", QNN_HEXAGON);

// 本项目
MNNInference inference = new MNNInference(context, "model.mnn",
    MNNInference.FORWARD_NNAPI,  // 走 APU 790
    4);                           // 4 线程
```

**关键点：** MNN 支持 NNAPI，天玑9300+ 的 APU 790 通过 NNAPI 暴露，
不需要联发科私有 SDK，通用性更好。

### 2. 流水线并行

原项目串行执行，每帧都要等截图+推理+触控全部完成：

```
原项目: |截图|推理|触控|截图|推理|触控|  → ~35ms/帧
本项目: |截图|推理|触控|截图|推理|触控|  → 截图和推理并行
         |截图|  推理  |截图|  推理  |
              |触控|       |触控|       → ~25ms/帧
```

用 `BlockingQueue` 连接各阶段，生产者-消费者模式。

### 3. 贝塞尔曲线拟人化

原项目直接移动准心到目标，轨迹是直线，容易被反作弊检测。

```java
// 原项目：直线移动
touchInput.moveStraight(fromX, fromY, toX, toY);

// 本项目：贝塞尔曲线 + 缓动 + 抖动
touchInput.moveToAsync(fromX, fromY, toX, toY);
// 内部实现：
// 1. 生成随机贝塞尔控制点
// 2. 分 8 段发送触控事件
// 3. 每段加 6-14ms 随机延迟
// 4. ease-in-out 缓动函数
// 5. 3px 随机抖动
```

### 4. 热管理

天玑9300+ 全大核架构，持续高负载会发热降频：

```java
// 根据温度动态调节
switch (thermalManager.getCurrentLevel()) {
    case COOL:     // < 38°C: 每帧检测，256 输入
    case WARM:     // 38-42°C: 每 2 帧，192 输入
    case HOT:      // 42-46°C: 每 3 帧，192 输入
    case CRITICAL: // > 46°C: 每 4 帧，128 输入
}
```

### 5. PID + 目标预测

原项目只有基础 PID，目标快速移动时会"追不上"。

```java
// 新增：预测目标位置
float predictedX = targetX + velocityX * dt * predictionFactor;
float predictedY = targetY + velocityY * dt * predictionFactor;

// 新增：智能选敌
// 优先级：准心最近 + 高置信度 + 适中大小
```

## 进一步优化方向

- [ ] **游戏数据集微调**：针对特定游戏（和平精英/PUBG Mobile）的敌人检测
- [ ] **INT4 量化**：APU 790 支持 INT4，推理速度再翻倍
- [ ] **头部检测**：训练专门的头部检测模型，提高击杀率
- [ ] **多人追踪**：同时追踪多个目标，切换最优目标
- [ ] **压枪补偿**：结合武器类型和距离自动压枪
- [ ] **帧间复用**：相邻帧相似度高时跳过推理，用光流追踪

## 注意事项

- 本项目仅供学习和研究用途
- 在线游戏中使用可能违反游戏条款
- 建议在单机/训练模式中测试
- 首次运行需要从 assets 解压模型文件

## License

MIT License (同原项目)
