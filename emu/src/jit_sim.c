/*
 * A checker for the code jit_a64.c generates, for hosts that are not aarch64
 * (CHG_JIT_SIM): it decodes the A64 words from their encodings, the subset the
 * translator uses, and runs them. Anything else stops with a message, so an
 * encoding slip shows up as a crash here rather than as a wrong game on the
 * handheld. Not part of the shipped core.
 */
#ifdef CHG_JIT_SIM
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "machine.h"

/* the helpers' arguments are reached through the machine; the sim calls them by number */
uint32_t chg_sim_ld(void *s, uint32_t a, uint32_t b, uint32_t c);
void chg_sim_st(void *s, uint32_t a, uint32_t b, uint32_t c, uint32_t d);
void chg_sim_inv(void *s, uint32_t a, uint32_t b);

typedef struct {
    uint64_t x[32];
    uint64_t sp;
    uint64_t pc;
    int N, Z, C, V;
} Sim;

static uint64_t ror64(uint64_t v, int r, int size)
{
    const uint64_t mask = size == 64 ? ~0ull : ((1ull << size) - 1);
    v &= mask;
    r %= size;
    if (!r) return v;
    return ((v >> r) | (v << (size - r))) & mask;
}

/* DecodeBitMasks of the Arm ARM */
static void bitmasks(int N, int imms, int immr, int immediate, int datasize, uint64_t *wmask, uint64_t *tmask)
{
    const int x = (N << 6) | (~imms & 0x3f);
    int len = -1;
    for (int i = 6; i >= 0; i--) if (x >> i & 1) { len = i; break; }
    if (len < 1 && immediate) { fprintf(stderr, "sim: bad bitmask\n"); exit(2); }
    if (len < 0) { fprintf(stderr, "sim: bad bitmask\n"); exit(2); }
    const int levels = (1 << len) - 1;
    const int S = imms & levels, R = immr & levels;
    const int esize = 1 << len;
    const int diff = S - R;
    const int d = diff & (esize - 1);
    uint64_t welem = (S + 1 >= 64) ? ~0ull : ((1ull << (S + 1)) - 1);
    uint64_t telem = (d + 1 >= 64) ? ~0ull : ((1ull << (d + 1)) - 1);
    uint64_t w = ror64(welem, R, esize), t = telem;
    uint64_t W = 0, T = 0;
    for (int i = 0; i < datasize; i += esize) { W |= w << i; T |= t << i; }
    if (datasize == 32) { W &= 0xffffffffu; T &= 0xffffffffu; }
    *wmask = W;
    *tmask = T;
}

static void fail(const Sim *s, uint32_t i)
{
    fprintf(stderr, "sim: cannot execute %08x at %llx\n", i, (unsigned long long)s->pc);
    exit(2);
}

static inline uint64_t rz(const Sim *s, int r) { return r == 31 ? 0 : s->x[r]; }
static inline uint64_t rsp(const Sim *s, int r) { return r == 31 ? s->sp : s->x[r]; }
static inline void wz(Sim *s, int r, uint64_t v) { if (r != 31) s->x[r] = v; }
static inline void wsp(Sim *s, int r, uint64_t v) { if (r == 31) s->sp = v; else s->x[r] = v; }

static int cond(const Sim *s, int c)
{
    int r;
    switch (c >> 1) {
    case 0: r = s->Z; break;
    case 1: r = s->C; break;
    case 2: r = s->N; break;
    case 3: r = s->V; break;
    case 4: r = s->C && !s->Z; break;
    case 5: r = s->N == s->V; break;
    case 6: r = s->N == s->V && !s->Z; break;
    default: r = 1; break;
    }
    if ((c & 1) && c != 15) r = !r;
    return r;
}

static uint64_t addflags(Sim *s, int sf, uint64_t a, uint64_t b, int carry_in, int sub)
{
    if (sub) b = ~b;
    if (!sf) { a &= 0xffffffffu; b &= 0xffffffffu; }
    const uint64_t res = a + b + (uint64_t)carry_in;
    uint64_t r = sf ? res : (res & 0xffffffffu);
    int C, V, N, Z;
    if (sf) {
        C = (res < a) || (carry_in && res == a);
        V = (int)((~(a ^ b) & (a ^ res)) >> 63 & 1);
        N = (int)(r >> 63);
    } else {
        C = (int)((a + b + (uint64_t)carry_in) >> 32 & 1);
        V = (int)((~(a ^ b) & (a ^ res)) >> 31 & 1);
        N = (int)(r >> 31 & 1);
    }
    Z = r == 0;
    s->N = N; s->Z = Z; s->C = C; s->V = V;
    return r;
}

void a64sim_run(void *S_, void *enter, void *entry);

void a64sim_run(void *S_, void *enter, void *entry)
{
    static uint64_t stack[512];
    Sim s;
    memset(&s, 0, sizeof s);
    s.sp = (uint64_t)(uintptr_t)(stack + 512);
    s.x[0] = (uint64_t)(uintptr_t)S_;
    s.x[1] = (uint64_t)(uintptr_t)entry;
    s.x[30] = 0xdead0000ull;
    s.pc = (uint64_t)(uintptr_t)enter;

    for (;;) {
        if (s.pc == 0xdead0000ull) return;
        const uint32_t i = *(const uint32_t *)(uintptr_t)s.pc;
        uint64_t next = s.pc + 4;
        const int rd = i & 31, rn = (i >> 5) & 31, rm = (i >> 16) & 31;
        const int sf = i >> 31;
        const uint64_t mask = sf ? ~0ull : 0xffffffffull;

        if ((i & 0x1F800000u) == 0x12800000u) {                       /* move wide */
            const int opc = (i >> 29) & 3, hw = (i >> 21) & 3;
            const uint64_t imm = (uint64_t)((i >> 5) & 0xffff) << (16 * hw);
            if (!sf && hw > 1) fail(&s, i);
            if (opc == 0) wz(&s, rd, ~imm & mask);
            else if (opc == 2) wz(&s, rd, imm);
            else if (opc == 3) wz(&s, rd, (rz(&s, rd) & ~(0xffffull << (16 * hw)) & mask) | imm);
            else fail(&s, i);
        } else if ((i & 0x1F000000u) == 0x11000000u) {                /* add/sub immediate */
            const int op = (i >> 30) & 1, S = (i >> 29) & 1, sh = (i >> 22) & 1;
            uint64_t imm = (i >> 10) & 0xfff;
            if (sh) imm <<= 12;
            const uint64_t a = rsp(&s, rn);
            uint64_t r;
            if (S) {
                r = addflags(&s, sf, a, imm, op, op);
                wz(&s, rd, r);
            } else {
                r = op ? a - imm : a + imm;
                wsp(&s, rd, r & mask);
            }
        } else if ((i & 0x1F000000u) == 0x0B000000u) {                /* add/sub shifted register */
            const int op = (i >> 30) & 1, S = (i >> 29) & 1, shift = (i >> 22) & 3, amt = (i >> 10) & 63;
            if (shift != 0 || (i & 0x200000u)) fail(&s, i);          /* LSL only, not the extended form */
            uint64_t b = (rz(&s, rm) << amt) & mask;
            const uint64_t a = rz(&s, rn);
            if (S) wz(&s, rd, addflags(&s, sf, a, b, op, op));
            else wz(&s, rd, (op ? a - b : a + b) & mask);
        } else if ((i & 0x1F000000u) == 0x0A000000u) {                /* logical shifted register */
            const int opc = (i >> 29) & 3, shift = (i >> 22) & 3, amt = (i >> 10) & 63, n = (i >> 21) & 1;
            if (shift != 0 || n) fail(&s, i);
            const uint64_t a = rz(&s, rn), b = (rz(&s, rm) << amt) & mask;
            uint64_t r = opc == 0 ? a & b : opc == 1 ? a | b : opc == 2 ? a ^ b : a & b;
            r &= mask;
            if (opc == 3) { s.N = (int)(r >> (sf ? 63 : 31) & 1); s.Z = r == 0; s.C = s.V = 0; }
            wz(&s, rd, r);
        } else if ((i & 0x1F800000u) == 0x12000000u) {                /* logical immediate */
            const int opc = (i >> 29) & 3, N = (i >> 22) & 1, immr = (i >> 16) & 63, imms = (i >> 10) & 63;
            if (!sf && N) fail(&s, i);
            uint64_t wm, tm;
            bitmasks(N, imms, immr, 1, sf ? 64 : 32, &wm, &tm);
            const uint64_t a = rz(&s, rn);
            uint64_t r = opc == 0 ? a & wm : opc == 1 ? a | wm : opc == 2 ? a ^ wm : a & wm;
            r &= mask;
            if (opc == 3) { s.N = (int)(r >> (sf ? 63 : 31) & 1); s.Z = r == 0; s.C = s.V = 0; wz(&s, rd, r); }
            else wsp(&s, rd, r);
        } else if ((i & 0x1F800000u) == 0x13000000u) {                /* bitfield */
            const int opc = (i >> 29) & 3, N = (i >> 22) & 1, immr = (i >> 16) & 63, imms = (i >> 10) & 63;
            if (opc == 1 || N != sf) fail(&s, i);
            const int ds = sf ? 64 : 32;
            uint64_t wm, tm;
            bitmasks(N, imms, immr, 0, ds, &wm, &tm);
            const uint64_t src = rz(&s, rn) & mask;
            const uint64_t bot = ror64(src, immr, ds) & wm;
            uint64_t r;
            if (opc == 2) r = bot & tm;
            else {
                const uint64_t top = (src >> imms & 1) ? ~0ull : 0;
                r = (top & ~tm) | (bot & tm);
            }
            wz(&s, rd, r & mask);
        } else if ((i & 0x7FE00000u) == 0x1AC00000u) {                /* two source */
            const int op = (i >> 10) & 63;
            const uint64_t a = rz(&s, rn) & mask, b = rz(&s, rm) & mask;
            uint64_t r;
            if (sf) fail(&s, i);
            switch (op) {
            case 2: r = b ? (uint32_t)a / (uint32_t)b : 0; break;
            case 3: r = b == 0 ? 0 : ((int32_t)a == INT32_MIN && (int32_t)b == -1) ? (uint32_t)INT32_MIN : (uint32_t)((int32_t)a / (int32_t)b); break;
            case 8: r = (uint32_t)a << (b & 31); break;
            case 9: r = (uint32_t)a >> (b & 31); break;
            case 10: r = (uint32_t)((int32_t)a >> (b & 31)); break;
            default: fail(&s, i); r = 0;
            }
            wz(&s, rd, r & 0xffffffffu);
        } else if ((i & 0x1F000000u) == 0x1B000000u) {                /* three source */
            const int op31 = (i >> 21) & 7, o0 = (i >> 15) & 1, ra = (i >> 10) & 31;
            if (op31 == 0) {
                const uint64_t a = rz(&s, rn), b = rz(&s, rm), c = rz(&s, ra);
                wz(&s, rd, (o0 ? c - a * b : c + a * b) & mask);
            } else if (op31 == 1 && sf && !o0) {
                wz(&s, rd, rz(&s, ra) + (uint64_t)((int64_t)(int32_t)rz(&s, rn) * (int64_t)(int32_t)rz(&s, rm)));
            } else if (op31 == 5 && sf && !o0) {
                wz(&s, rd, rz(&s, ra) + (uint64_t)(uint32_t)rz(&s, rn) * (uint64_t)(uint32_t)rz(&s, rm));
            } else fail(&s, i);
        } else if ((i & 0x1FE00000u) == 0x1A800000u) {                /* conditional select */
            const int op = (i >> 30) & 1, op2 = (i >> 10) & 3, c = (i >> 12) & 15;
            uint64_t v;
            if (cond(&s, c)) v = rz(&s, rn);
            else {
                v = rz(&s, rm);
                if (op2 == 1 && !op) v += 1;
                else if (op2 == 0 && op) v = ~v;
                else if (!(op2 == 0 && !op)) fail(&s, i);
            }
            wz(&s, rd, v & mask);
        } else if ((i & 0x3B000000u) == 0x39000000u) {                /* load/store unsigned offset */
            const int size = (i >> 30) & 3, opc = (i >> 22) & 3;
            const uint64_t addr = rsp(&s, rn) + ((uint64_t)((i >> 10) & 0xfff) << size);
            uint8_t *p = (uint8_t *)(uintptr_t)addr;
            if (opc == 0) {
                if (size == 3) memcpy(p, &s.x[rd], 8); else { uint64_t v = rz(&s, rd); memcpy(p, &v, 1 << size); }
            } else if (opc == 1) {
                uint64_t v = 0; memcpy(&v, p, 1 << size); wz(&s, rd, v);
            } else if (opc == 3 && size < 2) {
                int32_t v;
                if (size == 0) v = (int8_t)*p; else { int16_t h; memcpy(&h, p, 2); v = h; }
                wz(&s, rd, (uint32_t)v);
            } else fail(&s, i);
        } else if ((i & 0x3B200C00u) == 0x38200800u) {                /* load/store register offset */
            const int size = (i >> 30) & 3, opc = (i >> 22) & 3, option = (i >> 13) & 7, S = (i >> 12) & 1;
            if (option != 3 || S) fail(&s, i);
            const uint64_t addr = rsp(&s, rn) + rz(&s, rm);
            uint8_t *p = (uint8_t *)(uintptr_t)addr;
            if (opc == 0) { uint64_t v = rz(&s, rd); memcpy(p, &v, 1 << size); }
            else if (opc == 1) { uint64_t v = 0; memcpy(&v, p, 1 << size); wz(&s, rd, v); }
            else if (opc == 3 && size < 2) {
                int32_t v;
                if (size == 0) v = (int8_t)*p; else { int16_t h; memcpy(&h, p, 2); v = h; }
                wz(&s, rd, (uint32_t)v);
            } else fail(&s, i);
        } else if ((i & 0x3E000000u) == 0x28000000u) {                /* load/store pair */
            const int opc = (i >> 30) & 3, type = (i >> 23) & 3, L = (i >> 22) & 1, rt2 = (i >> 10) & 31;
            if (opc != 2) fail(&s, i);
            int64_t imm = (int64_t)((i >> 15) & 0x7f); if (imm & 0x40) imm -= 128; imm *= 8;
            uint64_t base = rsp(&s, rn), addr = (type == 1) ? base : base + (uint64_t)imm;
            uint64_t *p = (uint64_t *)(uintptr_t)addr;
            if (L) { memcpy(&s.x[rd], p, 8); memcpy(&s.x[rt2], p + 1, 8); }
            else { uint64_t a = rz(&s, rd), b = rz(&s, rt2); memcpy(p, &a, 8); memcpy(p + 1, &b, 8); }
            if (type == 1 || type == 3) wsp(&s, rn, base + (uint64_t)imm);
        } else if ((i & 0xFC000000u) == 0x14000000u) {                /* b */
            int64_t off = (int32_t)(i << 6) >> 6;
            next = s.pc + (uint64_t)(off * 4);
        } else if ((i & 0xFF000010u) == 0x54000000u) {                /* b.cond */
            int64_t off = (int32_t)((i >> 5) & 0x7ffff); if (off & 0x40000) off -= 0x80000;
            if (cond(&s, i & 15)) next = s.pc + (uint64_t)(off * 4);
        } else if ((i & 0x7E000000u) == 0x34000000u) {                /* cbz / cbnz */
            int64_t off = (int32_t)((i >> 5) & 0x7ffff); if (off & 0x40000) off -= 0x80000;
            uint64_t v = rz(&s, rd) & mask;
            if (((i >> 24) & 1) ? v != 0 : v == 0) next = s.pc + (uint64_t)(off * 4);
        } else if ((i & 0xFFFFFC1Fu) == 0xD61F0000u) {                /* br */
            next = rz(&s, rn);
        } else if ((i & 0xFFFFFC1Fu) == 0xD63F0000u) {                /* blr */
            const uint64_t t = rz(&s, rn);
            s.x[30] = s.pc + 4;
            if (t == 1) s.x[0] = chg_sim_ld((void *)(uintptr_t)s.x[0], (uint32_t)s.x[1], (uint32_t)s.x[2], (uint32_t)s.x[3]);
            else if (t == 2) chg_sim_st((void *)(uintptr_t)s.x[0], (uint32_t)s.x[1], (uint32_t)s.x[2], (uint32_t)s.x[3], (uint32_t)s.x[4]);
            else if (t == 3) chg_sim_inv((void *)(uintptr_t)s.x[0], (uint32_t)s.x[1], (uint32_t)s.x[2]);
            else fail(&s, i);
            /* a call clobbers the caller-saved registers */
            for (int r = 1; r < 16; r++) s.x[r] = 0xBAD0BAD0BAD0BAD0ull + (uint64_t)r;
            s.x[16] = s.x[17] = 0xBAD0BAD0BAD0BAD0ull;
            next = s.pc + 4;
            s.x[30] = 0xBAD1BAD1BAD1BAD1ull;
        } else if (i == 0xD65F03C0u) {                                /* ret */
            next = s.x[30];
        } else if ((i & 0x9F000000u) == 0x10000000u) {                /* adr */
            int64_t imm = (int64_t)(((i >> 5) & 0x7ffff) << 2 | ((i >> 29) & 3));
            if (imm & 0x100000) imm -= 0x200000;
            wz(&s, rd, s.pc + (uint64_t)imm);
        } else fail(&s, i);
        s.pc = next;
    }
}
#endif
