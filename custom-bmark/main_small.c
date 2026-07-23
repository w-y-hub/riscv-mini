#define N 4
#define read_csr(reg) ({ \
    unsigned int __val; \
    asm volatile("csrr %0, %1" : "=r"(__val) : "i"(reg)); \
    __val; \
})
void matrix_mul(int *A, int *B, int *C);

 void exit(int code) {
    unsigned int u = (unsigned int)code;   // ← 加这行
    while (1)
      __asm__ __volatile__("csrw 0x780, %0" : : "r"((u << 1) | 1));
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

  static int A[N * N];
  static int B[N * N];
  static int C[N * N];
 
  int main() {
      for (int i = 0; i < N * N; i++) {
          A[i] = 1;
          B[i] = 1;
          C[i] = 1;
      }
      matrix_mul(A, B, C);
      exit(0);
 }