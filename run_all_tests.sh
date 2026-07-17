#!/bin/bash
# 文件: run_all_tests.sh (放在 ~/riscv-mini 目录下)

# --- 配置 ---
VTILE="./VTile"
TEST_DIR="./tests"
LOG_DIR="./outputs"
PASS=0
FAIL=0
FAILED_TESTS=""

# 创建输出目录
mkdir -p "$LOG_DIR"

echo "=========================================="
echo "  RISC-V Mini 测试套件"
echo "=========================================="

# 遍历所有 hex 文件
for hex_file in "$TEST_DIR"/*.hex; do
    # 提取测试名（去掉路径和 .hex 后缀）
    test_name=$(basename "$hex_file" .hex)

    # 进度提示
    printf "  Running %-30s ... " "$test_name"

    # 运行仿真（不生成 VCD，stderr 重定向到日志）
    log_file="$LOG_DIR/${test_name}.log"
    $VTILE "$hex_file" 2> "$log_file"

    # 获取退出码
    exit_code=$?

    # 判断 PASS/FAIL 并输出
    if [ $exit_code -eq 0 ]; then
        echo "PASS"
        ((PASS++))
    else
        echo "FAIL (exit code: $exit_code)"
        FAILED_TESTS="$FAILED_TESTS $test_name"
        ((FAIL++))
    fi
done

# 输出汇总统计
echo "=========================================="
echo "  总计: $((PASS + FAIL))"
echo "  通过: $PASS"
echo "  失败: $FAIL"
if [ $FAIL -ne 0 ]; then
    echo "  失败列表:$FAILED_TESTS"
fi
echo "=========================================="

# 脚本退出码
if [ $FAIL -eq 0 ]; then
    exit 0
else
    exit 1
fi
