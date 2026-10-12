"""Run portable ksud regressions without root or an Android device.

Linux: python3 userspace/ksud/tests/run_host_tests.py --cxx clang++
Windows: python userspace/ksud/tests/run_host_tests.py --zig /path/to/zig.exe
"""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--clang", default="clang",
                        help="Clang assembler for the ARM64 bootstrap (unused with --zig)")
    parser.add_argument("--zig", type=Path)
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    compiler = [str(args.zig), "c++"] if args.zig else [args.cxx]
    tests = Path(__file__).resolve().parent
    src = tests.parent / "src"

    with tempfile.TemporaryDirectory(prefix="ksud-host-tests-") as temporary:
        build = Path(temporary)
        if os.name == "nt":
            if not args.zig:
                parser.error("Windows tests require --zig for the ELF header")
            # Only elf.h is needed; keep Linux libc headers off the host include path.
            shutil.copyfile(args.zig.parent / "lib/libc/include/generic-musl/elf.h",
                            build / "elf.h")
        source = (src / "boot/tools.cpp").read_text(encoding="utf-8")
        begin = source.index("bool exec_dd(")
        end = source.index("\n// `blockdev", begin)
        (build / "boot_copy_under_test.inc").write_text(source[begin:end], encoding="utf-8")
        # Link the production formatter without utils.cpp's Android-only dependencies.
        source = (src / "utils.cpp").read_text(encoding="utf-8")
        begin = source.index("void append_hex(")
        end = source.index("\n}", begin) + len("\n}")
        formatting = build / "image_formatting_under_test.cpp"
        formatting.write_text(
            "#include <array>\n#include <charconv>\n#include <cstdint>\n#include <string>\n"
            "namespace ksud {\n" + source[begin:end] + "\n}\n", encoding="utf-8")
        bootstrap = build / "lkm_image_bootstrap.o"
        assembler = [str(args.zig), "cc"] if args.zig else [args.clang]
        # Match the embedded object: Zig otherwise adds debug relocation sections.
        subprocess.run([*assembler, "-target", "aarch64-linux-gnu", "-c", "-g0", "-nostdlib",
                        str(src / "boot/lkm_image_bootstrap.S"), "-o", str(bootstrap)],
                       check=True)
        cases = [
            ("elf_symbols_test", []),
            ("boot_copy_test", []),
            ("kernel_version_test", []),
            ("su_args_test", [src / "su_args.cpp"]),
            ("cli_args_test", [src / "cli_args.cpp", src / "terminal.cpp"]),
            ("xperm_parser_test", [src / "sepolicy/xperm_parser.cpp"]),
            ("boot_image_btf_test", [src / "boot/boot_image_btf.cpp"]),
            ("lkm_image_core_test", [src / "boot/lkm_image_core.cpp", formatting]),
            ("lkm_image_bootstrap_linker_test", [src / "boot/lkm_image_bootstrap_linker.cpp"]),
        ]
        for name, sources in cases:
            binary = build / (name + (".exe" if os.name == "nt" else ""))
            extra_flags = (["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
                           if args.sanitize else [])
            if name == "cli_args_test":
                # The command table value-initializes omitted optional fields.
                extra_flags.append("-Wno-missing-field-initializers")
            if os.name == "nt" and name == "lkm_image_core_test":
                # utils.hpp declares a POSIX reader unused by the image parser.
                extra_flags.append("-DO_CLOEXEC=0")
            command = [*compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror",
                       *extra_flags,
                       "-I" + str(build), "-I" + str(src), str(tests / (name + ".cpp")),
                       *map(str, sources), "-o", str(binary)]
            subprocess.run(command, check=True)
            arguments = [str(bootstrap)] if name == "lkm_image_bootstrap_linker_test" else []
            subprocess.run([str(binary), *arguments], check=True, timeout=60)
            print(f"PASS {name}", flush=True)


if __name__ == "__main__":
    main()
