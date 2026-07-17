
.text
.align 2

# ============================================================
# Fibonacci 数列
# int fibonacci(int n)
# 返回第 n 个 Fibonacci 数 F(0)=0, F(1)=1
# 递归实现，包含 4 种指令类型：R-type, I-type, B-type, J-type
# ============================================================
.globl fibonacci
.type  fibonacci,@function

fibonacci:
    # 函数序言：保存返回地址和被调用者保存的寄存器
    addi  sp, sp, -16          # I-type: 分配栈空间
    sw    ra, 12(sp)           # S-type: 保存返回地址
    sw    s0, 8(sp)            # S-type: 保存 s0
    sw    s1, 4(sp)            # S-type: 保存 s1

    # 基础情况：if (n <= 1) return n
    li    t0, 1                # I-type: t0 = 1
    ble   a0, t0, fib_base     # B-type: 如果 n <= 1，跳转

    # 递归调用 fibonacci(n-1)
    mv    s0, a0               # I-type: 保存 n 到 s0
    addi  a0, a0, -1           # I-type: a0 = n - 1
    jal   fibonacci            # J-type: 递归调用
    mv    s1, a0               # I-type: s1 = fib(n-1)

    # 递归调用 fibonacci(n-2)
    addi  a0, s0, -2           # I-type: a0 = n - 2
    jal   fibonacci            # J-type: 递归调用

    # 合并结果：fib(n) = fib(n-1) + fib(n-2)
    add   a0, a0, s1           # R-type: a0 = fib(n-2) + fib(n-1)
    j     fib_done              # J-type: 跳转到结尾

fib_base:
    # n 本身已经是 0 或 1，直接作为返回值

fib_done:
    # 函数收尾：恢复寄存器并返回
    lw    ra, 12(sp)           # I-type: 恢复返回地址
    lw    s0, 8(sp)            # I-type: 恢复 s0
    lw    s1, 4(sp)            # I-type: 恢复 s1
    addi  sp, sp, 16           # I-type: 释放栈空间
    ret                        # I-type: 返回


# ============================================================
# 冒泡排序
# void bubble_sort(int* arr, int len)
# 对整型数组进行原地排序
# 包含 4 种指令类型：R-type, I-type, S-type, B-type, J-type
# ============================================================
.globl bubble_sort
.type  bubble_sort,@function

bubble_sort:
    # 函数序言
    addi  sp, sp, -24          # I-type: 分配栈空间
    sw    ra, 20(sp)           # S-type: 保存返回地址
    sw    s0, 16(sp)           # S-type: 保存 s0
    sw    s1, 12(sp)           # S-type: 保存 s1
    sw    s2, 8(sp)            # S-type: 保存 s2

    mv    s0, a0               # I-type: s0 = 数组基地址
    mv    s1, a1               # I-type: s1 = 数组长度
    li    s2, 0                # I-type: i = 0

outer_loop:
    # 外层循环：if (i >= len-1) 结束
    addi  t0, s1, -1           # I-type: t0 = len - 1
    bge   s2, t0, sort_done    # B-type: 如果 i >= len-1，结束

    li    t1, 0                # I-type: j = 0

inner_loop:
    # 内层循环：if (j >= len-i-1) 下一轮
    sub   t2, s1, s2           # R-type: t2 = len - i
    addi  t2, t2, -1           # I-type: t2 = len - i - 1
    bge   t1, t2, next_outer   # B-type: 如果 j >= len-i-1，下一轮

    # 加载 arr[j] 和 arr[j+1]
    slli  t3, t1, 2            # I-type: t3 = j * 4（字节偏移）
    add   t3, s0, t3           # R-type: t3 = &arr[j]
    lw    t4, 0(t3)            # I-type: t4 = arr[j]
    lw    t5, 4(t3)            # I-type: t5 = arr[j+1]

    # 比较：if (arr[j] <= arr[j+1]) 不交换
    ble   t4, t5, no_swap      # B-type: 如果 arr[j] <= arr[j+1]，跳过交换

    # 交换 arr[j] 和 arr[j+1]
    sw    t5, 0(t3)            # S-type: arr[j] = t5
    sw    t4, 4(t3)            # S-type: arr[j+1] = t4

no_swap:
    addi  t1, t1, 1            # I-type: j++
    j     inner_loop           # J-type: 继续内层循环

next_outer:
    addi  s2, s2, 1            # I-type: i++
    j     outer_loop           # J-type: 继续外层循环

sort_done:
    # 函数收尾
    lw    ra, 20(sp)           # I-type: 恢复返回地址
    lw    s0, 16(sp)           # I-type: 恢复 s0
    lw    s1, 12(sp)           # I-type: 恢复 s1
    lw    s2, 8(sp)            # I-type: 恢复 s2
    addi  sp, sp, 24           # I-type: 释放栈空间
    ret                        # I-type: 返回
