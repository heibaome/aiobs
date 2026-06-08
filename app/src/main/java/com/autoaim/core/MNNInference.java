package com.autoaim.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * MNN 推理引擎 - 替换原 QNN Hexagon
 * 适配天玑9300+ APU 790，支持 NNAPI / GPU / CPU 异构调度
 */
public class MNNInference {

    private static final String TAG = "MNNInference";

    // MNN 前端类型
    public static final int FORWARD_CPU = 0;
    public static final int FORWARD_OPENCL = 3;   // GPU
    public static final int FORWARD_VULKAN = 7;   // GPU fallback
    public static final int FORWARD_NNAPI = 5;    // NPU (APU 790)

    static {
        System.loadLibrary("mnn_inference");
    }

    // JNI native methods
    private native long nativeCreate(String modelPath, int forwardType, int numThread);
    private native float[] nativeDetect(long handle, ByteBuffer inputData, int width, int height, float confThreshold, float nmsThreshold);
    private native void nativeDestroy(long handle);
    private native int nativeGetInputWidth(long handle);
    private native int nativeGetInputHeight(long handle);

    private long nativeHandle;
    private final int inputWidth;
    private final int inputHeight;
    private final int forwardType;

    // 预分配 buffer，避免每帧 GC
    private ByteBuffer inputBuffer;
    private final float[] mean = {0.0f, 0.0f, 0.0f};
    private final float[] norm = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};

    /**
     * @param context     Android context
     * @param modelAsset  assets 中的模型文件名 (如 "yolov8n_int8_192.mnn")
     * @param forwardType 推理后端：FORWARD_NNAPI (APU) / FORWARD_OPENCL (GPU) / FORWARD_CPU
     * @param numThread   CPU 线程数（仅 CPU 模式生效）
     */
    public MNNInference(Context context, String modelAsset, int forwardType, int numThread) {
        this.forwardType = forwardType;

        // 从 assets 复制模型到内部存储（MNN 需要文件路径）
        String modelPath = copyAssetToInternal(context, modelAsset);

        // 创建推理实例
        nativeHandle = nativeCreate(modelPath, forwardType, numThread);
        if (nativeHandle == 0) {
            throw new RuntimeException("MNN engine create failed: " + modelPath);
        }

        inputWidth = nativeGetInputWidth(nativeHandle);
        inputHeight = nativeGetInputHeight(nativeHandle);

        // 预分配输入 buffer (NCHW, FP32)
        inputBuffer = ByteBuffer.allocateDirect(4 * 3 * inputHeight * inputWidth);
        inputBuffer.order(ByteOrder.nativeOrder());

        Log.i(TAG, String.format("MNN engine init: model=%s, input=%dx%d, forward=%d",
                modelAsset, inputWidth, inputHeight, forwardType));
    }

    /**
     * 推理入口 - 检测目标
     * @param bitmap  截图 Bitmap (ARGB_8888)
     * @param confTh  置信度阈值
     * @param nmsTh   NMS 阈值
     * @return 检测结果列表
     */
    public List<Detection> detect(Bitmap bitmap, float confTh, float nmsTh) {
        if (nativeHandle == 0) return new ArrayList<>();

        // 预处理：resize + normalize + NCHW
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true);
        preprocess(resized);

        // 推理
        long t0 = System.nanoTime();
        float[] rawOutput = nativeDetect(nativeHandle, inputBuffer, inputWidth, inputHeight, confTh, nmsTh);
        long inferenceMs = (System.nanoTime() - t0) / 1_000_000;

        // 后处理
        List<Detection> results = postprocess(rawOutput, bitmap.getWidth(), bitmap.getHeight(), confTh, nmsTh);

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, String.format("detect: %d results, inference=%dms", results.size(), inferenceMs));
        }

        return results;
    }

    /**
     * 快速检测 - 跳过 resize（假设输入已经是正确尺寸）
     */
    public List<Detection> detectFast(Bitmap preprocessed, int originalW, int originalH, float confTh, float nmsTh) {
        if (nativeHandle == 0) return new ArrayList<>();

        preprocess(preprocessed);

        long t0 = System.nanoTime();
        float[] rawOutput = nativeDetect(nativeHandle, inputBuffer, inputWidth, inputHeight, confTh, nmsTh);
        long inferenceMs = (System.nanoTime() - t0) / 1_000_000;

        List<Detection> results = postprocess(rawOutput, originalW, originalH, confTh, nmsTh);

        Log.d(TAG, String.format("detectFast: %d results, %dms", results.size(), inferenceMs));
        return results;
    }

    /**
     * 预处理：Bitmap → NCHW FloatBuffer (归一化)
     */
    private void preprocess(Bitmap bitmap) {
        inputBuffer.rewind();

        int[] pixels = new int[inputWidth * inputHeight];
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);

        // NCHW 布局：先 R 通道，再 G，再 B
        int imageSize = inputWidth * inputHeight;

        // R channel
        for (int i = 0; i < imageSize; i++) {
            int pixel = pixels[i];
            float val = ((pixel >> 16) & 0xFF) * norm[0] + mean[0];
            inputBuffer.putFloat(val);
        }
        // G channel
        for (int i = 0; i < imageSize; i++) {
            int pixel = pixels[i];
            float val = ((pixel >> 8) & 0xFF) * norm[1] + mean[1];
            inputBuffer.putFloat(val);
        }
        // B channel
        for (int i = 0; i < imageSize; i++) {
            int pixel = pixels[i];
            float val = (pixel & 0xFF) * norm[2] + mean[2];
            inputBuffer.putFloat(val);
        }

        inputBuffer.rewind();
    }

    /**
     * 后处理：解析 YOLOv8 输出 → Detection 列表
     * YOLOv8 输出格式: [1, 84, 8400] (4 bbox + 80 class scores)
     */
    private List<Detection> postprocess(float[] rawOutput, int origW, int origH, float confTh, float nmsTh) {
        List<Detection> detections = new ArrayList<>();

        if (rawOutput == null || rawOutput.length == 0) return detections;

        // YOLOv8 输出: [batch, 4+num_classes, num_anchors]
        // 通常 192x192 输入 → 8400 anchors
        int numAnchors = rawOutput.length / 84;  // 4 bbox + 80 COCO classes
        int numClasses = 80;

        float scaleX = (float) origW / inputWidth;
        float scaleY = (float) origH / inputHeight;

        for (int i = 0; i < numAnchors; i++) {
            // 找最大类别分数
            float maxScore = 0;
            int maxClass = -1;
            for (int c = 0; c < numClasses; c++) {
                // rawOutput 布局: [84, 8400]，按列优先
                float score = rawOutput[(4 + c) * numAnchors + i];
                if (score > maxScore) {
                    maxScore = score;
                    maxClass = c;
                }
            }

            if (maxScore < confTh) continue;

            // 提取 bbox (center_x, center_y, w, h)
            float cx = rawOutput[0 * numAnchors + i];
            float cy = rawOutput[1 * numAnchors + i];
            float w  = rawOutput[2 * numAnchors + i];
            float h  = rawOutput[3 * numAnchors + i];

            // 转换到原图坐标
            float left   = (cx - w / 2) * scaleX;
            float top    = (cy - h / 2) * scaleY;
            float right  = (cx + w / 2) * scaleX;
            float bottom = (cy + h / 2) * scaleY;

            detections.add(new Detection(
                    new RectF(left, top, right, bottom),
                    maxScore,
                    maxClass
            ));
        }

        // NMS
        return nms(detections, nmsTh);
    }

    /**
     * NMS (Non-Maximum Suppression)
     */
    private List<Detection> nms(List<Detection> detections, float iouThreshold) {
        detections.sort((a, b) -> Float.compare(b.score, a.score));

        List<Detection> result = new ArrayList<>();
        boolean[] suppressed = new boolean[detections.size()];

        for (int i = 0; i < detections.size(); i++) {
            if (suppressed[i]) continue;
            result.add(detections.get(i));

            for (int j = i + 1; j < detections.size(); j++) {
                if (suppressed[j]) continue;
                if (iou(detections.get(i).box, detections.get(j).box) > iouThreshold) {
                    suppressed[j] = true;
                }
            }
        }
        return result;
    }

    private float iou(RectF a, RectF b) {
        float interLeft   = Math.max(a.left, b.left);
        float interTop    = Math.max(a.top, b.top);
        float interRight  = Math.min(a.right, b.right);
        float interBottom = Math.min(a.bottom, b.bottom);

        float interArea = Math.max(0, interRight - interLeft) * Math.max(0, interBottom - interTop);
        float aArea = (a.right - a.left) * (a.bottom - a.top);
        float bArea = (b.right - b.left) * (b.bottom - b.top);

        return interArea / (aArea + bArea - interArea + 1e-6f);
    }

    /**
     * 从 assets 复制模型到内部存储
     */
    private String copyAssetToInternal(Context context, String assetName) {
        File outFile = new File(context.getFilesDir(), assetName);
        if (outFile.exists()) return outFile.getAbsolutePath();

        try (InputStream is = context.getAssets().open(assetName);
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy model: " + assetName, e);
        }
        return outFile.getAbsolutePath();
    }

    public int getInputWidth() { return inputWidth; }
    public int getInputHeight() { return inputHeight; }

    public void release() {
        if (nativeHandle != 0) {
            nativeDestroy(nativeHandle);
            nativeHandle = 0;
        }
    }

    /**
     * 检测结果
     */
    public static class Detection {
        public final RectF box;       // 原图坐标系的边界框
        public final float score;     // 置信度
        public final int classId;     // 类别 ID

        public Detection(RectF box, float score, int classId) {
            this.box = box;
            this.score = score;
            this.classId = classId;
        }

        /** 获取目标中心点 */
        public float centerX() { return box.centerX(); }
        public float centerY() { return box.centerY(); }

        @Override
        public String toString() {
            return String.format("Detection{class=%d, score=%.2f, box=[%.0f,%.0f,%.0f,%.0f]}",
                    classId, score, box.left, box.top, box.right, box.bottom);
        }
    }
}
