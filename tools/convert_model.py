#!/usr/bin/env python3
"""
模型转换脚本：YOLOv8n → ONNX → MNN (INT8 量化)
适配天玑9300+ APU 790

使用方法：
    python convert_model.py --input yolov8n.pt --output yolov8n_int8_192.mnn --size 192

依赖：
    pip install ultralytics onnx onnxruntime mnn
"""

import argparse
import os
import sys
import subprocess

def parse_args():
    parser = argparse.ArgumentParser(description="YOLOv8n → MNN 转换")
    parser.add_argument("--input", type=str, default="yolov8n.pt", help="输入 .pt 模型")
    parser.add_argument("--output", type=str, default=None, help="输出 .mnn 路径")
    parser.add_argument("--size", type=int, default=192, help="输入尺寸 (192/256/320)")
    parser.add_argument("--quant", type=str, default="int8", choices=["int8", "fp16", "fp32"],
                        help="量化精度")
    parser.add_argument("--calib-dir", type=str, default=None, help="INT8 校准数据集目录")
    parser.add_argument("--no-nms", action="store_true", help="不嵌入 NMS 后处理")
    return parser.parse_args()


def step1_export_onnx(pt_path, onnx_path, input_size):
    """Step 1: PyTorch → ONNX"""
    print(f"\n{'='*50}")
    print(f"Step 1: Export ONNX (input_size={input_size})")
    print(f"{'='*50}")

    from ultralytics import YOLO

    model = YOLO(pt_path)

    # 导出 ONNX
    model.export(
        format="onnx",
        imgsz=input_size,
        simplify=True,          # 简化算子
        opset=12,               # ONNX opset 版本
        dynamic=False,          # 静态 shape（移动端更高效）
    )

    # ultralytics 会在同目录生成 .onnx
    expected_onnx = pt_path.replace(".pt", ".onnx")
    if os.path.exists(expected_onnx):
        if expected_onnx != onnx_path:
            os.rename(expected_onnx, onnx_path)
        print(f"✓ ONNX exported: {onnx_path}")
        return True
    else:
        print(f"✗ ONNX export failed, expected: {expected_onnx}")
        return False


def step2_simplify_onnx(onnx_path):
    """Step 2: 简化 ONNX 图"""
    print(f"\n{'='*50}")
    print(f"Step 2: Simplify ONNX")
    print(f"{'='*50}")

    try:
        import onnx
        from onnxsim import simplify

        model = onnx.load(onnx_path)
        model_simp, check = simplify(model)
        if check:
            onnx.save(model_simp, onnx_path)
            print(f"✓ ONNX simplified")
        else:
            print("⚠ Simplification check failed, using original")
    except ImportError:
        print("⚠ onnxsim not installed, skipping simplification")
        print("  Install: pip install onnxsim")


def step3_convert_mnn(onnx_path, mnn_path, quant_type, calib_dir=None, input_size=192):
    """Step 3: ONNX → MNN (with quantization)"""
    print(f"\n{'='*50}")
    print(f"Step 3: Convert to MNN (quant={quant_type})")
    print(f"{'='*50}")

    # MNN 转换命令
    # 方式1: 使用 MNNConvert 工具
    mnn_convert = "MNNConvert"
    if not shutil.which(mnn_convert):
        # 尝试 Python 包
        try:
            import MNN
            mnn_convert = None  # 用 Python API
        except ImportError:
            print("✗ MNNConvert not found and MNN Python package not installed")
            print("  Install: pip install MNN")
            print("  Or download MNN tools from: https://github.com/alibaba/MNN/releases")
            return False

    if mnn_convert:
        # 命令行方式
        cmd = [
            mnn_convert,
            "-f", "ONNX",
            "--modelFile", onnx_path,
            "--MNNModel", mnn_path,
            "--bizCode", "autoaim",
        ]

        if quant_type == "int8":
            cmd.extend(["--quantized", "--weightQuantBits", "8"])
        elif quant_type == "fp16":
            cmd.extend(["--fp16"])

        print(f"Running: {' '.join(cmd)}")
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            print(f"✗ MNNConvert failed:\n{result.stderr}")
            return False
    else:
        # Python API 方式
        import MNN
        converter = MNN.Converter()
        converter.convert(onnx_path, mnn_path)

    print(f"✓ MNN model: {mnn_path}")

    # 检查文件大小
    size_mb = os.path.getsize(mnn_path) / (1024 * 1024)
    print(f"  Model size: {size_mb:.2f} MB")

    return True


def step4_verify(mnn_path, input_size):
    """Step 4: 验证 MNN 模型"""
    print(f"\n{'='*50}")
    print(f"Step 4: Verify MNN model")
    print(f"{'='*50}")

    try:
        import MNN
        import numpy as np

        interpreter = MNN.Interpreter(mnn_path)
        session = interpreter.createSession()
        input_tensor = interpreter.getSessionInput(session)

        print(f"  Input shape: {input_tensor.getShape()}")
        print(f"  Input dtype: {input_tensor.getDataType()}")

        # 测试推理
        dummy = np.random.randn(1, 3, input_size, input_size).astype(np.float32)
        tmp_input = MNN.Tensor((1, 3, input_size, input_size),
                               MNN.Halide_Type_Float, dummy, MNN.Tensor_DimensionType_Caffe)
        input_tensor.copyFrom(tmp_input)

        import time
        times = []
        for _ in range(10):
            t0 = time.time()
            interpreter.runSession(session)
            times.append((time.time() - t0) * 1000)

        avg_ms = sum(times) / len(times)
        min_ms = min(times)
        print(f"  Avg inference: {avg_ms:.2f}ms (min: {min_ms:.2f}ms)")
        print(f"  ✓ MNN model verified")

    except Exception as e:
        print(f"  ⚠ Verification failed: {e}")


def main():
    args = parse_args()

    pt_path = args.input
    if not os.path.exists(pt_path):
        print(f"✗ Input not found: {pt_path}")
        sys.exit(1)

    # 输出路径
    if args.output is None:
        base = os.path.splitext(pt_path)[0]
        args.output = f"{base}_{args.quant}_{args.size}.mnn"
    mnn_path = args.output

    onnx_path = pt_path.replace(".pt", f"_{args.size}.onnx")

    print(f"Conversion pipeline:")
    print(f"  Input:  {pt_path}")
    print(f"  ONNX:   {onnx_path}")
    print(f"  MNN:    {mnn_path}")
    print(f"  Size:   {args.size}")
    print(f"  Quant:  {args.quant}")

    # 执行转换
    if not step1_export_onnx(pt_path, onnx_path, args.size):
        sys.exit(1)

    step2_simplify_onnx(onnx_path)

    if not step3_convert_mnn(onnx_path, mnn_path, args.quant, args.calib_dir, args.size):
        sys.exit(1)

    step4_verify(mnn_path, args.size)

    print(f"\n{'='*50}")
    print(f"✓ Done! MNN model: {mnn_path}")
    print(f"{'='*50}")
    print(f"\nNext steps:")
    print(f"  1. Copy {mnn_path} to app/src/main/assets/")
    print(f"  2. Build and install the APK")
    print(f"  3. Grant Shizuku permission")


if __name__ == "__main__":
    import shutil
    main()
