.section .text
.globl _start
.type _start, @function
_start:
    la sp, _sp
    call main
1:  j 1b
