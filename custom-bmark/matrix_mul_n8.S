.text
.align 2

# ============================================================
# 矩阵乘法（仅 RV32I 指令）
#
# void matrix_mul(int *A, int *B, int *C)
#
# C = A × B
# N = 16（矩阵为 16×16）
#
# 仅使用 RV32I 指令集：
#   R-type: add, sub, sltu, srl, and
#   I-type: addi, lw, sw, slli, srli, andi
#   B-type: beq, bne, bltu
#   J-type: jal
# ============================================================

.equ N, 8
.equ N_LOG2, 3
.equ ROW_STRIDE, 32

.globl matrix_mul
.type  matrix_mul,@function

matrix_mul:
    # 保存被调用者保存的寄存器
    addi  sp, sp, -24
    sw    ra, 20(sp)
    sw    s0, 16(sp)
    sw    s1, 12(sp)
    sw    s2,  8(sp)
    sw    s3,  4(sp)
    sw    s4,  0(sp)

    mv    s0, a0            # s0 = A 基地址
    mv    s1, a1            # s1 = B 基地址
    mv    s2, a2            # s2 = C 基地址

    li    s3, 0             # i = 0

.outer_loop_i:
    li    t0, N
    bge   s3, t0, .done     # if (i >= N) 结束

    li    s4, 0             # j = 0

.mid_loop_j:
    li    t0, N
    bge   s4, t0, .next_i   # if (j >= N) 下一行

    # sum = 0
    li    s5, 0             # s5 = 累加器（贯穿 k 循环）

    li    s6, 0             # k = 0

.inner_loop_k:
    li    t0, N
    bge   s6, t0, .store_c  # if (k >= N) 计算完毕，存结果

    # ---- 加载 A[i][k] ----
    # 地址 = A + i*64 + k*4
    slli  t1, s3, N_LOG2    # t1 = i * 16
    add   t1, t1, s6        # t1 = i*16 + k
    slli  t1, t1, 2         # t1 = (i*16 + k) * 4 (字节偏移)
    add   t1, s0, t1        # t1 = &A[i][k]
    lw    s7, 0(t1)         # s7 = A[i][k]

    # ---- 加载 B[k][j] ----
    # 地址 = B + k*64 + j*4
    slli  t2, s6, N_LOG2    # t2 = k * 16
    add   t2, t2, s4        # t2 = k*16 + j
    slli  t2, t2, 2         # t2 = (k*16 + j) * 4
    add   t2, s1, t2        # t2 = &B[k][j]
    lw    t3, 0(t2)         # t3 = B[k][j]

    # ---- 乘法：s7 * t3 → t4（移位加法）----
    # 算法：result = 0
    #       每轮检查乘数最低位，为 1 则累加被乘数
    #       被乘数左移 1，乘数右移 1
    #       重复直到乘数为 0
    li    t4, 0             # result = 0
    mv    t5, s7            # t5 = 被乘数（A[i][k]）
    mv    t6, t3            # t6 = 乘数（B[k][j]）

.mul_loop:
    beq   t6, zero, .mul_done  # 乘数为 0 则结束
    andi  t0, t6, 1            # 检查乘数最低位
    beq   t0, zero, .mul_shift # 该位为 0 则跳过累加
    add   t4, t4, t5           # 累加：result += 被乘数
.mul_shift:
    slli  t5, t5, 1            # 被乘数左移 1
    srli  t6, t6, 1            # 乘数右移 1
    j     .mul_loop
.mul_done:
    # t4 = s7 * t3（乘积）

    # ---- 累加到 sum ----
    add   s5, s5, t4        # sum += A[i][k] * B[k][j]

    addi  s6, s6, 1         # k++
    j     .inner_loop_k

.store_c:
    # ---- C[i][j] = sum ----
    # 地址 = C + i*64 + j*4
    slli  t0, s3, N_LOG2    # t0 = i * 16
    add   t0, t0, s4        # t0 = i*16 + j
    slli  t0, t0, 2         # t0 = (i*16 + j) * 4
    add   t0, s2, t0        # t0 = &C[i][j]
    sw    s5, 0(t0)         # C[i][j] = sum

    addi  s4, s4, 1         # j++
    j     .mid_loop_j

.next_i:
    addi  s3, s3, 1         # i++
    j     .outer_loop_i

.done:
    # 恢复寄存器并返回
    lw    s4,  0(sp)
    lw    s3,  4(sp)
    lw    s2,  8(sp)
    lw    s1, 12(sp)
    lw    s0, 16(sp)
    lw    ra, 20(sp)
    addi  sp, sp, 24
    ret
