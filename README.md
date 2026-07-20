# riscv-mini 计算机系统综合实验

基于 UC Berkeley 开源的 [riscv-mini](https://github.com/ucb-bar/riscv-mini)（RV32I 三级流水线教学处理器），在 Chisel 硬件描述语言基础上进行项目实践。

## 项目结构

```
├── custom-bmark/               # 自定义裸机程序
│   ├── main.c                  # 主程序：矩阵乘法验证（16x16）
│   ├── matrix_mul.S            # 汇编矩阵乘法（仅 RV32I 指令集）
│   ├── crt.S                   # 启动代码（初始化 sp，调用 main）
│   ├── syscalls.c              # 简易 _exit 实现
│   ├── test.ld                 # 自定义链接脚本（入口 0x200）
│   └── Makefile                # 编译规则
├── src/main/scala/mini/        # Chisel 硬件源码
│   ├── Config.scala            # 配置（含 CacheConfig）
│   ├── Cache.scala             # I$/D$ Cache 实现
│   └── ...（Core/Datapath/Control 等 11 个模块）
├── src/main/cc/                # Verilator C++ 仿真环境
│   ├── top.cc                  # 仿真主循环（内存改为 64MB）
│   └── mm.cc / mm.h            # AXI 主存模型
├── tests/                      # 51 个标准 ISA 测试用例
├── run_all_tests.sh            # 自动化测试脚本
└── VTile                       # Verilator 仿真可执行文件
```

## 实验任务

### 必做题：自定义裸机程序与全流程验证

开发矩阵乘法裸机程序，走通 C/汇编 -> ELF -> hex -> Verilator 仿真 全流程。

- 汇编实现 16x16 矩阵乘法三重循环
- 仅使用 RV32I 指令（乘法用移位加法替代 mul）
- 通过 mtohost 返回退出码
- 仿真 29,529 cycles，结果正确

### 选做题 4：Cache 参数调优与性能分析（进行中）

修改 CacheConfig 参数，对比不同 Cache 配置对矩阵乘法性能的影响。

## 当前进展

| 任务 | 状态 |
|------|------|
| 环境搭建（工具链 + Verilator + 测试集） | 完成 |
| 51 个标准 ISA 测试全部通过 | 完成 |
| 入口点修复（_start 在 0x200） | 完成 |
| 矩阵乘法 benchmark（16x16，仅 RV32I） | 完成 |
| 自动化测试脚本 | 完成 |
| Git 仓库推送至 GitHub | 完成 |
| Cache 参数调优实验 | 进行中 |
| 指令级流水线分析 | 待完成 |

## 使用方式

```bash
# 编译自定义裸机程序
make -C custom-bmark

# 运行仿真
./VTile custom-bmark/main.hex

# 运行全部标准测试
./run_all_tests.sh
```
