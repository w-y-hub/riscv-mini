OUTPUT_ARCH( "riscv" )
ENTRY( _start )

SECTIONS
{
  . = 0x00000200;
  .text : {
    *crt.o(.text)     /* 确保 crt.o 的 .text 在最前面 */
    *(.text)	
   }  
  .rodata : { *(.rodata) }
  .data : { *(.data) }
  .bss : { *(.bss) }
  _sp = 0x00010000;
}
