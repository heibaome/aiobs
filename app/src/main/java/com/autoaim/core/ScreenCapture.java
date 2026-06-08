package com.autoaim.core;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 屏幕截图模块 - 双缓冲异步方案
 * 优化点：
 * 1. 双缓冲：一个 buffer 在推理，另一个在截图，减少等待
 * 2. ImageReader 回调触发，不用轮询
 * 3. 裁剪到游戏区域，减少预处理开销
 */
public class ScreenCapture {

    private static final String TAG = "ScreenCapture";

    public interface CaptureCallback {
        /** 新帧就绪，bitmap 在调用期间有效，用完不要 recycle */
        void onFrameCaptured(Bitmap bitmap, long timestampMs);
    }

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;

    // 双缓冲
    private final Bitmap[] buffers = new Bitmap[2];
    private int activeBuffer = 0;
    private final ReentrantLock bufferLock = new ReentrantLock();
    private final AtomicBoolean isCapturing = new AtomicBoolean(false);

    // 裁剪区域（游戏画面区域，排除状态栏/导航栏）
    private int captureX, captureY, captureWidth, captureHeight;
    private int screenWidth, screenHeight;

    // 预分配像素数组，避免每帧 GC
    private int[] pixelBuffer;

    private CaptureCallback callback;

    /**
     * 初始化截图器
     * @param projection MediaProjection 实例
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     * @param cropX 裁剪起始X（游戏区域）
     * @param cropY 裁剪起始Y
     * @param cropW 裁剪宽度
     * @param cropH 裁剪高度
     * @param scale 缩放比例（0.5 = 一半分辨率，推荐）
     */
    public void init(MediaProjection projection, int screenWidth, int screenHeight,
                     int cropX, int cropY, int cropW, int cropH, float scale) {
        this.projection = projection;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.captureX = cropX;
        this.captureY = cropY;
        this.captureWidth = cropW > 0 ? cropW : screenWidth;
        this.captureHeight = cropH > 0 ? cropH : screenHeight;

        int scaledW = (int)(captureWidth * scale);
        int scaledH = (int)(captureHeight * scale);

        // 启动截图线程（必须在 setOnImageAvailableListener 之前创建 handler）
        captureThread = new HandlerThread("ScreenCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        // 预分配像素数组
        pixelBuffer = new int[scaledW * scaledH];

        // 创建双缓冲 Bitmap
        for (int i = 0; i < 2; i++) {
            buffers[i] = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888);
        }

        // ImageReader（低分辨率，只用于触发回调）
        imageReader = ImageReader.newInstance(scaledW, scaledH, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);

        Log.i(TAG, String.format("init: screen=%dx%d, crop=[%d,%d,%d,%d], scale=%.2f, output=%dx%d",
                screenWidth, screenHeight, cropX, cropY, cropW, cropH, scale, scaledW, scaledH));
    }

    /**
     * 用默认参数初始化（全屏，50%缩放）
     */
    public void initDefault(MediaProjection projection, int screenWidth, int screenHeight) {
        init(projection, screenWidth, screenHeight, 0, 0, screenWidth, screenHeight, 0.5f);
    }

    /**
     * 开始截图
     */
    public void start(CaptureCallback callback) {
        if (isCapturing.get()) return;
        this.callback = callback;

        // 创建 VirtualDisplay
        virtualDisplay = projection.createVirtualDisplay(
                "AutoAim",
                captureWidth, captureHeight, 1,  // 1 dpi，反正只看像素
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                captureHandler,
                null
        );

        isCapturing.set(true);
        Log.i(TAG, "Capture started");
    }

    /**
     * ImageReader 回调 - 有新帧
     */
    private void onImageAvailable(ImageReader reader) {
        if (!isCapturing.get()) return;

        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            Bitmap frameBitmap;
            bufferLock.lock();
            try {
                activeBuffer = 1 - activeBuffer;
                imageToBitmap(image, buffers[activeBuffer]);
                frameBitmap = buffers[activeBuffer];  // 锁内保存引用
            } finally {
                bufferLock.unlock();
            }

            long timestamp = System.currentTimeMillis();
            if (callback != null) {
                callback.onFrameCaptured(frameBitmap, timestamp);
            }
        } catch (Exception e) {
            Log.e(TAG, "Capture error", e);
        } finally {
            if (image != null) image.close();
        }
    }

    /**
     * 获取当前帧的副本（线程安全，推理用）
     * 调用方负责 recycle
     */
    public Bitmap captureFrameCopy() {
        bufferLock.lock();
        try {
            int readIdx = 1 - activeBuffer;  // 读非活跃 buffer（刚写完的）
            return buffers[readIdx].copy(buffers[readIdx].getConfig(), false);
        } finally {
            bufferLock.unlock();
        }
    }


    /**
     * Image → Bitmap 转换
     */
    private void imageToBitmap(Image image, Bitmap target) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return;

        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();

        int w = image.getWidth();
        int h = image.getHeight();
        int tw = target.getWidth();
        int th = target.getHeight();

        // RGBA_8888 → ARGB_8888 通道转换
        // Image buffer: R,G,B,A 字节序（每个像素 4 字节，可能有 rowPadding）
        // Bitmap: 0xAARRGGBB int 格式
        buffer.rewind();

        for (int y = 0; y < th && y < h; y++) {
            buffer.position(y * rowStride);
            for (int x = 0; x < tw && x < w; x++) {
                int r = buffer.get() & 0xFF;
                int g = buffer.get() & 0xFF;
                int b = buffer.get() & 0xFF;
                int a = buffer.get() & 0xFF;
                pixelBuffer[y * tw + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }

        target.setPixels(pixelBuffer, 0, tw, 0, 0, tw, th);
    }

    /**
     * 停止截图
     */
    public void stop() {
        isCapturing.set(false);

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }

        Log.i(TAG, "Capture stopped");
    }

    /**
     * 释放所有资源
     */
    public void release() {
        stop();
        for (int i = 0; i < 2; i++) {
            if (buffers[i] != null && !buffers[i].isRecycled()) {
                buffers[i].recycle();
                buffers[i] = null;
            }
        }
    }
}
