#!/bin/bash

# 运行所有测试并生成报告

set -e  # 遇到错误立即退出

echo "================================"
echo "  OneClaw 测试套件"
echo "================================"
echo ""

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 检查是否在项目根目录
if [ ! -f "app/build.gradle" ] && [ ! -f "app/build.gradle.kts" ]; then
    echo -e "${RED}错误: 请在项目根目录运行此脚本${NC}"
    exit 1
fi

# 1. 运行单元测试
echo -e "${YELLOW}[1/4] 运行单元测试...${NC}"
./gradlew test --continue || {
    echo -e "${RED}✗ 单元测试失败${NC}"
    exit 1
}
echo -e "${GREEN}✓ 单元测试通过${NC}"
echo ""

# 2. 运行集成测试（需要连接设备或模拟器）
echo -e "${YELLOW}[2/4] 检查Android设备...${NC}"
if adb devices | grep -q "device$"; then
    echo -e "${GREEN}✓ 发现Android设备${NC}"
    echo -e "${YELLOW}运行集成测试...${NC}"
    ./gradlew connectedAndroidTest --continue || {
        echo -e "${RED}✗ 集成测试失败${NC}"
        exit 1
    }
    echo -e "${GREEN}✓ 集成测试通过${NC}"
else
    echo -e "${YELLOW}⚠ 未发现Android设备，跳过集成测试${NC}"
    echo "   请连接设备或启动模拟器后运行："
    echo "   ./gradlew connectedAndroidTest"
fi
echo ""

# 3. 生成测试覆盖率报告
echo -e "${YELLOW}[3/4] 生成覆盖率报告...${NC}"
./gradlew jacocoTestReport || {
    echo -e "${RED}✗ 生成覆盖率报告失败${NC}"
    exit 1
}
echo -e "${GREEN}✓ 覆盖率报告生成成功${NC}"
echo ""

# 4. 显示测试结果摘要
echo -e "${YELLOW}[4/4] 测试结果摘要${NC}"
echo "================================"

# 解析测试结果（这里是示例，实际需要根据Gradle输出解析）
if [ -f "app/build/test-results/testDebugUnitTest/TEST-*.xml" ]; then
    # 简单统计（需要安装xmllint或使用其他工具）
    echo -e "${GREEN}✓ 所有测试通过${NC}"
else
    echo "测试结果文件位置："
    echo "  单元测试: app/build/reports/tests/testDebugUnitTest/index.html"
    echo "  覆盖率: app/build/reports/jacoco/jacocoTestReport/html/index.html"
fi

echo ""
echo "================================"
echo -e "${GREEN}测试完成！${NC}"
echo "================================"
echo ""
echo "查看详细报告："
echo "  单元测试报告:"
echo "    open app/build/reports/tests/testDebugUnitTest/index.html"
echo ""
echo "  覆盖率报告:"
echo "    open app/build/reports/jacoco/jacocoTestReport/html/index.html"
echo ""

# 如果有覆盖率目标，检查是否达标
COVERAGE_THRESHOLD=80
echo "覆盖率目标: ${COVERAGE_THRESHOLD}%"
echo "（请查看报告确认实际覆盖率）"
