int fibonacci(int n);
void bubble_sort(int* arr, int len);

void exit(int code) {
    while (1)
        __asm__ __volatile__("csrw 0x780, %0" : : "r"((code << 1) | 1));
}

int main(int argc, char** argv) {
    int result = 0;

    // 测试 Fibonacci
    int n = 10;
    int fib_n = fibonacci(n);
    if (fib_n != 55) {
        exit(-1);
    }

    // 测试冒泡排序
    int arr[5] = {5, 3, 4, 1, 2};
    bubble_sort(arr, 5);
    int expected[5] = {1, 2, 3, 4, 5};
    for (int i = 0; i < 5; i++) {
        if (arr[i] != expected[i]) {
            exit(-2);
        }
    }

    exit(0);
}
