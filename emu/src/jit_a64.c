/*
 * An aarch64 translator for the QingKe core (CHG_JIT, aarch64 builds only).
 * The interpreter in rv32.c stays the reference and the fallback: the code
 * made here leaves the machine in exactly the state the interpreter would.
 *
 * What is translated: code that runs from flash, in blocks that start where
 * the interpreter has just taken a jump (the prefetcher restarted there, which
 * is the one state a block may assume) and that follow the instructions on
 * through not-taken conditional branches. A block ends at a JAL or JALR, at
 * an instruction left to the interpreter (CSRs, ecall, mret, wfi, atomics),
 * or at a size limit. Taken branches and jumps go straight to the next block
 * when it exists (links are patched in as blocks are made), or through a
 * table lookup for JALR.
 *
 * Timing is the interpreter's, instruction by instruction, with everything
 * that depends only on the code worked out here: the number of words the
 * prefetcher has to fetch, the cost, the cycle the instruction is checked
 * against the slice's end. What the program decides stays in registers:
 *
 *   x19 guest registers   x20 cycle        x21 prefetcher time   x22 slice end
 *   x23 JitS              x24 RAM          x25 flash             x26 divider ready
 *   x27 divider register  x28 block table  x29 RAM-code lines
 *
 * Loads and stores take the fast path for RAM inline and everything else
 * out of line: flash data loads (which cost the prefetcher a fetch) and the
 * bus's slow functions, which see the same arguments and the same cycle as
 * they do from the interpreter. Code in RAM is not translated; stores to a
 * 32-byte line that ever held decoded RAM code call back to drop those
 * decoded entries.
 *
 * Divides: the divider's timing is only generated once a divide has been
 * decoded anywhere (the blocks are then made again).
 *
 * The code is generated for aarch64 and run by the CPU, or, with
 * CHG_JIT_SIM, by the small checker in jit_sim.c on any other host.
 */
#ifdef CHG_JIT

#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <stddef.h>
#include <stdio.h>
#include "machine.h"

#ifndef CHG_JIT_SIM
#include <sys/mman.h>
#endif

#define FLASH_WORD 4
#define FLASH_LOAD_RAM8 37      /* eighths a flash load costs from RAM code, in all */
#define WIDX(a) ((uint32_t)(((a) & 0xffffu) >> 2))
#define WAIT_CYCLES 0x0ffff

unsigned long long chg_jit_retired, chg_jit_blocks, chg_jit_flushes, chg_jit_nld, chg_jit_nst, chg_jit_ninv;
int chg_jit = 1;
int chg_jit_div_pending, chg_jit_div_on;
uint8_t chg_jit_bad[0x8000];
uint8_t chg_jit_ramcode[CHG_RAM_SIZE / 32 + 2];

typedef struct JitS {
    uint64_t cyc, ft, stop, div_ready, div_rd, retired, link;     /* 0..48 */
    uint32_t pc, fw;                                              /* 56, 60 */
    void *regs, *ram, *flash, *table, *bitmap;                    /* 64..96 */
    void *fn_ld, *fn_st, *fn_inv;                                 /* 104..120 */
    ChgMachine *m;                                                /* 128 */
    uint32_t rst, frac;                                           /* 136, 140: recent-data bits | pair_open << 3, and the flash-load fraction */
    void *ramtab;                                                 /* 144 */
} JitS;

#define O_CYC 0
#define O_FT 8
#define O_STOP 16
#define O_DREADY 24
#define O_DRD 32
#define O_RET 40
#define O_LINK 48
#define O_PC 56
#define O_FW 60
#define O_REGS 64
#define O_RAM 72
#define O_FLASH 80
#define O_TAB 88
#define O_BM 96
#define O_FLD 104
#define O_FST 112
#define O_FINV 120
#define O_RST 136
#define O_FRAC 140
#define O_RTAB 144

#ifdef CHG_JIT_SIM
void a64sim_run(JitS *s, void *enter, void *entry);
#define SIM_LD ((void *)1)
#define SIM_ST ((void *)2)
#define SIM_INV ((void *)3)
#endif

/* ------------------------------------------------------------------------ */
/* Instruction encoders                                                      */
/* ------------------------------------------------------------------------ */

enum { CC_EQ = 0, CC_NE = 1, CC_HS = 2, CC_LO = 3, CC_HI = 8, CC_LS = 9, CC_GE = 10, CC_LT = 11 };

#define R_G 19
#define R_CYC 20
#define R_FT 21
#define R_STOP 22
#define R_S 23
#define R_RAM 24
#define R_FLASH 25
#define R_DREADY 26
#define R_DRD 27
#define R_RST 28
#define R_BM 29
#define ZR 31

static uint32_t e_movz(int sf, int rd, uint32_t imm, int hw) { return (uint32_t)sf << 31 | 0x52800000u | (uint32_t)hw << 21 | imm << 5 | (uint32_t)rd; }
static uint32_t e_movk(int rd, uint32_t imm, int hw) { return 0x72800000u | (uint32_t)hw << 21 | imm << 5 | (uint32_t)rd; }
static uint32_t e_movn(int rd, uint32_t imm, int hw) { return 0x12800000u | (uint32_t)hw << 21 | imm << 5 | (uint32_t)rd; }
static uint32_t e_addi(int sf, int rd, int rn, uint32_t imm, int sh) { return (uint32_t)sf << 31 | 0x11000000u | (uint32_t)sh << 22 | imm << 10 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_subi(int sf, int rd, int rn, uint32_t imm, int sh) { return (uint32_t)sf << 31 | 0x51000000u | (uint32_t)sh << 22 | imm << 10 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_subsi(int sf, int rd, int rn, uint32_t imm, int sh) { return (uint32_t)sf << 31 | 0x71000000u | (uint32_t)sh << 22 | imm << 10 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_addr(int sf, int rd, int rn, int rm, int amt) { return (uint32_t)sf << 31 | 0x0B000000u | (uint32_t)rm << 16 | (uint32_t)amt << 10 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_subr(int sf, int rd, int rn, int rm) { return (uint32_t)sf << 31 | 0x4B000000u | (uint32_t)rm << 16 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_subsr(int sf, int rd, int rn, int rm) { return (uint32_t)sf << 31 | 0x6B000000u | (uint32_t)rm << 16 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_and(int rd, int rn, int rm) { return 0x0A000000u | (uint32_t)rm << 16 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_orr(int rd, int rn, int rm) { return 0x2A000000u | (uint32_t)rm << 16 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_eor(int rd, int rn, int rm) { return 0x4A000000u | (uint32_t)rm << 16 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_movx(int rd, int rm) { return 0xAA0003E0u | (uint32_t)rm << 16 | (uint32_t)rd; }
static uint32_t e_lslv(int rd, int rn, int rm) { return 0x1AC02000u | (uint32_t)rm << 16 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_lsrv(int rd, int rn, int rm) { return 0x1AC02400u | (uint32_t)rm << 16 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_asrv(int rd, int rn, int rm) { return 0x1AC02800u | (uint32_t)rm << 16 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_ubfm(int sf, int rd, int rn, int immr, int imms) { return (uint32_t)sf << 31 | 0x53000000u | (uint32_t)sf << 22 | (uint32_t)immr << 16 | (uint32_t)imms << 10 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_sbfm(int sf, int rd, int rn, int immr, int imms) { return (uint32_t)sf << 31 | 0x13000000u | (uint32_t)sf << 22 | (uint32_t)immr << 16 | (uint32_t)imms << 10 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_mul(int sf, int rd, int rn, int rm) { return (uint32_t)sf << 31 | 0x1B007C00u | (uint32_t)rm << 16 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_msub(int rd, int rn, int rm, int ra) { return 0x1B008000u | (uint32_t)rm << 16 | (uint32_t)ra << 10 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_smull(int rd, int rn, int rm) { return 0x9B207C00u | (uint32_t)rm << 16 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_umull(int rd, int rn, int rm) { return 0x9BA07C00u | (uint32_t)rm << 16 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_sdiv(int rd, int rn, int rm) { return 0x1AC00C00u | (uint32_t)rm << 16 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_udiv(int rd, int rn, int rm) { return 0x1AC00800u | (uint32_t)rm << 16 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_csel(int sf, int rd, int rn, int rm, int cc) { return (uint32_t)sf << 31 | 0x1A800000u | (uint32_t)rm << 16 | (uint32_t)cc << 12 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_csinc(int rd, int rn, int rm, int cc) { return 0x1A800400u | (uint32_t)rm << 16 | (uint32_t)cc << 12 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_csinv(int rd, int rn, int rm, int cc) { return 0x5A800000u | (uint32_t)rm << 16 | (uint32_t)cc << 12 | (uint32_t)rn << 5 | (uint32_t)rd; }
/* immediate-offset loads and stores; off is in bytes */
static uint32_t e_ldrw(int rt, int rn, uint32_t off) { return 0xB9400000u | (off / 4) << 10 | (uint32_t)rn << 5 | (uint32_t)rt; }
static uint32_t e_strw(int rt, int rn, uint32_t off) { return 0xB9000000u | (off / 4) << 10 | (uint32_t)rn << 5 | (uint32_t)rt; }
static uint32_t e_ldrx(int rt, int rn, uint32_t off) { return 0xF9400000u | (off / 8) << 10 | (uint32_t)rn << 5 | (uint32_t)rt; }
static uint32_t e_strx(int rt, int rn, uint32_t off) { return 0xF9000000u | (off / 8) << 10 | (uint32_t)rn << 5 | (uint32_t)rt; }
/* register-offset (the offset register is a 64-bit index, unscaled) */
#define M_LDRB 0x38606800u
#define M_LDRH 0x78606800u
#define M_LDRW 0xB8606800u
#define M_LDRSB 0x38E06800u
#define M_LDRSH 0x78E06800u
#define M_STRB 0x38206800u
#define M_STRH 0x78206800u
#define M_STRW 0xB8206800u
static uint32_t e_memr(uint32_t base, int rt, int rn, int rm) { return base | (uint32_t)rm << 16 | (uint32_t)rn << 5 | (uint32_t)rt; }
static uint32_t e_stp_pre(int rt, int rt2, int rn, int off) { return 0xA9800000u | ((uint32_t)(off / 8) & 0x7f) << 15 | (uint32_t)rt2 << 10 | (uint32_t)rn << 5 | (uint32_t)rt; }
static uint32_t e_stp(int rt, int rt2, int rn, int off) { return 0xA9000000u | ((uint32_t)(off / 8) & 0x7f) << 15 | (uint32_t)rt2 << 10 | (uint32_t)rn << 5 | (uint32_t)rt; }
static uint32_t e_ldp(int rt, int rt2, int rn, int off) { return 0xA9400000u | ((uint32_t)(off / 8) & 0x7f) << 15 | (uint32_t)rt2 << 10 | (uint32_t)rn << 5 | (uint32_t)rt; }
static uint32_t e_ldp_post(int rt, int rt2, int rn, int off) { return 0xA8C00000u | ((uint32_t)(off / 8) & 0x7f) << 15 | (uint32_t)rt2 << 10 | (uint32_t)rn << 5 | (uint32_t)rt; }
static uint32_t e_b(void) { return 0x14000000u; }
static uint32_t e_bcond(int cc) { return 0x54000000u | (uint32_t)cc; }
static uint32_t e_orrsh(int rd, int rn, int rm, int amt) { return 0x2A000000u | (uint32_t)rm << 16 | (uint32_t)amt << 10 | (uint32_t)rn << 5 | (uint32_t)rd; }
static uint32_t e_cbnz(int rt) { return 0x35000000u | (uint32_t)rt; }
static uint32_t e_cbnz_x(int rt) { return 0xB5000000u | (uint32_t)rt; }
static uint32_t e_cbz_x(int rt) { return 0xB4000000u | (uint32_t)rt; }
static uint32_t e_br(int rn) { return 0xD61F0000u | (uint32_t)rn << 5; }
static uint32_t e_blr(int rn) { return 0xD63F0000u | (uint32_t)rn << 5; }
static uint32_t e_ret(void) { return 0xD65F03C0u; }
static uint32_t e_adr(int rd) { return 0x10000000u | (uint32_t)rd; }

/* a logical immediate: N:immr:imms for a 32-bit value, or 0 when it is not one */
static int bitmask32(uint32_t v, uint32_t *enc)
{
    if (v == 0 || v == 0xffffffffu) return 0;
    int size = 32;
    while (size > 2) {
        const int half = size / 2;
        const uint32_t mask = (1u << half) - 1;
        if (((v >> half) & mask) != (v & mask)) break;
        size = half;
    }
    const uint32_t emask = size == 32 ? 0xffffffffu : (1u << size) - 1;
    const uint32_t e = v & emask;
    const int n = __builtin_popcount(e);
    const uint32_t ones = n == 32 ? 0xffffffffu : (1u << n) - 1;
    for (int r = 0; r < size; r++) {
        /* ones rotated right by r within size bits */
        uint32_t rot = r == 0 ? ones : (((ones >> r) | (ones << (size - r))) & emask);
        if (rot == e) {
            const uint32_t imms = ((uint32_t)(n - 1) | ((~(uint32_t)(size - 1) << 1) & 0x3f)) & 0x3f;
            *enc = (uint32_t)r << 16 | imms << 10;     /* N is 0 for sizes up to 32 */
            return 1;
        }
    }
    return 0;
}
static uint32_t e_logi(uint32_t base, int rd, int rn, uint32_t enc) { return base | enc | (uint32_t)rn << 5 | (uint32_t)rd; }
#define L_AND 0x12000000u
#define L_ORR 0x32000000u
#define L_EOR 0x52000000u
#define L_ANDS 0x72000000u

/* ------------------------------------------------------------------------ */
/* Code buffer                                                               */
/* ------------------------------------------------------------------------ */

/* test hooks: JIT_FORCE_COLD sends every load and store down the out-of-line path, JIT_FORCE_INV
   every store through the decoded-code invalidation; the results must not change */
#ifdef JIT_FORCE_COLD
#define JIT_RAMBR e_b()
#else
#define JIT_RAMBR e_bcond(CC_HS)
#endif
#ifdef JIT_FORCE_INV
#define JIT_INVBR e_b()
#else
#define JIT_INVBR e_cbnz(14)
#endif

#define CODE_SIZE (16u << 20)
#define MAXI 96                 /* instructions in a block */

static int ramblk;              /* the block being made runs from RAM: its words are 1 cycle, and the data-port state matters */
static uint8_t *code_buf, *code_end_p;
static uint32_t *wp;            /* where the next block goes */
static uint32_t *cp;            /* the instruction being written */
static int wx_flip;             /* the buffer cannot be writable and executable at once */
static uint32_t *tramp_enter, *exit_common;
static uint8_t *tab[0x8000];                /* flash blocks by pc / 2 */
static uint8_t *ram_tab[CHG_RAM_SIZE / 2];  /* RAM blocks by offset / 2 */
static uint8_t trans_hw[CHG_RAM_SIZE / 16 + 1];   /* a bit per halfword of RAM that translated code was made from */
uint8_t chg_jit_bad_ram[CHG_RAM_SIZE / 2];
int chg_jit_rst;
static JitS S;
static unsigned epoch;
static int jit_div;
static ChgMachine *jit_m;
static int jit_state;           /* 0 not started, 1 ready, -1 failed */

static void code_begin(void)
{
#ifdef CHG_JIT_SIM
    (void)wx_flip;
#else
    if (wx_flip) mprotect(code_buf, CODE_SIZE, PROT_READ | PROT_WRITE);
#endif
}

static void code_end(void *from, void *to)
{
#ifndef CHG_JIT_SIM
    if (wx_flip) mprotect(code_buf, CODE_SIZE, PROT_READ | PROT_EXEC);
    __builtin___clear_cache((char *)from, (char *)to);
#else
    (void)from; (void)to;
#endif
}

static inline uint32_t *emit(uint32_t v) { *cp = v; return cp++; }
#define W(x) emit(x)

/* patch the branch at 'site' to reach 'target' (both instruction pointers) */
static void patch(uint32_t *site, uint32_t *target)
{
    const int64_t off = target - site;
    const uint32_t i = *site;
    if ((i & 0xFC000000u) == 0x14000000u) *site = (i & 0xFC000000u) | ((uint32_t)off & 0x03ffffffu);
    else if ((i & 0x9F000000u) == 0x10000000u) {
        const int64_t b = (int64_t)((uint8_t *)target - (uint8_t *)site);
        *site = (i & 0x9F00001Fu) | ((uint32_t)(b & 3) << 29) | ((((uint32_t)b >> 2) & 0x7ffff) << 5);
    } else *site = (i & 0xFF00001Fu) | (((uint32_t)off & 0x7ffffu) << 5);
}

static void mov32(int rd, uint32_t v)
{
    if ((v >> 16) == 0) W(e_movz(0, rd, v, 0));
    else if ((v & 0xffff) == 0) W(e_movz(0, rd, v >> 16, 1));
    else if ((v >> 16) == 0xffff) W(e_movn(rd, ~v & 0xffff, 0));
    else if ((v & 0xffff) == 0xffff) W(e_movn(rd, (~v >> 16) & 0xffff, 1));
    else { W(e_movz(0, rd, v & 0xffff, 0)); W(e_movk(rd, v >> 16, 1)); }
}

/* ------------------------------------------------------------------------ */
/* Helpers the generated code calls                                          */
/* ------------------------------------------------------------------------ */

/* The slow bus paths, as the interpreter calls them: the cycle is brought
   into m->cycles for the peripherals and comes back with what they added */
static uint32_t h_ld(JitS *s, uint32_t addr, uint32_t size, uint32_t pc)
{
    ChgMachine *m = s->m;
    int wait = 0;
    chg_jit_nld++;
    m->cycles = s->cyc;
    m->cpu.pc = pc;
    const uint32_t v = bus_read_slow(m, addr, (int)size, &wait);
    s->cyc = m->cycles + 2 + (uint64_t)(wait & WAIT_CYCLES);
    s->stop = m->stop_at;
    return v;
}

static void h_st(JitS *s, uint32_t addr, uint32_t val, uint32_t size, uint32_t pc)
{
    ChgMachine *m = s->m;
    int wait = 0;
    const unsigned e = epoch;
    chg_jit_nst++;
    m->cycles = s->cyc;
    m->cpu.pc = pc;
    bus_write_slow(m, addr, val, (int)size, &wait);
    s->cyc = m->cycles + 2 + (uint64_t)(wait & WAIT_CYCLES);
    s->stop = m->stop_at;
    /* flash was written under the code that is running: leave it at once */
    if (e != epoch) s->stop = 0;
}

static void h_inv(JitS *s, uint32_t off, uint32_t size)
{
    const unsigned e = epoch;
    chg_jit_ninv++;
    cpu_invalidate_ram(s->m, off, (int)size);
    if (e != epoch) s->stop = 0;     /* translated code was written: leave the block at once */
}

#ifdef CHG_JIT_SIM
uint32_t chg_sim_ld(void *s, uint32_t a, uint32_t b, uint32_t c) { return h_ld((JitS *)s, a, b, c); }
void chg_sim_st(void *s, uint32_t a, uint32_t b, uint32_t c, uint32_t d) { h_st((JitS *)s, a, b, c, d); }
void chg_sim_inv(void *s, uint32_t a, uint32_t b) { h_inv((JitS *)s, a, b); }
#endif

/* ------------------------------------------------------------------------ */
/* Start-up                                                                  */
/* ------------------------------------------------------------------------ */

static void gen_runtime(void)
{
    cp = wp;
    tramp_enter = cp;
    W(e_stp_pre(29, 30, 31, -96));
    W(e_stp(19, 20, 31, 16));
    W(e_stp(21, 22, 31, 32));
    W(e_stp(23, 24, 31, 48));
    W(e_stp(25, 26, 31, 64));
    W(e_stp(27, 28, 31, 80));
    W(e_movx(R_S, 0));
    W(e_ldrx(R_G, R_S, O_REGS));
    W(e_ldrx(R_CYC, R_S, O_CYC));
    W(e_ldrx(R_FT, R_S, O_FT));
    W(e_ldrx(R_STOP, R_S, O_STOP));
    W(e_ldrx(R_RAM, R_S, O_RAM));
    W(e_ldrx(R_FLASH, R_S, O_FLASH));
    W(e_ldrx(R_DREADY, R_S, O_DREADY));
    W(e_ldrx(R_DRD, R_S, O_DRD));
    W(e_ldrw(R_RST, R_S, O_RST));
    W(e_ldrx(R_BM, R_S, O_BM));
    W(e_br(1));
    /* w0 pc, w1 fw, w2 instructions retired, x3 link site or 0 */
    exit_common = cp;
    W(e_strw(0, R_S, O_PC));
    W(e_strw(1, R_S, O_FW));
    W(e_ldrx(4, R_S, O_RET));
    W(e_addr(1, 4, 4, 2, 0));
    W(e_strx(4, R_S, O_RET));
    W(e_strx(3, R_S, O_LINK));
    W(e_strx(R_CYC, R_S, O_CYC));
    W(e_strx(R_FT, R_S, O_FT));
    W(e_strx(R_DREADY, R_S, O_DREADY));
    W(e_strx(R_DRD, R_S, O_DRD));
    W(e_strw(R_RST, R_S, O_RST));
    W(e_ldp(19, 20, 31, 16));
    W(e_ldp(21, 22, 31, 32));
    W(e_ldp(23, 24, 31, 48));
    W(e_ldp(25, 26, 31, 64));
    W(e_ldp(27, 28, 31, 80));
    W(e_ldp_post(29, 30, 31, 96));
    W(e_ret());
    wp = cp;
}

static int jit_init(void)
{
#ifdef CHG_JIT_SIM
    code_buf = (uint8_t *)malloc(CODE_SIZE);
    if (!code_buf) return 0;
#else
    void *p = mmap(NULL, CODE_SIZE, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (p == MAP_FAILED) {
        p = mmap(NULL, CODE_SIZE, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (p == MAP_FAILED) return 0;
        wx_flip = 1;
    }
    code_buf = (uint8_t *)p;
#endif
    code_end_p = code_buf + CODE_SIZE;
    wp = (uint32_t *)code_buf;
    code_begin();
    uint32_t *from = wp;
    gen_runtime();
    code_end(from, wp);
    S.table = tab;
    S.ramtab = ram_tab;
    S.bitmap = chg_jit_ramcode;
#ifdef CHG_JIT_SIM
    S.fn_ld = SIM_LD; S.fn_st = SIM_ST; S.fn_inv = SIM_INV;
#else
    S.fn_ld = (void *)h_ld; S.fn_st = (void *)h_st; S.fn_inv = (void *)h_inv;
#endif
    return 1;
}

static uint32_t *first_block;   /* the end of the runtime, where blocks start */

void jit_flush(void)
{
    if (jit_state != 1) return;
    memset(tab, 0, sizeof tab);
    memset(ram_tab, 0, sizeof ram_tab);
    memset(chg_jit_bad, 0, sizeof chg_jit_bad);
    memset(chg_jit_bad_ram, 0, sizeof chg_jit_bad_ram);
    memset(trans_hw, 0, sizeof trans_hw);
    for (unsigned i = 0; i < sizeof chg_jit_ramcode; i++) chg_jit_ramcode[i] &= 1;
    wp = first_block;
    epoch++;
    chg_jit_flushes++;
}

/* a save state replaced the whole machine, decoded caches included: the blocks
   may describe other flash, and the lines that held RAM code are the new ones */
void jit_machine_replaced(ChgMachine *m)
{
    jit_flush();
    memset(chg_jit_ramcode, 0, sizeof chg_jit_ramcode);
    for (uint32_t i = 0; i < CHG_RAM_SIZE / 2; i++)
        if (m->dc_ram[i].op) jit_mark_ram(i * 2);
}

/* ------------------------------------------------------------------------ */
/* Translation                                                               */
/* ------------------------------------------------------------------------ */

enum { K_LD, K_ST, K_INV, K_LINK, K_STOP, K_JMISS, K_JRAM };

typedef struct {
    int kind;
    uint32_t *br;           /* the branch to point at this stub */
    uint32_t *ret;          /* where it resumes */
    uint32_t *site;         /* K_LINK: the b to patch when the target exists */
    uint32_t pc, fw, cnt;   /* exit state, or pc of a memory access */
    uint32_t target;
    int op, rd, rs2, size;
} Cold;

#define MAXCOLD (MAXI * 3 + 8)
static Cold cold[MAXCOLD];
static int ncold;

static Cold *add_cold(int kind, uint32_t *br)
{
    Cold *c = &cold[ncold++];
    memset(c, 0, sizeof *c);
    c->kind = kind;
    c->br = br;
    return c;
}

/* a guest register as an operand: x0 is the zero register, others are loaded */
static int src(int t, int r)
{
    if (r == 0) return ZR;
    W(e_ldrw(t, R_G, (uint32_t)r * 4));
    return t;
}
/* ...and one that must be a real register (immediate forms read ZR as SP) */
static int srcr(int t, int r)
{
    if (r == 0) { W(e_movz(0, t, 0, 0)); return t; }
    W(e_ldrw(t, R_G, (uint32_t)r * 4));
    return t;
}
static void dst(int rd, int reg)
{
    if (rd != 32) W(e_strw(reg, R_G, (uint32_t)rd * 4));
}

static void add_const(int rd, int rn, int32_t imm)   /* w rd = w rn + imm */
{
    if (imm == 0) { if (rd != rn) W(e_addi(0, rd, rn, 0, 0)); return; }
    if (imm > 0 && imm < 4096) W(e_addi(0, rd, rn, (uint32_t)imm, 0));
    else if (imm < 0 && imm > -4096) W(e_subi(0, rd, rn, (uint32_t)-imm, 0));
    else { mov32(12, (uint32_t)imm); W(e_addr(0, rd, rn, 12, 0)); }
}

static void fixup_here(uint32_t *site) { patch(site, cp); }

/* the instruction's wait for its words, and for a result the divider owes it */
static void emit_fetch(uint32_t *fw, const ChgInsn *d)
{
    const int n = (int32_t)(d->wend - *fw);
    for (int i = 0; i < n; i++) {
        W(e_addi(1, R_FT, R_FT, ramblk ? 1 : FLASH_WORD, 0));
        W(e_subi(1, 15, R_CYC, ramblk ? 1 : FLASH_WORD, 0));
        W(e_subsr(1, ZR, R_FT, 15));
        W(e_csel(1, R_FT, R_FT, 15, CC_HS));
    }
    if (n > 0) *fw = d->wend;
    W(e_subsr(1, ZR, R_FT, R_CYC));
    W(e_csel(1, R_CYC, R_FT, R_CYC, CC_HI));
    if (jit_div && (d->rs1 || d->rs2)) {
        W(e_subsr(1, ZR, R_DREADY, R_CYC));
        uint32_t *skip1 = W(e_bcond(CC_LS));
        uint32_t *skip2 = NULL, *w = NULL;
        if (d->rs1 && d->rs2) {
            W(e_subsi(0, ZR, R_DRD, d->rs1, 0));
            w = W(e_bcond(CC_EQ));
            W(e_subsi(0, ZR, R_DRD, d->rs2, 0));
            skip2 = W(e_bcond(CC_NE));
            fixup_here(w);
        } else {
            W(e_subsi(0, ZR, R_DRD, d->rs1 ? d->rs1 : d->rs2, 0));
            skip2 = W(e_bcond(CC_NE));
        }
        W(e_movx(R_CYC, R_DREADY));
        fixup_here(skip1);
        fixup_here(skip2);
    }
}

/* the address of a load or store, in w9 */
static void emit_addr(int rs1, int32_t imm)
{
    if (rs1 == 0) mov32(9, (uint32_t)imm);
    else {
        W(e_ldrw(9, R_G, (uint32_t)rs1 * 4));
        add_const(9, 9, imm);
    }
}

/* the sequence that ends a taken jump or branch: the prefetcher restarts at
   the target once the word in flight is in */
static void emit_taken_timing(void)
{
    W(e_addi(1, R_FT, R_FT, ramblk ? 1 : FLASH_WORD, 0));
    W(e_subsr(1, ZR, R_FT, R_CYC));
    W(e_csel(1, R_FT, R_FT, R_CYC, CC_HI));
    if (ramblk) {
        /* a data access in the two instructions before hides a cycle of the bubble */
        uint32_t enc;
        bitmask32(3, &enc);
        W(e_logi(L_ANDS, ZR, R_RST, enc));
        W(e_csinc(13, ZR, ZR, CC_EQ));
        W(e_subr(1, R_FT, R_FT, 13));
    }
}

/* RAM code keeps the last three instructions' data accesses (bits 0-2) and whether a
   store opened a pair (bit 3) in w28: an instruction without data shifts a 0 in */
static void ram_shift(void)
{
    if (ramblk) W(e_ubfm(0, R_RST, R_RST, 31, 1));      /* ubfiz w28, w28, #1, #2 */
}

static void emit_exit_sequence(uint32_t pc, uint32_t fw, uint32_t cnt)
{
    mov32(0, pc);
    mov32(1, fw);
    mov32(2, cnt);
    W(e_movz(1, 3, 0, 0));
    uint32_t *b = W(e_b());
    patch(b, exit_common);
}

static int supported(int op)
{
    switch (op) {
    case OP_LR: case OP_SC:
    case OP_AMOSWAP: case OP_AMOADD: case OP_AMOXOR: case OP_AMOAND: case OP_AMOOR:
    case OP_AMOMIN: case OP_AMOMAX: case OP_AMOMINU: case OP_AMOMAXU:
    case OP_ECALL: case OP_EBREAK: case OP_MRET: case OP_WFI:
    case OP_CSRRW: case OP_CSRRS: case OP_CSRRC: case OP_CSRRWI: case OP_CSRRSI: case OP_CSRRCI:
    case OP_ILLEGAL: case OP_NONE:
        return 0;
    }
    return 1;
}

/* a jump to a fixed target: straight into its block if there is one, else
   through a stub that asks for it and is patched later */
static void emit_goto(uint32_t target, uint32_t cnt)
{
    const uint32_t gcnt = cnt;
    uint8_t *e = NULL;
    const uint32_t roff = target - CHG_RAM_BASE;
    if (target < 0xfffeu && !(target & 1)) {
        e = tab[target >> 1];
        if (ramblk) {
            /* flash blocks assume no data access in the instructions before: only enter with none */
            uint32_t *g = W(e_cbnz(R_RST));
            Cold *c = add_cold(K_STOP, g);
            c->pc = target; c->fw = WIDX(target) - 1; c->cnt = gcnt;
        }
    } else if (roff < CHG_RAM_SIZE - 2 && !(target & 1)) e = ram_tab[roff >> 1];
    uint32_t *b = W(e_b());
    if (e) {
        patch(b, (uint32_t *)e);
    } else {
        Cold *c = add_cold(K_LINK, b);
        c->site = b;
        c->target = target;
        c->cnt = cnt;
    }
}

static void emit_alu(const ChgInsn *d, uint32_t pc)
{
    const int rd = d->rd;
    /* a result nobody reads has no side effects, except the divider's busy time */
    if (rd == 32 && !(d->op >= OP_DIV && d->op <= OP_REMU)) return;
    const int32_t imm = d->imm;
    uint32_t enc;
    switch (d->op) {
    case OP_LUI: if (rd != 32) { mov32(11, (uint32_t)imm); dst(rd, 11); } return;
    case OP_AUIPC: mov32(11, pc + (uint32_t)imm); dst(rd, 11); return;
    case OP_ADDI:
        if (d->rs1 == 0) { mov32(11, (uint32_t)imm); dst(rd, 11); return; }
        W(e_ldrw(9, R_G, (uint32_t)d->rs1 * 4));
        add_const(11, 9, imm);
        dst(rd, 11);
        return;
    case OP_SLTI: case OP_SLTIU: {
        const int a = srcr(9, d->rs1);
        if (imm >= 0 && imm < 4096) W(e_subsi(0, ZR, a, (uint32_t)imm, 0));
        else { mov32(12, (uint32_t)imm); W(e_subsr(0, ZR, a, 12)); }
        W(e_csinc(11, ZR, ZR, d->op == OP_SLTI ? CC_GE : CC_HS));
        dst(rd, 11);
        return;
    }
    case OP_XORI: case OP_ORI: case OP_ANDI: {
        const int a = src(9, d->rs1);
        const uint32_t base = d->op == OP_XORI ? L_EOR : d->op == OP_ORI ? L_ORR : L_AND;
        if (bitmask32((uint32_t)imm, &enc) && a != ZR) W(e_logi(base, 11, a, enc));
        else {
            mov32(12, (uint32_t)imm);
            W((d->op == OP_XORI ? e_eor : d->op == OP_ORI ? e_orr : e_and)(11, a, 12));
        }
        dst(rd, 11);
        return;
    }
    case OP_SLLI: { const int a = srcr(9, d->rs1); W(e_ubfm(0, 11, a, (32 - imm) & 31, 31 - imm)); dst(rd, 11); return; }
    case OP_SRLI: { const int a = srcr(9, d->rs1); W(e_ubfm(0, 11, a, imm, 31)); dst(rd, 11); return; }
    case OP_SRAI: { const int a = srcr(9, d->rs1); W(e_sbfm(0, 11, a, imm, 31)); dst(rd, 11); return; }
    default: break;
    }
    const int a = src(9, d->rs1);
    const int b = src(10, d->rs2);
    switch (d->op) {
    case OP_ADD: W(e_addr(0, 11, a, b, 0)); break;
    case OP_SUB: W(e_subr(0, 11, a, b)); break;
    case OP_SLL: W(e_lslv(11, a, b)); break;
    case OP_SRL: W(e_lsrv(11, a, b)); break;
    case OP_SRA: W(e_asrv(11, a, b)); break;
    case OP_XOR: W(e_eor(11, a, b)); break;
    case OP_OR:  W(e_orr(11, a, b)); break;
    case OP_AND: W(e_and(11, a, b)); break;
    case OP_SLT:  W(e_subsr(0, ZR, a, b)); W(e_csinc(11, ZR, ZR, CC_GE)); break;
    case OP_SLTU: W(e_subsr(0, ZR, a, b)); W(e_csinc(11, ZR, ZR, CC_HS)); break;
    case OP_MUL: W(e_mul(0, 11, a, b)); break;
    case OP_MULH: W(e_smull(11, a, b)); W(e_ubfm(1, 11, 11, 32, 63)); break;
    case OP_MULHU: W(e_umull(11, a, b)); W(e_ubfm(1, 11, 11, 32, 63)); break;
    case OP_MULHSU:
        /* signed a times unsigned b: a sign-extended, b as the zero-extended w register */
        W(e_sbfm(1, 9, a, 0, 31));
        W(e_mul(1, 11, 9, b));
        W(e_sbfm(1, 11, 11, 32, 63));
        break;
    case OP_DIV: case OP_DIVU: case OP_REM: case OP_REMU:
        /* the divider: it waits for its previous result and is busy 10 cycles */
        W(e_subsr(1, ZR, R_CYC, R_DREADY));
        W(e_csel(1, R_CYC, R_DREADY, R_CYC, CC_LO));
        W(e_addi(1, R_DREADY, R_CYC, 10, 0));
        W(e_movz(0, R_DRD, (uint32_t)rd, 0));
        if (d->op == OP_DIV || d->op == OP_DIVU) {
            W((d->op == OP_DIV ? e_sdiv : e_udiv)(11, a, b));
            W(e_subsr(0, ZR, b, ZR));
            W(e_csinv(11, 11, ZR, CC_NE));
        } else {
            W((d->op == OP_REM ? e_sdiv : e_udiv)(12, a, b));
            W(e_msub(11, 12, b, a));
        }
        break;
    default: return;
    }
    dst(rd, 11);
}

static uint8_t **slotp(uint32_t pc, int ram) { return ram ? &ram_tab[(pc - CHG_RAM_BASE) >> 1] : &tab[pc >> 1]; }
static uint8_t *badp(uint32_t pc, int ram) { return ram ? &chg_jit_bad_ram[(pc - CHG_RAM_BASE) >> 1] : &chg_jit_bad[pc >> 1]; }

/* 1 flash, 2 RAM, 0 neither: where a block may start */
static int region(uint32_t pc)
{
    if (pc & 1) return 0;
    if (pc < 0xfffeu) return 1;
    if (pc - CHG_RAM_BASE < CHG_RAM_SIZE - 2) return 2;
    return 0;
}

static uint32_t ram_br(void)
{
#ifdef JIT_FORCE_COLD
    if (!ramblk) return e_b();      /* (in RAM code the slow path costs differently: only flash code is forced) */
#endif
    return e_bcond(CC_HS);
}

/* RAM that translated code was made from has been written: it goes, all of it */
void jit_ram_stored(uint32_t off, int size)
{
    for (uint32_t h = off >> 1; h <= (off + (uint32_t)size - 1) >> 1 && h < CHG_RAM_SIZE / 2; h++)
        if (trans_hw[h >> 3] >> (h & 7) & 1) {
            jit_flush();
            return;
        }
}

static void mark_translated(uint32_t off, int len)
{
    jit_mark_ram(off);
    chg_jit_ramcode[off >= 3 ? (off - 3) >> 5 : 0] |= 2;
    chg_jit_ramcode[(off + 3) >> 5] |= 2;
    for (uint32_t h = off >> 1; h <= (off + (uint32_t)len - 1) >> 1; h++)
        trans_hw[h >> 3] |= (uint8_t)(1u << (h & 7));
}

static void emit_jmiss(uint32_t cnt)
{
    W(e_orr(0, ZR, 9));
    W(e_ubfm(0, 1, 9, 2, 15));
    W(e_subi(0, 1, 1, 1, 0));
    mov32(2, cnt);
    W(e_movz(1, 3, 0, 0));
    uint32_t *b = W(e_b());
    patch(b, exit_common);
}

static uint32_t *translate(ChgMachine *m, uint32_t pc0)
{
    if ((size_t)((uint8_t *)wp - code_buf) + 160 * 1024 > CODE_SIZE) jit_flush();
    uint32_t *start = wp;
    ramblk = region(pc0) == 2;
    code_begin();
    cp = start;
    ncold = 0;
    *slotp(pc0, ramblk) = (uint8_t *)start;

    uint32_t fw = WIDX(pc0) - 1;
    uint32_t pc = pc0;
    uint32_t cnt = 0;
    int ended = 0;

    while (!ended && cnt < MAXI) {
        ChgInsn d;
        uint32_t raw;
        if (region(pc) != (ramblk ? 2 : 1)) { emit_exit_sequence(pc, fw, cnt); ended = 1; break; }
        if (ramblk) memcpy(&raw, m->ram + (pc - CHG_RAM_BASE), 4);
        else memcpy(&raw, m->flash + pc, 4);
        cpu_decode(pc, raw, &d, !ramblk);
        if (chg_jit_div_pending) { *slotp(pc0, ramblk) = NULL; wp = start; code_end(start, start); return (uint32_t *)1; }
        if (!supported(d.op)) {
            if (cnt == 0) {
                *slotp(pc0, ramblk) = NULL;
                *badp(pc0, ramblk) = 1;
                code_end(start, start);
                return NULL;
            }
            emit_exit_sequence(pc, fw, cnt);
            ended = 1;
            break;
        }
        if (ramblk) mark_translated(pc - CHG_RAM_BASE, d.len);

        /* the slice's end is checked before every instruction */
        W(e_subsr(1, ZR, R_CYC, R_STOP));
        {
            uint32_t *b = W(e_bcond(CC_HS));
            Cold *c = add_cold(K_STOP, b);
            c->pc = pc; c->fw = fw; c->cnt = cnt;
        }
        emit_fetch(&fw, &d);

        const uint32_t cost = d.cost;
        const uint32_t npc = pc + d.len;
        switch (d.op) {
        case OP_FENCE:
            W(e_addi(1, R_CYC, R_CYC, 1, 0));
            ram_shift();
            break;

        case OP_JAL:
            W(e_addi(1, R_CYC, R_CYC, 1, 0));
            if (d.rd != 32) { mov32(11, npc); dst(d.rd, 11); }
            emit_taken_timing();
            ram_shift();
            emit_goto(pc + (uint32_t)d.imm, cnt + 1);
            ended = 1;
            break;

        case OP_JALR:
            W(e_addi(1, R_CYC, R_CYC, 1, 0));
            if (d.rs1 == 0) {
                if (d.rd != 32) { mov32(11, npc); dst(d.rd, 11); }
                emit_taken_timing();
                ram_shift();
                emit_goto((uint32_t)d.imm & ~1u, cnt + 1);
            } else {
                W(e_ldrw(9, R_G, (uint32_t)d.rs1 * 4));
                add_const(9, 9, d.imm);
                { uint32_t enc; bitmask32(0xfffffffeu, &enc); W(e_logi(L_AND, 9, 9, enc)); }
                if (d.rd != 32) { mov32(11, npc); dst(d.rd, 11); }
                emit_taken_timing();
                ram_shift();
                W(e_ubfm(0, 10, 9, 16, 31));    /* lsr w10, w9, 16 */
                uint32_t *far = W(e_cbnz(10));
                uint32_t *guard = NULL;
                if (ramblk) guard = W(e_cbnz(R_RST));       /* a flash block is entered with no data access behind */
                W(e_ldrx(13, R_S, O_TAB));
                W(e_addr(1, 10, 13, 9, 2 /* lsl 2: entries are 8 bytes, pc is even */));
                W(e_ldrx(11, 10, 0));
                uint32_t *miss = W(e_cbz_x(11));
                W(e_br(11));
                Cold *c1 = add_cold(K_JRAM, far);
                c1->cnt = cnt + 1;
                if (guard) { Cold *cg = add_cold(K_JMISS, guard); cg->cnt = cnt + 1; }
                Cold *c2 = add_cold(K_JMISS, miss);
                c2->cnt = cnt + 1;
            }
            ended = 1;
            break;

        case OP_BEQ: case OP_BNE: case OP_BLT: case OP_BGE: case OP_BLTU: case OP_BGEU: {
            W(e_addi(1, R_CYC, R_CYC, 1, 0));
            const int a = src(9, d.rs1);
            const int b = src(10, d.rs2);
            W(e_subsr(0, ZR, a, b));
            static const int inv[] = { [OP_BEQ] = CC_NE, [OP_BNE] = CC_EQ, [OP_BLT] = CC_GE, [OP_BGE] = CC_LT,
                                       [OP_BLTU] = CC_HS, [OP_BGEU] = CC_LO };
            uint32_t *skip = W(e_bcond(inv[d.op]));
            emit_taken_timing();
            ram_shift();
            emit_goto(pc + (uint32_t)d.imm, cnt + 1);
            fixup_here(skip);
            ram_shift();
            break;
        }

        case OP_LB: case OP_LH: case OP_LW: case OP_LBU: case OP_LHU: {
            const int size = (d.op == OP_LB || d.op == OP_LBU) ? 1 : (d.op == OP_LH || d.op == OP_LHU) ? 2 : 4;
            emit_addr(d.rs1, d.imm);
            { uint32_t enc; bitmask32(0x20000000u, &enc); W(e_logi(L_EOR, 10, 9, enc)); }
            if (size > 1) { W(e_addi(1, 11, 10, (uint32_t)size - 1, 0)); W(e_subsi(1, ZR, 11, 5, 1)); }
            else W(e_subsi(1, ZR, 10, 5, 1));
            uint32_t *br = W(ram_br());
            static const uint32_t ldop[] = { [OP_LB] = M_LDRSB, [OP_LH] = M_LDRSH, [OP_LW] = M_LDRW, [OP_LBU] = M_LDRB, [OP_LHU] = M_LDRH };
            W(e_memr(ldop[d.op], 11, R_RAM, 10));
            dst(d.rd, 11);
            W(e_addi(1, R_CYC, R_CYC, cost, 0));
            if (ramblk) {
                /* the SRAM port was the prefetcher's for a cycle, and this is a data access */
                uint32_t enc;
                W(e_addi(1, R_FT, R_FT, 1, 0));
                ram_shift();
                bitmask32(1, &enc);
                W(e_logi(L_ORR, R_RST, R_RST, enc));
            }
            Cold *c = add_cold(K_LD, br);
            c->ret = cp;
            c->pc = pc; c->op = d.op; c->rd = d.rd; c->size = size;
            break;
        }

        case OP_SB: case OP_SH: case OP_SW: {
            const int size = d.op == OP_SB ? 1 : d.op == OP_SH ? 2 : 4;
            emit_addr(d.rs1, d.imm);
            int v = ZR;
            if (d.rs2) { W(e_ldrw(12, R_G, (uint32_t)d.rs2 * 4)); v = 12; }
            { uint32_t enc; bitmask32(0x20000000u, &enc); W(e_logi(L_EOR, 10, 9, enc)); }
            if (size > 1) { W(e_addi(1, 11, 10, (uint32_t)size - 1, 0)); W(e_subsi(1, ZR, 11, 5, 1)); }
            else W(e_subsi(1, ZR, 10, 5, 1));
            uint32_t *br = W(ram_br());
            W(e_memr(d.op == OP_SB ? M_STRB : d.op == OP_SH ? M_STRH : M_STRW, v, R_RAM, 10));
            /* RAM that held decoded or translated code: those entries must go */
            W(e_ubfm(1, 13, 10, 5, 63));            /* lsr x13, x10, 5 */
            W(e_memr(M_LDRB, 14, R_BM, 13));
            uint32_t *inv = W(JIT_INVBR);
            Cold *ci = add_cold(K_INV, inv);
            ci->ret = cp;
            ci->size = size;
            W(e_addi(1, R_CYC, R_CYC, cost, 0));
            if (ramblk) {
                /* back to back stores go through the SRAM port in pairs: 2 cycles, then 1 */
                uint32_t enc;
                W(e_ubfm(0, 13, R_RST, 3, 3));              /* w13 = pair_open */
                W(e_addi(1, R_FT, R_FT, 2, 0));
                W(e_subr(1, R_FT, R_FT, 13));
                ram_shift();
                bitmask32(1, &enc);
                W(e_logi(L_ORR, R_RST, R_RST, enc));
                bitmask32(1, &enc);
                W(e_logi(L_EOR, 13, 13, enc));
                W(e_orrsh(R_RST, R_RST, 13, 3));
            }
            Cold *c = add_cold(K_ST, br);
            c->ret = cp;
            c->pc = pc; c->rs2 = d.rs2; c->size = size;
            break;
        }

        default:
            emit_alu(&d, pc);
            W(e_addi(1, R_CYC, R_CYC, cost, 0));
            ram_shift();
            break;
        }
        pc = npc;
        cnt++;
    }
    if (!ended) emit_exit_sequence(pc, fw, cnt);

    /* the out-of-line parts */
    for (int i = 0; i < ncold; i++) {
        Cold *c = &cold[i];
        if (c->br) patch(c->br, cp);
        switch (c->kind) {
        case K_STOP:
            emit_exit_sequence(c->pc, c->fw, c->cnt);
            break;
        case K_LINK: {
            const uint32_t t = c->target;
            mov32(0, t);
            mov32(1, WIDX(t) - 1);
            mov32(2, c->cnt);
            uint32_t *a = W(e_adr(3));
            patch(a, c->site);
            uint32_t *b = W(e_b());
            patch(b, exit_common);
            break;
        }
        case K_JMISS:
            emit_jmiss(c->cnt);
            break;
        case K_JRAM: {
            /* a jump to an address outside flash: a RAM block, if there is one */
            uint32_t enc;
            bitmask32(0x20000000u, &enc);
            W(e_logi(L_EOR, 10, 9, enc));
            W(e_subsi(1, ZR, 10, 5, 1));
            uint32_t *ok = W(e_bcond(CC_LO));
            emit_jmiss(c->cnt);
            fixup_here(ok);
            W(e_ldrx(13, R_S, O_RTAB));
            W(e_addr(1, 10, 13, 10, 2));
            W(e_ldrx(11, 10, 0));
            uint32_t *go = W(e_cbnz_x(11));
            emit_jmiss(c->cnt);
            fixup_here(go);
            W(e_br(11));
            break;
        }
        case K_INV: {
            W(e_movx(0, R_S));
            W(e_orr(1, ZR, 10));
            mov32(2, (uint32_t)c->size);
            W(e_ldrx(16, R_S, O_FINV));
            W(e_blr(16));
            W(e_ldrx(R_STOP, R_S, O_STOP));
            uint32_t *b = W(e_b());
            patch(b, c->ret);
            break;
        }
        case K_LD: {
            static const uint32_t ldop[] = { [OP_LB] = M_LDRSB, [OP_LH] = M_LDRSH, [OP_LW] = M_LDRW, [OP_LBU] = M_LDRB, [OP_LHU] = M_LDRH };
            uint32_t enc;
            /* flash data */
            W(e_addi(1, 11, 9, (uint32_t)c->size - 1, 0));
            W(e_subsi(1, ZR, 11, 16, 1));
            uint32_t *slow = W(e_bcond(CC_HS));
            W(e_memr(ldop[c->op], 11, R_FLASH, 9));
            dst(c->rd, 11);
            if (!ramblk) {
                /* from flash code the prefetcher waits for it: the load costs 4 and the word time moves on */
                W(e_addi(1, R_CYC, R_CYC, 4, 0));
                W(e_addi(1, R_FT, R_FT, 4, 0));
            } else {
                /* from RAM code it costs 4 5/8 cycles, the eighths carried over */
                W(e_addi(1, R_CYC, R_CYC, 2, 0));
                W(e_ldrw(13, R_S, O_FRAC));
                W(e_addi(0, 13, 13, FLASH_LOAD_RAM8 - 16, 0));
                W(e_ubfm(0, 14, 13, 3, 31));
                W(e_addr(1, R_CYC, R_CYC, 14, 0));
                bitmask32(7, &enc);
                W(e_logi(L_AND, 13, 13, enc));
                W(e_strw(13, R_S, O_FRAC));
                ram_shift();
            }
            { uint32_t *b = W(e_b()); patch(b, c->ret); }
            fixup_here(slow);
            W(e_strx(R_CYC, R_S, O_CYC));
            W(e_movx(0, R_S));
            W(e_orr(1, ZR, 9));
            mov32(2, (uint32_t)c->size);
            mov32(3, c->pc);
            W(e_ldrx(16, R_S, O_FLD));
            W(e_blr(16));
            W(e_ldrx(R_CYC, R_S, O_CYC));
            W(e_ldrx(R_STOP, R_S, O_STOP));
            if (c->op == OP_LB) W(e_sbfm(0, 0, 0, 0, 7));
            else if (c->op == OP_LH) W(e_sbfm(0, 0, 0, 0, 15));
            dst(c->rd, 0);
            if (ramblk) {
                ram_shift();
                bitmask32(1, &enc);
                W(e_logi(L_ORR, R_RST, R_RST, enc));
            }
            { uint32_t *b = W(e_b()); patch(b, c->ret); }
            break;
        }
        case K_ST: {
            W(e_strx(R_CYC, R_S, O_CYC));
            W(e_movx(0, R_S));
            W(e_orr(1, ZR, 9));
            if (c->rs2) W(e_ldrw(2, R_G, (uint32_t)c->rs2 * 4)); else W(e_movz(0, 2, 0, 0));
            mov32(3, (uint32_t)c->size);
            mov32(4, c->pc);
            W(e_ldrx(16, R_S, O_FST));
            W(e_blr(16));
            W(e_ldrx(R_CYC, R_S, O_CYC));
            W(e_ldrx(R_STOP, R_S, O_STOP));
            ram_shift();
            uint32_t *b = W(e_b());
            patch(b, c->ret);
            break;
        }
        }
    }

    if (cnt == 0) {
        /* nothing came of it */
        *slotp(pc0, ramblk) = NULL;
        *badp(pc0, ramblk) = 1;
        code_end(start, start);
        return NULL;
    }
    code_end(start, cp);
    wp = cp;
    chg_jit_blocks++;
    return start;
}

/* ------------------------------------------------------------------------ */
/* Running                                                                   */
/* ------------------------------------------------------------------------ */

int jit_run(ChgMachine *m, int rst)
{
    if (jit_state == 0) {
        jit_state = jit_init() ? 1 : -1;
        if (jit_state == 1) first_block = wp;
    }
    chg_jit_rst = rst;
    if (jit_state != 1) { chg_jit = 0; return 0; }
    ChgCpu *c = &m->cpu;
    if (jit_m != m) { jit_flush(); jit_m = m; }
    S.m = m;
    S.regs = c->x;
    S.ram = m->ram;
    S.flash = m->flash;
    S.rst = (uint32_t)rst;
    S.frac = c->frac8;
    int ret = 0;

    for (;;) {
        if (chg_jit_div_pending) {
            jit_div = chg_jit_div_on = 1;
            chg_jit_div_pending = 0;
            jit_flush();
        }
        const uint32_t pc = c->pc;
        const int reg = region(pc);
        if (!reg || *badp(pc, reg == 2)) break;
        if (reg == 1 && S.rst != 0) break;          /* flash blocks are entered with no data access behind */
        uint8_t *e = *slotp(pc, reg == 2);
        if (!e) {
            e = (uint8_t *)translate(m, pc);
            if (e == (uint8_t *)1) continue;            /* a divide turned up: again with its timing */
            if (!e) break;
        }
        const uint64_t cyc0 = m->cycles;
        S.cyc = m->cycles;
        S.ft = c->fetch_time;
        S.stop = m->stop_at;
        S.div_ready = c->div_ready;
        S.div_rd = c->div_rd;
        S.retired = 0;
        S.link = 0;
        const unsigned ep = epoch;
#ifdef CHG_JIT_SIM
        a64sim_run(&S, tramp_enter, e);
#else
        ((void (*)(JitS *, void *))tramp_enter)(&S, e);
#endif
        m->cycles = S.cyc;
        c->pc = S.pc;
        c->fetch_word = S.fw;
        c->fetch_time = S.ft;
        c->div_ready = S.div_ready;
        c->div_rd = (uint8_t)S.div_rd;
        c->frac8 = (uint8_t)S.frac;
        m->instret += S.retired;
        chg_jit_retired += S.retired;

        if (S.link && ep == epoch) {
            /* the stub that brought us here asked for this block: make it and point the jump at it */
            const uint32_t t = S.pc;
            const int treg = region(t);
            if (treg && !*badp(t, treg == 2)) {
                uint8_t *tg = *slotp(t, treg == 2);
                if (!tg) {
                    tg = (uint8_t *)translate(m, t);
                    if (tg == (uint8_t *)1) tg = NULL;
                }
                if (tg && ep == epoch) {
                    code_begin();
                    patch((uint32_t *)S.link, (uint32_t *)tg);
                    code_end((void *)S.link, (uint8_t *)S.link + 4);
                }
            }
        }
        ret = 1;
        if (m->cycles >= m->stop_at || m->cycles == cyc0) break;   /* the slice is over, or a block made no progress */
        if (c->fetch_word != WIDX(c->pc) - 1) break;
    }
    chg_jit_rst = (int)S.rst;
    return ret;
}

#endif /* CHG_JIT */
