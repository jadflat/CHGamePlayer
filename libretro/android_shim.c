/* Android: the core is compiled against musl's headers (zig has no bionic),
   which name errno's accessor __errno_location; bionic calls it __errno */
extern int *__errno(void);
__attribute__((visibility("hidden"))) int *__errno_location(void) { return __errno(); }
