/* Android: the core is compiled against musl's headers (zig has no bionic),
   which name errno's accessor __errno_location; bionic calls it __errno */
extern int *__errno(void);
__attribute__((visibility("hidden"))) int *__errno_location(void) { return __errno(); }

/* Stack protector (-fstack-protector-strong): zig's musl target reads a global
   guard and calls __stack_chk_fail, neither of which the stub libc provides.
   Both are defined here, hidden. The guard is seeded from bionic's arc4random
   before any protected code runs; the seeding function itself is unprotected. */
extern void arc4random_buf(void *buf, unsigned long n);
extern void abort(void);
__attribute__((visibility("hidden"))) unsigned long __stack_chk_guard = 0x595e9fbd94fda766ul;
__attribute__((visibility("hidden"), noreturn)) void __stack_chk_fail(void) { abort(); }
__attribute__((constructor, no_stack_protector, visibility("hidden"))) static void chg_seed_stack_guard(void)
{
    unsigned long g;
    arc4random_buf(&g, sizeof g);
    g &= ~0xfful;                 /* a zero low byte stops string overruns copying the guard */
    __stack_chk_guard = g;
}
