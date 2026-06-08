# 无畏契约手游 (Valorant Mobile) 专属优化方案

## 游戏特性分析

| 特性 | 详情 |
|------|------|
| **游戏模式** | 5v5 爆破模式为主 |
| **TTK** | 极低（步枪爆头一枪死） |
| **角色 hitbox** | 所有英雄统一大小 |
| **帧率支持** | 144Hz / 165Hz 高帧率 |
| **画面风格** | 美漫画风，轮廓清晰 |
| **操作方式** | 支持陀螺仪 + 多指操作 |
| **开发方** | 腾讯光子工作室群 |

## 为什么无畏契约手游特别适合 AI 辅助

1. **角色轮廓清晰** — 美漫画风，人物和背景对比度高，YOLO 检测精度高
2. **统一 hitbox** — 不需要针对不同体型调整
3. **高帧率** — 165Hz 下每帧间隔仅 6ms，AI 可以每2-3帧检测一次，不影响流畅度
4. **固定弹道** — 武器后坐力可预编程补偿
5. **短 TTK** — 一枪头即死，AI 精准瞄准优势巨大

## 专属优化模块

### 1. 爆头优先策略

```
近距离 (< 200px) → 瞄准头部 (headOffsetY = 0.08)
中距离 (200-400px) → 瞄准胸部 (chestOffsetY = 0.25)
远距离 (> 400px) → 瞄准腹部 (bodyOffsetY = 0.40)

狙击模式 → 始终瞄准头部
```

### 2. 武器压枪补偿

```
Vandal: 前15发垂直补偿 [-3, -5, -7, -9, -10, -11, -11, -10, -9, -8, -7, -6, -5, -4]
Phantom: 前15发垂直补偿 [-2, -4, -6, -8, -9, -10, -10, -9, -8, -7, -6, -5, -4, -3]

PID 参数随武器切换：
- 步枪: Kp=0.55, Kd=0.35 (稳定)
- 冲锋枪: Kp=0.70, Kd=0.25 (快速)
- 狙击: Kp=0.40, Kd=0.45 (精准)
```

### 3. 数据集准备

#### 数据采集

```bash
# 方法1: 录屏采集
# 游戏中开启录屏（推荐 1080p 60fps）
# 覆盖不同地图、不同距离、不同角度

# 方法2: 截图采集
# 使用 adb 截图
adb shell screencap -p /sdcard/val_screenshot.png
adb pull /sdcard/val_screenshot.png
```

#### 标注要求

使用 LabelImg 或 CVAT 标注：

```
类别：
- enemy_body     # 敌人身体（完整人物）
- enemy_head     # 敌人头部（可选，用于头部检测）
- ally_body      # 队友身体（用于区分敌我）
- ability        # 技能效果（可选）

标注规则：
1. 标注框紧贴人物轮廓
2. 头部标注框单独标出
3. 被遮挡超过 50% 的不标注
4. 极远距离 (< 20px) 的不标注
```

#### 数据集结构

```
val_dataset/
├── train/
│   ├── images/
│   │   ├── val_ascent_001.jpg
│   │   ├── val_bind_002.jpg
│   │   └── ...
│   └── labels/
│       ├── val_ascent_001.txt  # YOLO 格式
│       ├── val_bind_002.txt
│       └── ...
├── val/
│   ├── images/
│   └── labels/
└── val.yaml
```

#### val.yaml

```yaml
path: ./val_dataset
train: train/images
val: val/images

names:
  0: enemy_body
  1: enemy_head
  2: ally_body
```

### 4. 模型训练

```bash
# Step 1: 采集数据（建议 500-1000 张）
python tools/collect_data.py --game valorant --duration 60 --interval 1

# Step 2: 标注数据（使用 LabelImg）
labelimg val_dataset/train/images val_dataset/val.yaml

# Step 3: 训练 YOLOv8n
yolo detect train \
    data=val_dataset/val.yaml \
    model=yolov8n.pt \
    epochs=150 \
    imgsz=192 \
    batch=32 \
    lr0=0.01 \
    name=valorant_v1

# Step 4: 评估
yolo val model=runs/detect/valorant_v1/weights/best.pt data=val_dataset/val.yaml

# Step 5: 转换为 MNN
python tools/convert_model.py \
    --input runs/detect/valorant_v1/weights/best.pt \
    --size 192 \
    --quant int8 \
    --output valorant_int8_192.mnn
```

### 5. 地图适配

不同地图的光照和色调不同，建议每个地图单独采集数据：

| 地图 | 特点 | 注意事项 |
|------|------|----------|
| Bind | 沙漠色调 | 角色偏暗，注意对比度 |
| Haven | 三层结构 | 垂直方向敌人多 |
| Split | 城市风格 | 建筑阴影多 |
| Ascent | 意大利风格 | 亮度适中 |
| Icebox | 冰雪地图 | 白色背景，角色明显 |
| Breeze | 海岛地图 | 强光，注意过曝 |
| Pearl | 水下城市 | 蓝色调 |
| Lotus | 丛林地图 | 绿色调 |

### 6. 运行配置

```java
// 在游戏中推荐的设置
ValorantConfig config = new ValorantConfig();

// 检测参数
config.confThreshold = 0.50f;     // 置信度（无畏契约角色清晰，可以设高）
config.inputSize = 192;            // 输入尺寸（平衡速度和精度）
config.detectEveryNFrames = 2;     // 165Hz 下每 2 帧检测一次

// 瞄准参数
config.aimPart = 0;                // 自动选择（近头远身）
config.headOffsetY = 0.08f;        // 头部偏移

// 武器默认 Vandal
config.currentWeapon = WeaponType.RIFLE;
```

### 7. 天玑9300+ 性能表现

```
设备: 天玑9300+ (4×X4 + 4×A720)
APU: APU 790
游戏帧率: 165 FPS
AI 检测: 每 2 帧一次 → 82.5 次/秒
单次推理: ~6ms (INT8 192, NNAPI/APU)
总延迟: 截图 + 推理 + 触控 ≈ 15ms
温度: 38-42°C（正常游戏温度）
```

## 进阶优化

### 敌我识别

无畏契约中队友有蓝色轮廓/标记，可以训练模型区分敌我：

```
类别：
0: enemy    # 敌人（红色/无标记）
1: ally     # 队友（蓝色标记）

只对 enemy 类别进行瞄准
```

### 技能检测

检测敌方技能效果，自动规避：

```
类别：
0: enemy
1: molly      # 燃烧弹（如 Phoenix 火墙）
2: smoke      # 烟雾（如 Omen 暗影）
3: flash      # 闪光（如 Breach 闪光）
4: wall       # 墙壁（如 Sage 冰墙）

检测到 molly/smoke 时暂停瞄准，避免浪费子弹
```

### 声音辅助

结合游戏声音（脚步声、枪声）判断敌人方位：

```
1. 检测到左侧脚步声 → 预瞄左侧
2. 检测到开枪声 → 优先瞄准声源方向
3. 检测到技能音效 → 准备规避
```

## 注意事项

- 建议在训练场/自定义模式中测试
- 不同段位的对手移动模式不同，需要持续更新数据集
- 游戏更新可能改变武器弹道，需要重新校准压枪参数
- 高帧率模式下 AI 检测频率可以适当降低，节省功耗
