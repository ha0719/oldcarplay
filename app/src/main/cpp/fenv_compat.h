/*
 * Android 4.4（API 19）32 位 ARM 的 libm 里没有 fesetround / fegetround（API 21 才加入），
 * 官方 QuickJS 库在车机上因此报：cannot locate symbol "fesetround"。
 * 这里用 ARM VFP 的 FPSCR 寄存器自己实现，编译 QuickJS 的 C 代码时通过 -include 强制引入。
 * 舍入模式位在 FPSCR[23:22]，取值与 bionic 一致：
 *   FE_TONEAREST=0, FE_UPWARD=1, FE_DOWNWARD=2, FE_TOWARDZERO=3
 */
#ifndef LX_FENV_COMPAT_H
#define LX_FENV_COMPAT_H

#include <fenv.h>

#if defined(__ANDROID__) && defined(__arm__) && !defined(__aarch64__) && (__ANDROID_API__ < 21)

static inline int lx_compat_fegetround(void) {
    unsigned int fpscr;
    __asm__ __volatile__("vmrs %0, fpscr" : "=r"(fpscr));
    return (int) ((fpscr >> 22) & 0x3u);
}

static inline int lx_compat_fesetround(int round) {
    unsigned int fpscr;
    if (round & ~0x3) return -1;
    __asm__ __volatile__("vmrs %0, fpscr" : "=r"(fpscr));
    fpscr = (fpscr & ~(0x3u << 22)) | ((unsigned int) round << 22);
    __asm__ __volatile__("vmsr fpscr, %0" : : "r"(fpscr));
    return 0;
}

#define fegetround lx_compat_fegetround
#define fesetround lx_compat_fesetround

#endif
#endif
