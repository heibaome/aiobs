#!/usr/bin/env python3
"""
无畏契约手游 YOLOv8n 训练脚本

针对无畏契约手游特点优化：
- 输入尺寸 192x192（平衡速度和精度）
- INT8 量化（适配天玑9300+ APU）
- 敌我识别训练
- 数据增强（针对游戏画面）

使用方法：
    # 基础训练
    python train_valorant.py --data val_dataset/val.yaml

    # 使用预训练权重
    python train_valorant.py --data val_dataset/val.yaml --pretrained yolov8n.pt

    # 继续训练
    python train_valorant.py --data val_dataset/val.yaml --resume runs/detect/valorant_v1/weights/last.pt
"""

import argparse
import os
import yaml
from ultralytics import YOLO


def parse_args():
    parser = argparse.ArgumentParser(description="无畏契约手游 YOLOv8n 训练")
    parser.add_argument("--data", type=str, required=True, help="数据集配置文件")
    parser.add_argument("--pretrained", type=str, default="yolov8n.pt", help="预训练权重")
    parser.add_argument("--resume", type=str, default=None, help="继续训练的权重")
    parser.add_argument("--epochs", type=int, default=150, help="训练轮数")
    parser.add_argument("--batch", type=int, default=32, help="批次大小")
    parser.add_argument("--imgsz", type=int, default=192, help="输入尺寸")
    parser.add_argument("--name", type=str, default="valorant_v1", help="实验名称")
    parser.add_argument("--workers", type=int, default=8, help="数据加载线程数")
    parser.add_argument("--device", type=str, default="0", help="GPU 设备")
    parser.add_argument("--lr0", type=float, default=0.01, help="初始学习率")
    parser.add_argument("--patience", type=int, default=30, help="早停耐心值")
    return parser.parse_args()


def validate_dataset(data_path):
    """验证数据集"""
    if not os.path.exists(data_path):
        print(f"✗ 数据集配置不存在: {data_path}")
        return False

    with open(data_path, 'r') as f:
        data = yaml.safe_load(f)

    # 检查目录
    base_dir = os.path.dirname(data_path)
    train_dir = os.path.join(base_dir, data.get('train', 'train/images'))
    val_dir = os.path.join(base_dir, data.get('val', 'val/images'))

    if not os.path.exists(train_dir):
        print(f"✗ 训练集目录不存在: {train_dir}")
        return False

    # 统计图片数量
    train_images = [f for f in os.listdir(train_dir) if f.endswith(('.jpg', '.png', '.jpeg'))]
    val_images = []
    if os.path.exists(val_dir):
        val_images = [f for f in os.listdir(val_dir) if f.endswith(('.jpg', '.png', '.jpeg'))]

    print(f"✓ 数据集验证通过:")
    print(f"  训练集: {len(train_images)} 张图片")
    print(f"  验证集: {len(val_images)} 张图片")
    print(f"  类别数: {len(data.get('names', []))}")

    if len(train_images) < 100:
        print(f"⚠ 警告: 训练集图片太少（{len(train_images)}），建议至少 500 张")

    return True


def get_valorant_augmentation():
    """无畏契约专属数据增强参数"""
    return {
        # 基础增强
        'hsv_h': 0.015,      # 色调变化（无畏契约色彩鲜明）
        'hsv_s': 0.5,        # 饱和度变化
        'hsv_v': 0.4,        # 亮度变化

        # 几何变换
        'degrees': 5.0,       # 旋转角度（小角度，因为游戏画面通常水平）
        'translate': 0.1,     # 平移
        'scale': 0.3,         # 缩放（模拟远近）
        'shear': 2.0,         # 剪切
        'perspective': 0.0,   # 透视变换（关闭）

        # 翻转
        'flipud': 0.0,        # 上下翻转（关闭，游戏中不会倒过来）
        'fliplr': 0.5,        # 左右翻转（开启）

        # 马赛克和混合
        'mosaic': 1.0,        # 马赛克增强
        'mixup': 0.1,         # MixUp 增强
        'copy_paste': 0.0,    # 复制粘贴（关闭）

        # 针对无畏契约的特殊增强
        'erasing': 0.2,       # 随机擦除（模拟遮挡）
        'crop_fraction': 0.8, # 随机裁剪
    }


def train(args):
    """训练主函数"""
    print("=" * 60)
    print("  无畏契约手游 YOLOv8n 训练")
    print("=" * 60)

    # 验证数据集
    if not validate_dataset(args.data):
        return

    # 加载模型
    if args.resume:
        print(f"\n继续训练: {args.resume}")
        model = YOLO(args.resume)
    else:
        print(f"\n加载预训练模型: {args.pretrained}")
        model = YOLO(args.pretrained)

    # 获取增强参数
    augmentation = get_valorant_augmentation()

    # 训练配置
    train_args = {
        'data': args.data,
        'epochs': args.epochs,
        'batch': args.batch,
        'imgsz': args.imgsz,
        'name': args.name,
        'workers': args.workers,
        'device': args.device,
        'lr0': args.lr0,
        'patience': args.patience,

        # 优化器
        'optimizer': 'AdamW',
        'cos_lr': True,           # 余弦退火学习率
        'warmup_epochs': 5,       # 预热轮数

        # 无畏契约专属增强
        **augmentation,

        # 其他
        'save': True,
        'save_period': 10,        # 每 10 轮保存一次
        'plots': True,            # 生成训练图表
        'verbose': True,
    }

    print(f"\n训练参数:")
    for k, v in train_args.items():
        if k not in augmentation:
            print(f"  {k}: {v}")

    print(f"\n数据增强:")
    for k, v in augmentation.items():
        print(f"  {k}: {v}")

    # 开始训练
    print(f"\n{'='*60}")
    print(f"  开始训练...")
    print(f"{'='*60}")

    results = model.train(**train_args)

    # 训练完成
    print(f"\n{'='*60}")
    print(f"  训练完成!")
    print(f"{'='*60}")
    print(f"  最佳权重: runs/detect/{args.name}/weights/best.pt")
    print(f"  最终权重: runs/detect/{args.name}/weights/last.pt")

    # 评估
    print(f"\n评估最佳模型...")
    metrics = model.val()
    print(f"  mAP50: {metrics.box.map50:.4f}")
    print(f"  mAP50-95: {metrics.box.map:.4f}")

    return results


def export_model(args):
    """导出模型为 ONNX 和 MNN"""
    best_weights = f"runs/detect/{args.name}/weights/best.pt"
    if not os.path.exists(best_weights):
        print(f"✗ 最佳权重不存在: {best_weights}")
        return

    print(f"\n导出模型...")

    # 导出 ONNX
    model = YOLO(best_weights)
    model.export(format="onnx", imgsz=args.imgsz, simplify=True)
    print(f"✓ ONNX 导出完成")

    # 转换 MNN
    onnx_path = best_weights.replace(".pt", ".onnx")
    mnn_path = best_weights.replace(".pt", f"_int8_{args.imgsz}.mnn")

    print(f"\n转换 MNN...")
    print(f"  ONNX: {onnx_path}")
    print(f"  MNN:  {mnn_path}")
    print(f"\n运行以下命令转换:")
    print(f"  python tools/convert_model.py --input {onnx_path} --output {mnn_path} --size {args.imgsz} --quant int8")


if __name__ == "__main__":
    args = parse_args()
    train(args)
    export_model(args)
