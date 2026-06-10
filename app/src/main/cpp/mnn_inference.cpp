#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <MNN/Interpreter.hpp>
#include <MNN/MNNDefine.h>
#include <MNN/Tensor.hpp>
#include <MNN/ImageProcess.hpp>

// ============================================================
// 工具函数（必须在 extern "C" 之前定义）
// ============================================================

static long getCurrentTimeMs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

#define TAG "MNN_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct MNNContext {
    std::shared_ptr<MNN::Interpreter> interpreter;
    MNN::Session* session = nullptr;
    MNN::Tensor* inputTensor = nullptr;
    int inputWidth = 0;
    int inputHeight = 0;
    int numThread = 4;
};

// ============================================================
// JNI 方法实现
// ============================================================

extern "C" {

/**
 * 创建 MNN 推理实例
 * @param modelPath 模型文件路径
 * @param forwardType 0=CPU, 3=OpenCL, 5=NNAPI, 7=Vulkan
 * @param numThread CPU线程数
 * @return native handle (pointer)
 */
JNIEXPORT jlong JNICALL
Java_com_autoaim_core_MNNInference_nativeCreate(
        JNIEnv *env, jobject thiz, jstring modelPath, jint forwardType, jint numThread) {

    const char *path = env->GetStringUTFChars(modelPath, nullptr);

    auto* ctx = new MNNContext();
    ctx->numThread = numThread;

    // 创建 Interpreter
    ctx->interpreter.reset(MNN::Interpreter::createFromFile(path));
    if (!ctx->interpreter) {
        LOGE("Failed to create interpreter: %s", path);
        delete ctx;
        env->ReleaseStringUTFChars(modelPath, path);
        return 0;
    }

    // 配置推理后端
    MNN::ScheduleConfig scheduleConfig;
    scheduleConfig.type = static_cast<MNNForwardType>(forwardType);
    scheduleConfig.numThread = numThread;

    // 针对天玑9300+ APU 优化配置
    MNN::BackendConfig backendConfig;
    backendConfig.precision = MNN::BackendConfig::PrecisionMode::Precision_Low;  // 低精度加速
    backendConfig.memory = MNN::BackendConfig::MemoryMode::Memory_Low;            // 低内存占用
    scheduleConfig.backendConfig = &backendConfig;

    // 创建 Session
    ctx->session = ctx->interpreter->createSession(scheduleConfig);
    if (!ctx->session) {
        LOGE("Failed to create MNN session, forwardType=%d", forwardType);
        // 回退到 CPU
        LOGI("Fallback to CPU");
        scheduleConfig.type = MNN_FORWARD_CPU;
        ctx->session = ctx->interpreter->createSession(scheduleConfig);
        if (!ctx->session) {
            LOGE("CPU fallback also failed");
            delete ctx;
            env->ReleaseStringUTFChars(modelPath, path);
            return 0;
        }
    }

    // 获取输入 tensor 信息
    auto inputTensor = ctx->interpreter->getSessionInput(ctx->session, nullptr);
    if (inputTensor) {
        auto shape = inputTensor->shape();
        if (shape.size() == 4) {  // NCHW
            ctx->inputHeight = shape[2];
            ctx->inputWidth = shape[3];
        } else if (shape.size() == 3) {  // CHW
            ctx->inputHeight = shape[1];
            ctx->inputWidth = shape[2];
        }
        ctx->inputTensor = inputTensor;
    }

    LOGI("MNN engine created: %s, input=%dx%d, forward=%d, threads=%d",
         path, ctx->inputWidth, ctx->inputHeight, forwardType, numThread);

    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

/**
 * 执行推理
 * @param handle native handle
 * @param inputData 输入数据 (NCHW, FP32, ByteBuffer)
 * @param width 输入宽
 * @param height 输入高
 * @param confThreshold 置信度阈值
 * @param nmsThreshold NMS阈值
 * @return float[] 原始输出 (展平的一维数组)
 */
JNIEXPORT jfloatArray JNICALL
Java_com_autoaim_core_MNNInference_nativeDetect(
        JNIEnv *env, jobject thiz, jlong handle, jobject inputData,
        jint width, jint height, jfloat confThreshold, jfloat nmsThreshold) {

    auto* ctx = reinterpret_cast<MNNContext*>(handle);
    if (!ctx || !ctx->session) return nullptr;

    // 获取输入 buffer 数据
    auto* inputPtr = (float*)env->GetDirectBufferAddress(inputData);
    if (!inputPtr) {
        LOGE("Failed to get input buffer address");
        return nullptr;
    }

    // 复制数据到输入 tensor
    auto inputTensor = ctx->interpreter->getSessionInput(ctx->session, nullptr);
    if (!inputTensor) {
        LOGE("Failed to get input tensor");
        return nullptr;
    }

    // 使用 copyFromHostTensor 确保数据类型正确转换
    // 即使模型是 INT8 量化输入也能自动处理
    auto inputDims = inputTensor->shape();
    std::shared_ptr<MNN::Tensor> wrapTensor(
        MNN::Tensor::create(inputDims, halide_type_of<float>(), inputPtr));
    inputTensor->copyFromHostTensor(wrapTensor.get());

    // 执行推理
    long t0 = getCurrentTimeMs();
    ctx->interpreter->runSession(ctx->session);
    long inferenceMs = getCurrentTimeMs() - t0;

    LOGD("MNN inference: %ldms", inferenceMs);

    // 获取输出 tensor
    auto outputTensor = ctx->interpreter->getSessionOutput(ctx->session, nullptr);
    if (!outputTensor) {
        LOGE("Failed to get output tensor");
        return nullptr;
    }

    // 读取输出数据
    auto outputShape = outputTensor->shape();
    int totalElements = 1;
    for (int dim : outputShape) {
        totalElements *= dim;
    }

    // 创建 Java float 数组返回
    jfloatArray result = env->NewFloatArray(totalElements);
    if (!result) return nullptr;

    // 使用 copyToHostTensor 确保从量化格式正确转换为 float
    std::shared_ptr<MNN::Tensor> outHost(
        MNN::Tensor::create(outputShape, halide_type_of<float>()));
    outputTensor->copyToHostTensor(outHost.get());
    env->SetFloatArrayRegion(result, 0, totalElements, outHost->host<float>());

    return result;
}

/**
 * 获取模型输入宽
 */
JNIEXPORT jint JNICALL
Java_com_autoaim_core_MNNInference_nativeGetInputWidth(
        JNIEnv *env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<MNNContext*>(handle);
    return ctx ? ctx->inputWidth : 0;
}

/**
 * 获取模型输入高
 */
JNIEXPORT jint JNICALL
Java_com_autoaim_core_MNNInference_nativeGetInputHeight(
        JNIEnv *env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<MNNContext*>(handle);
    return ctx ? ctx->inputHeight : 0;
}

/**
 * 释放资源
 */
JNIEXPORT void JNICALL
Java_com_autoaim_core_MNNInference_nativeDestroy(
        JNIEnv *env, jobject thiz, jlong handle) {
    auto* ctx = reinterpret_cast<MNNContext*>(handle);
    if (ctx) {
        if (ctx->interpreter && ctx->session) {
            ctx->interpreter->releaseSession(ctx->session);
        }
        delete ctx;
        LOGI("MNN engine released");
    }
}

} // extern "C"
