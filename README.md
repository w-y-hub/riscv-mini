# riscv-mini 计算机系统综合实验

基于 UC Berkeley 开源的 [riscv-mini](https://github.com/ucb-bar/riscv-mini)（RV32I 三级流水线教学处理器），在 Chisel 硬件描述语言基础上进行项目实践。

## 项目结构

```
├── custom-bmark/               # 自定义裸机程序（必做题）
│   ├── main.c                  # 主程序：Fibonacci + 冒泡排序验证
│   ├── fib.S                   # 汇编实现：递归 Fibonacci 和冒泡排序
│   ├── crt.S                   # 启动代码（初始化 sp，调用 main）
│   ├── syscalls.c              # 简易 _exit 实现
│   ├── test.ld                 # 自定义链接脚本（入口 0x200）
│   └── Makefile                # 编译规则
├── src/main/scala/mini/        # Chisel 硬件源码
│   ├── Config.scala            # 配置（含 CacheConfig）
│   ├── Cache.scala             # I$/D$ Cache 实现
│   ├── Core.scala / Datapath.scala / Control.scala  # 三级流水线
│   └── ...
├── src/main/cc/                # Verilator C++ 仿真环境
│   ├── top.cc                  # 仿真主循环
│   └── mm.cc / mm.h            # AXI 主存模型
├── tests/                      # 51 个标准 ISA 测试用例
├── run_all_tests.sh            # 自动化测试脚本
├── outputs/                    # 仿真输出日志
└── VTile                       # Verilator 编译的仿真可执行文件
```

## 实验任务

### 必做题：自定义裸机程序与全流程验证

**要求：**
1. 在 `custom-bmark/` 基础上开发具有实际功能的裸机程序（≥50 行有效 C 代码）
2. 理解 `link.ld` 内存布局与 `PC_START = 0x200` 的对应关系
3. 使用 `mtohost` 输出返回码，编写自动化比对脚本
4. 选取 2~3 条关键指令，结合反汇编与仿真波形解释流水线执行过程

**验收标准：** `make run-custom-bmark` 成功且结果可自动校验

### 选做题 4：Cache 参数调优与性能分析

**要求：**
1. 理解 Cache 容量、相联度、块大小等配置项
2. 选取 3~5 组不同 `CacheConfig` 参数，对 benchmark 运行仿真
3. 通过 CSR 的 `cycle`、`instret` 计算 CPI，分析 cache miss 影响
4. 撰写分析报告（参数变化 → miss 率 → CPI 因果关系）

**验收标准：** 至少 3 组参数对比数据表；报告含结论与调参建议

## 当前进展

### ✅ 已完成

| 任务 | 状态 |
|------|------|
| 环境搭建（工具链 + Verilator + 测试集） | ✅ |
| 51 个标准 ISA 测试全部通过 | ✅ |
| 自定义裸机程序：Fibonacci（递归汇编）+ 冒泡排序 | ✅ |
| 启动代码 crt.S / 链接脚本 test.ld | ✅ |
| mtohost 返回码输出 | ✅ |
| 入口点修复（_start 在 0x200） | ✅ |
| 编译问题修复（CSR 符号、libc 依赖） | ✅ |
| 仿真验证通过（970 cycles，exit 0） | ✅ |
| 自动化测试脚本 run_all_tests.sh | ✅ |
| Git 仓库配置并推送至 GitHub | ✅ |

### 🔄 进行中

| 任务 | 状态 |
|------|------|
| 指令级流水线分析（ADD/LW/BEQ） | ⏳ 待完成 |
| 选做题 4：Cache 参数调优实验 | ⏳ 待完成 |
| 答辩 PPT 与设计文档 | ⏳ 待完成 |

## 使用方式

```bash
# 编译自定义裸机程序
make -C custom-bmark

# 运行仿真
./VTile custom-bmark/main.hex

# 运行全部标准测试
./run_all_tests.sh
```
