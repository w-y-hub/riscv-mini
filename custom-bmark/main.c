#define N 16

void matrix_mul(int *A, int *B, int *C);

void exit(int code) {
    while (1)
        __asm__ __volatile__("csrw 0x780, %0" : : "r"((code << 1) | 1));
}

int main() {
    int A[N * N];
    int B[N * N];
    int C[N * N];

    // 初始化：A[i]=1, B[i]=1（全 1 矩阵）
    for (int i = 0; i < N * N; i++) {
        A[i] = 1;
        B[i] = 1;
        C[i] = 0;
    }

    // 矩阵乘法 C = A × B
    matrix_mul(A, B, C);

    // 全 1 矩阵相乘：C[i][j] = N，所有元素之和 = N³
    int sum = 0;
    for (int i = 0; i < N * N; i++)
        sum += C[i];

    // 预期：sum = 16 × 16 × 16 = 4096
    if (sum != N * N * N)
        exit(-1);

    exit(0);
}
