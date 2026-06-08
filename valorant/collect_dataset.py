#!/usr/bin/env python3
"""
无畏契约手游 数据集采集脚本

功能：
1. 通过 ADB 截图采集游戏画面
2. 自动按时间间隔截图
3. 按地图/模式分类存储
4. 生成标注模板

使用方法：
    # 连接手机，开始游戏，然后运行：
    python collect_dataset.py --output val_dataset --interval 2 --duration 300

    # 指定地图：
    python collect_dataset.py --output val_dataset --map ascent --interval 1

    # 只采集特定场景：
    python collect_dataset.py --output val_dataset --mode competitive
"""

import argparse
import os
import subprocess
import time
from datetime import datetime


def parse_args():
    parser = argparse.ArgumentParser(description="无畏契约手游数据采集")
    parser.add_argument("--output", type=str, default="val_dataset", help="输出目录")
    parser.add_argument("--interval", type=float, default=2.0, help="截图间隔（秒）")
    parser.add_argument("--duration", type=int, default=300, help="采集时长（秒）")
    parser.add_argument("--map", type=str, default="unknown", help="当前地图名称")
    parser.add_argument("--mode", type=str, default="competitive",
                        choices=["competitive", "unrated", "spike_rush", "deathmatch", "custom"],
                        help="游戏模式")
    parser.add_argument("--device", type=str, default=None, help="ADB 设备序列号")
    parser.add_argument("--resolution", type=str, default="1080",
                        choices=["720", "1080", "1440"],
                        help="截图分辨率")
    return parser.parse_args()


def check_adb(device=None):
    """检查 ADB 连接"""
    cmd = ["adb"]
    if device:
        cmd.extend(["-s", device])

    try:
        result = subprocess.run(cmd + ["devices"], capture_output=True, text=True, timeout=5)
        lines = result.stdout.strip().split("\n")
        devices = [l for l in lines[1:] if l.strip() and "device" in l]
        if not devices:
            print("✗ 没有检测到 ADB 设备")
            print("  请确保：")
            print("  1. 手机已连接 USB 并开启 USB 调试")
            print("  2. 手机上已授权调试")
            return False
        print(f"✓ 检测到 {len(devices)} 个设备")
        for d in devices:
            print(f"  {d}")
        return True
    except Exception as e:
        print(f"✗ ADB 检查失败: {e}")
        return False


def take_screenshot(device=None, resolution="1080"):
    """通过 ADB 截图"""
    cmd = ["adb"]
    if device:
        cmd.extend(["-s", device])

    # 截图到设备
    subprocess.run(cmd + ["shell", "screencap", "-p", "/sdcard/autoaim_temp.png"],
                   capture_output=True, timeout=10)

    # 拉取到本地
    local_path = "/tmp/autoaim_temp.png"
    subprocess.run(cmd + ["pull", "/sdcard/autoaim_temp.png", local_path],
                   capture_output=True, timeout=10)

    if os.path.exists(local_path):
        return local_path
    return None


def get_game_info(device=None):
    """尝试获取游戏信息（分辨率、帧率等）"""
    cmd = ["adb"]
    if device:
        cmd.extend(["-s", device])

    try:
        # 获取屏幕分辨率
        result = subprocess.run(cmd + ["shell", "wm", "size"],
                               capture_output=True, text=True, timeout=5)
        resolution = result.stdout.strip()
        print(f"  屏幕分辨率: {resolution}")

        # 获取当前应用
        result = subprocess.run(
            cmd + ["shell", "dumpsys", "activity", "recents"],
            capture_output=True, text=True, timeout=5
        )
        # 尝试找到无畏契约进程
        for line in result.stdout.split("\n"):
            if "valorant" in line.lower() or "riot" in line.lower() or "valm" in line.lower():
                print(f"  游戏进程: {line.strip()[:80]}")
                break

    except Exception as e:
        print(f"  获取游戏信息失败: {e}")


def create_dataset_structure(output_dir, map_name, mode):
    """创建数据集目录结构"""
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    session_dir = os.path.join(output_dir, "raw", f"{map_name}_{mode}_{timestamp}")
    images_dir = os.path.join(session_dir, "images")
    os.makedirs(images_dir, exist_ok=True)

    # 创建标注目录
    labels_dir = os.path.join(session_dir, "labels")
    os.makedirs(labels_dir, exist_ok=True)

    return session_dir, images_dir, labels_dir


def generate_label_template(image_path, label_path):
    """生成空的标注模板（供后续手动标注）"""
    # 创建一个空的标注文件
    with open(label_path, 'w') as f:
        f.write("")  # 空文件，等标注工具填充


def main():
    args = parse_args()

    print("=" * 60)
    print("  无畏契约手游 数据集采集工具")
    print("=" * 60)
    print(f"  输出目录: {args.output}")
    print(f"  截图间隔: {args.interval}s")
    print(f"  采集时长: {args.duration}s")
    print(f"  当前地图: {args.map}")
    print(f"  游戏模式: {args.mode}")
    print("=" * 60)

    # 检查 ADB
    if not check_adb(args.device):
        return

    # 获取游戏信息
    print("\n设备信息:")
    get_game_info(args.device)

    # 创建目录
    session_dir, images_dir, labels_dir = create_dataset_structure(
        args.output, args.map, args.mode
    )
    print(f"\n保存目录: {session_dir}")

    # 开始采集
    print(f"\n开始采集... (按 Ctrl+C 停止)")
    print("-" * 40)

    count = 0
    start_time = time.time()

    try:
        while True:
            elapsed = time.time() - start_time
            if elapsed >= args.duration:
                print(f"\n采集时长 {args.duration}s 已到")
                break

            # 截图
            screenshot_path = take_screenshot(args.device, args.resolution)
            if screenshot_path:
                count += 1
                timestamp = datetime.now().strftime("%H%M%S_%f")[:12]
                filename = f"val_{args.map}_{count:04d}_{timestamp}.png"
                dest_path = os.path.join(images_dir, filename)

                # 复制到数据集目录
                import shutil
                shutil.copy2(screenshot_path, dest_path)

                # 生成标注模板
                label_filename = filename.replace(".png", ".txt")
                generate_label_template(dest_path, os.path.join(labels_dir, label_filename))

                # 进度显示
                remaining = args.duration - elapsed
                print(f"\r  [{count:4d}] {filename} | "
                      f"已采集: {elapsed:.0f}s | 剩余: {remaining:.0f}s | "
                      f"间隔: {args.interval}s", end="", flush=True)
            else:
                print(f"\r  [{count:4d}] 截图失败，重试...", end="", flush=True)

            time.sleep(args.interval)

    except KeyboardInterrupt:
        print(f"\n\n用户中断采集")

    # 统计
    total_time = time.time() - start_time
    print(f"\n{'='*40}")
    print(f"  采集完成!")
    print(f"  总截图数: {count}")
    print(f"  总时长: {total_time:.1f}s")
    print(f"  保存目录: {images_dir}")
    print(f"{'='*40}")

    # 生成数据集配置
    generate_dataset_config(session_dir, args.map, args.mode, count)

    # 下一步提示
    print(f"\n下一步:")
    print(f"  1. 使用 LabelImg 标注图片:")
    print(f"     labelimg {images_dir} ../val.yaml")
    print(f"  2. 标注完成后，运行训练:")
    print(f"     python train_valorant.py --data {session_dir}")


def generate_dataset_config(session_dir, map_name, mode, count):
    """生成数据集配置文件"""
    config = f"""# 无畏契约手游 数据集配置
# 自动生成于 {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}

map: {map_name}
mode: {mode}
total_images: {count}
source: ADB screenshot

# 标注类别
classes:
  0: enemy_body      # 敌人身体
  1: enemy_head      # 敌人头部
  2: ally_body       # 队友身体（用于敌我识别）

# 标注说明
# 使用 LabelImg 进行标注
# 格式: YOLO (class_id center_x center_y width height)
# 归一化到 0-1 范围
"""
    config_path = os.path.join(session_dir, "dataset_info.yaml")
    with open(config_path, 'w') as f:
        f.write(config)
    print(f"  配置文件: {config_path}")


if __name__ == "__main__":
    main()
