#!/bin/bash
# ============================================================
# AutoAim - 无畏契约手游 一键构建脚本
# 天玑9300+ 专属优化版
# ============================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}================================================${NC}"
echo -e "${BLUE}  AutoAim - 无畏契约手游 构建工具${NC}"
echo -e "${BLUE}  天玑9300+ (APU 790) 专属优化${NC}"
echo -e "${BLUE}================================================${NC}"
echo ""

# ============================================================
# Step 1: 检查环境
# ============================================================
echo -e "${YELLOW}[1/6] 检查构建环境...${NC}"

check_cmd() {
    if command -v "$1" &>/dev/null; then
        echo -e "  ${GREEN}✓${NC} $1: $(command -v $1)"
        return 0
    else
        echo -e "  ${RED}✗${NC} $1: 未安装"
        return 1
    fi
}

MISSING=0
check_cmd java    || MISSING=1
check_cmd adb     || MISSING=1

# 检查 ANDROID_HOME
if [ -n "$ANDROID_HOME" ]; then
    echo -e "  ${GREEN}✓${NC} ANDROID_HOME: $ANDROID_HOME"
elif [ -n "$ANDROID_SDK_ROOT" ]; then
    ANDROID_HOME="$ANDROID_SDK_ROOT"
    echo -e "  ${GREEN}✓${NC} ANDROID_SDK_ROOT: $ANDROID_HOME"
else
    echo -e "  ${RED}✗${NC} ANDROID_HOME 未设置"
    echo -e "    请设置: export ANDROID_HOME=/path/to/android/sdk"
    MISSING=1
fi

if [ $MISSING -eq 1 ]; then
    echo ""
    echo -e "${RED}缺少必要依赖，请先安装:${NC}"
    echo "  - JDK 11+: https://adoptium.net/"
    echo "  - Android SDK: https://developer.android.com/studio"
    echo "  - 设置 ANDROID_HOME 环境变量"
    exit 1
fi

echo ""

# ============================================================
# Step 2: 下载 MNN SDK
# ============================================================
echo -e "${YELLOW}[2/6] 检查 MNN SDK...${NC}"

MNN_DIR="app/src/main/cpp/third_party/MNN"
MNN_VERSION="2.9.0"

if [ -d "$MNN_DIR" ]; then
    echo -e "  ${GREEN}✓${NC} MNN SDK 已存在"
else
    echo -e "  ${YELLOW}↓${NC} 下载 MNN SDK v${MNN_VERSION}..."

    mkdir -p "$MNN_DIR"

    # 下载 MNN AAR
    MNN_URL="https://github.com/alibaba/MNN/releases/download/${MNN_VERSION}/MNN-${MNN_VERSION}-android.aar"
    echo "  下载: $MNN_URL"

    if command -v wget &>/dev/null; then
        wget -q --show-progress -O /tmp/mnn.aar "$MNN_URL" || {
            echo -e "  ${YELLOW}⚠ 自动下载失败，请手动下载 MNN AAR:${NC}"
            echo "    $MNN_URL"
            echo "    解压到: $MNN_DIR/"
        }
    elif command -v curl &>/dev/null; then
        curl -L -o /tmp/mnn.aar "$MNN_URL" || {
            echo -e "  ${YELLOW}⚠ 自动下载失败，请手动下载${NC}"
        }
    fi

    # 解压 AAR
    if [ -f /tmp/mnn.aar ]; then
        mkdir -p /tmp/mnn_extract
        unzip -q -o /tmp/mnn.aar -d /tmp/mnn_extract

        # 复制头文件
        mkdir -p "$MNN_DIR/include/MNN"
        cp -r /tmp/mnn_extract/jni/include/* "$MNN_DIR/include/" 2>/dev/null || true

        # 复制 so 库
        for abi in arm64-v8a armeabi-v7a; do
            mkdir -p "$MNN_DIR/libs/$abi"
            cp /tmp/mnn_extract/jni/$abi/*.so "$MNN_DIR/libs/$abi/" 2>/dev/null || true
        done

        rm -rf /tmp/mnn.aar /tmp/mnn_extract
        echo -e "  ${GREEN}✓${NC} MNN SDK 安装完成"
    fi
fi
echo ""

# ============================================================
# Step 3: 下载 YOLOv8n 预训练模型
# ============================================================
echo -e "${YELLOW}[3/6] 检查 YOLOv8n 模型...${NC}"

if [ -f "yolov8n.pt" ]; then
    echo -e "  ${GREEN}✓${NC} yolov8n.pt 已存在"
else
    echo -e "  ${YELLOW}↓${NC} 下载 YOLOv8n 预训练权重..."
    if command -v wget &>/dev/null; then
        wget -q --show-progress -O yolov8n.pt \
            "https://github.com/ultralytics/assets/releases/download/v8.2.0/yolov8n.pt" || \
            echo -e "  ${YELLOW}⚠ 下载失败，请手动下载${NC}"
    elif command -v curl &>/dev/null; then
        curl -L -o yolov8n.pt \
            "https://github.com/ultralytics/assets/releases/download/v8.2.0/yolov8n.pt" || \
            echo -e "  ${YELLOW}⚠ 下载失败，请手动下载${NC}"
    fi
    [ -f yolov8n.pt ] && echo -e "  ${GREEN}✓${NC} yolov8n.pt 下载完成"
fi
echo ""

# ============================================================
# Step 4: 转换模型 (如果有 Python 环境)
# ============================================================
echo -e "${YELLOW}[4/6] 模型转换...${NC}"

if command -v python3 &>/dev/null && python3 -c "import ultralytics" 2>/dev/null; then
    echo -e "  ${GREEN}✓${NC} Python + Ultralytics 可用"

    if [ -f "yolov8n.pt" ] && [ ! -f "app/src/main/assets/yolov8n_int8_192.mnn" ]; then
        echo -e "  ${YELLOW}↓${NC} 转换模型到 MNN 格式..."
        mkdir -p app/src/main/assets

        # 导出 ONNX
        python3 -c "
from ultralytics import YOLO
model = YOLO('yolov8n.pt')
model.export(format='onnx', imgsz=192, simplify=True)
print('✓ ONNX exported')
" || echo -e "  ${YELLOW}⚠ ONNX 导出失败${NC}"

        # 转 MNN (如果有 MNNConvert)
        if command -v MNNConvert &>/dev/null; then
            MNNConvert -f ONNX --modelFile yolov8n.onnx \
                --MNNModel app/src/main/assets/yolov8n_int8_192.mnn \
                --quantized --weightQuantBits 8
            echo -e "  ${GREEN}✓${NC} MNN 模型转换完成"
        elif python3 -c "import MNN" 2>/dev/null; then
            python3 tools/convert_model.py --input yolov8n.pt --size 192 --quant int8
            cp yolov8n_int8_192.mnn app/src/main/assets/
            echo -e "  ${GREEN}✓${NC} MNN 模型转换完成"
        else
            echo -e "  ${YELLOW}⚠ MNN 转换工具不可用，请手动转换${NC}"
            echo "    pip install MNN"
            echo "    python tools/convert_model.py --input yolov8n.pt --size 192 --quant int8"
            # 创建占位模型
            touch app/src/main/assets/yolov8n_int8_192.mnn.placeholder
        fi
    else
        echo -e "  ${GREEN}✓${NC} 模型已就绪"
    fi
else
    echo -e "  ${YELLOW}⚠ Python/Ultralytics 不可用，跳过模型转换${NC}"
    echo "    安装: pip install ultralytics MNN"
    echo "    然后运行: python tools/convert_model.py --input yolov8n.pt --size 192 --quant int8"

    mkdir -p app/src/main/assets
    if [ ! -f "app/src/main/assets/yolov8n_int8_192.mnn" ]; then
        touch app/src/main/assets/yolov8n_int8_192.mnn.placeholder
    fi
fi
echo ""

# ============================================================
# Step 5: 生成 Gradle Wrapper
# ============================================================
echo -e "${YELLOW}[5/6] 配置 Gradle...${NC}"

if [ ! -f "gradlew" ]; then
    echo -e "  ${YELLOW}↓${NC} 生成 Gradle Wrapper..."
    if command -v gradle &>/dev/null; then
        gradle wrapper --gradle-version 8.5
    else
        # 手动生成
        cat > gradlew << 'GRADLEW'
#!/bin/sh
# Gradle Wrapper
APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

DIRNAME=$(dirname "$0")
APP_HOME=$(cd "$DIRNAME" && pwd -P)

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

exec java $DEFAULT_JVM_OPTS \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
GRADLEW
        chmod +x gradlew
    fi
    echo -e "  ${GREEN}✓${NC} Gradle Wrapper 就绪"
else
    echo -e "  ${GREEN}✓${NC} Gradle Wrapper 已存在"
fi
echo ""

# ============================================================
# Step 6: 构建
# ============================================================
echo -e "${YELLOW}[6/6] 构建 APK...${NC}"
echo ""

# 检查是否有 debug keystore
if [ ! -f "$HOME/.android/debug.keystore" ]; then
    echo -e "  ${YELLOW}↓${NC} 生成 debug keystore..."
    mkdir -p "$HOME/.android"
    keytool -genkey -v -keystore "$HOME/.android/debug.keystore" \
        -storepass android -alias androiddebugkey -keypass android \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Debug, OU=Debug, O=Debug, L=Debug, S=Debug, C=US" 2>/dev/null || true
fi

# 构建
echo -e "  ${BLUE}→${NC} 运行: ./gradlew assembleDebug"
echo ""

./gradlew assembleDebug --no-daemon 2>&1 | while IFS= read -r line; do
    if [[ "$line" == *"BUILD SUCCESSFUL"* ]]; then
        echo -e "  ${GREEN}✓ 构建成功!${NC}"
    elif [[ "$line" == *"BUILD FAILED"* ]]; then
        echo -e "  ${RED}✗ 构建失败${NC}"
    elif [[ "$line" == *"FAILURE"* ]]; then
        echo -e "  ${RED}$line${NC}"
    fi
    echo "$line"
done

echo ""

# ============================================================
# 结果
# ============================================================
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
    SIZE=$(du -h "$APK_PATH" | cut -f1)
    echo -e "${GREEN}================================================${NC}"
    echo -e "${GREEN}  ✓ 构建完成!${NC}"
    echo -e "${GREEN}================================================${NC}"
    echo ""
    echo -e "  APK: ${BLUE}$APK_PATH${NC} ($SIZE)"
    echo ""
    echo -e "  ${YELLOW}安装到手机:${NC}"
    echo -e "    adb install $APK_PATH"
    echo ""
    echo -e "  ${YELLOW}使用步骤:${NC}"
    echo "    1. 安装 Shizuku (https://shizuku.rikka.app/)"
    echo "    2. 通过无线调试启动 Shizuku"
    echo "    3. 授权 AutoAim 的 Shizuku 权限"
    echo "    4. 打开 AutoAim，选择武器和设置"
    echo "    5. 点击启动，切换到无畏契约手游"
    echo "    6. 游戏中按住开火键触发自瞄"
    echo ""
else
    echo -e "${RED}================================================${NC}"
    echo -e "${RED}  ✗ 构建失败${NC}"
    echo -e "${RED}================================================${NC}"
    echo ""
    echo "  请检查上方错误信息"
    echo "  常见问题："
    echo "    - Android SDK 未安装: sdkmanager --install 'platform-tools' 'platforms;android-34'"
    echo "    - JDK 版本不对: 需要 JDK 11+"
    echo "    - MNN SDK 缺失: 请手动下载放到 app/src/main/cpp/third_party/MNN/"
fi
