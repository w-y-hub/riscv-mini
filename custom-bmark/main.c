#define N 16
#define read_csr(reg) ({ \
    unsigned int __val; \
    asm volatile("csrr %0, %1" : "=r"(__val) : "i"(reg)); \
    __val; \
})
void matrix_mul(int *A, int *B, int *C);

void exit(int code) {
    while (1)
        __asm__ __volatile__("csrw 0x780, %0" : : "r"((code << 1) | 1));
}



unsigned long long read_cycles() {
    unsigned int hi, lo;
    do {
        hi = read_csr(0xC80);
        lo = read_csr(0xC00);
    } while (hi != read_csr(0xC80));
    return ((unsigned long long)hi << 32) | lo;
}

unsigned long long read_instret() {
    unsigned int hi, lo;
    do {
        hi = read_csr(0xC82);
        lo = read_csr(0xC02);
    } while (hi != read_csr(0xC82));
    return ((unsigned long long)hi << 32) | lo;
}

int main() {

    unsigned long long c0 = read_cycles();
    unsigned long long i0 = read_instret();

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

    unsigned long long c1 = read_cycles();
    unsigned long long i1 = read_instret();

    unsigned long long cycles  = c1 - c0;
    unsigned long long instret = i1 - i0;
    // CPI = cycles / instret（浮点除法）
    // 注意：为防止除法溢出，建议先转为 double
    exit(0);
}
