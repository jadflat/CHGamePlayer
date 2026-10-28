"""Minimal ELF64 checker for Android .so: machine, DT_NEEDED, symbols, versions, TLS, textrel, hash."""
import struct, sys

# Symbols known NOT to exist in bionic (glibc/musl-header artefacts)
BAD = {"__errno_location", "__isoc99_sscanf", "__isoc99_fscanf", "__isoc99_scanf",
       "__isoc23_sscanf", "__isoc23_strtol", "__isoc23_strtoul", "__isoc23_strtoll",
       "__fprintf_chk", "__printf_chk", "__sprintf_chk", "__snprintf_chk",
       "__vsnprintf_chk", "__memcpy_chk", "__strcpy_chk", "__fread_chk",
       "__libc_start_main", "__ctype_b_loc", "__ctype_tolower_loc", "__ctype_toupper_loc",
       "__xstat", "__lxstat", "__fxstat", "__xstat64", "stat64", "fstat64", "fopen64",
       "readdir64", "__assert_fail", "__cxa_finalize_glibc", "_IO_putc", "_IO_getc",
       "__stack_chk_guard_glibc", "__tls_get_addr", "__getdelim", "__overflow", "__uflow",
       "__fpclassify", "__fpclassifyl", "__signbit", "__signbitl", "__isinf", "__isnan"}


def main(path):
    d = open(path, "rb").read()
    assert d[:4] == b"\x7fELF", "not ELF"
    cls, data = d[4], d[5]
    assert cls == 2 and data == 1, "not ELF64 LE"
    (e_type, e_machine, _, _, e_phoff, e_shoff, _, _, e_phentsize, e_phnum,
     e_shentsize, e_shnum, e_shstrndx) = struct.unpack_from("<HHIQQQIHHHHHH", d, 16)
    print(f"type={e_type} (3=DYN) machine={e_machine} (183=AArch64)")
    # program headers
    dyn_off = dyn_sz = None
    for i in range(e_phnum):
        p_type, p_flags, p_off, p_vaddr, _, p_filesz, p_memsz, p_align = struct.unpack_from(
            "<IIQQQQQQ", d, e_phoff + i * e_phentsize)
        names = {1: "LOAD", 2: "DYNAMIC", 3: "INTERP", 7: "TLS", 0x6474e551: "GNU_STACK",
                 0x6474e552: "GNU_RELRO", 0x6474e550: "GNU_EH_FRAME"}
        print(f"  PH {names.get(p_type, hex(p_type)):12} flags={p_flags} align={p_align:#x}")
        if p_type == 3:
            print("  !! PT_INTERP present")
        if p_type == 7:
            print("  !! PT_TLS present (check alignment >= 64 for API 29+ arm64)")
    # sections
    secs = []
    for i in range(e_shnum):
        secs.append(struct.unpack_from("<IIQQQQIIQQ", d, e_shoff + i * e_shentsize))
    shstr = secs[e_shstrndx]
    def sname(off, base):
        e = d.index(b"\0", base + off)
        return d[base + off:e].decode()
    byname = {sname(s[0], shstr[4]): s for s in secs}
    print("  sections:", " ".join(n for n in byname if n))
    dynsym, dynstr = byname.get(".dynsym"), byname.get(".dynstr")
    strbase = dynstr[4]
    # dynamic
    dyn = byname[".dynamic"]
    tags = {1: "NEEDED", 14: "SONAME", 22: "TEXTREL", 30: "FLAGS", 0x6ffffffb: "FLAGS_1",
            0x6ffffffe: "VERNEED", 0x6ffffffc: "VERDEF", 4: "HASH", 0x6ffffef5: "GNU_HASH",
            15: "RPATH", 29: "RUNPATH"}
    for off in range(dyn[4], dyn[4] + dyn[5], 16):
        tag, val = struct.unpack_from("<qQ", d, off)
        if tag == 0:
            break
        t = tags.get(tag)
        if t in ("NEEDED", "SONAME", "RPATH", "RUNPATH"):
            print(f"  DT_{t}: {sname(val, strbase)}")
        elif t:
            print(f"  DT_{t}: {val:#x}")
    if ".gnu.version_r" in byname:
        print("  !! .gnu.version_r present (symbol versions needed)")
    # symbols
    und, exp = [], []
    for off in range(dynsym[4] + 24, dynsym[4] + dynsym[5], 24):
        st_name, st_info, st_other, st_shndx, _, _ = struct.unpack_from("<IBBHQQ", d, off)
        n = sname(st_name, strbase)
        bind, typ = st_info >> 4, st_info & 15
        if st_shndx == 0:
            und.append((n, typ))
        elif bind in (1, 2):
            exp.append(n)
    print("  exported:", " ".join(sorted(exp)))
    print("  undefined:", " ".join(sorted(n for n, _ in und)))
    bad = [n for n, t in und if n in BAD or n.startswith("__isoc") or n.endswith("_chk")
           or n.startswith("GLIBC") or n in ("stdin", "stdout", "stderr")]
    tls = [n for n, t in und if t == 6]
    if bad:
        print("  !! check:", bad, "(stdin/stdout/stderr need API >= 23)")
    if tls:
        print("  !! TLS symbols:", tls)


if __name__ == "__main__":
    for p in sys.argv[1:]:
        print("==", p)
        main(p)
