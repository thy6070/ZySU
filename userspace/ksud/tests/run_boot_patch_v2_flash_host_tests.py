"""Test v2 partial-write recovery using temporary files, never block devices.

Linux: python3 userspace/ksud/tests/run_boot_patch_v2_flash_host_tests.py --cxx clang++ --sanitize
Windows: python run_boot_patch_v2_flash_host_tests.py --zig /path/to/zig.exe
"""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def between(source, begin, end):
    start = source.index(begin)
    return source[start:source.index(end, start)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--zig", type=Path)
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    compiler = [str(args.zig), "c++"] if args.zig else [args.cxx]
    tests = Path(__file__).resolve().parent
    source = (tests.parent / "src/boot/boot_patch_v2.cpp").read_text(encoding="utf-8")
    with tempfile.TemporaryDirectory(prefix="ksud-v2-flash-tests-") as temporary:
        build = Path(temporary)
        # Compile the production write/rollback and cleanup paths with only the
        # block-device writer replaced by a partial-write fault injector.
        extracts = {
            "v2_flash_under_test.inc": between(
                source, "bool flash_partition_image(",
                "std::optional<std::vector<std::uint8_t>> load_module("),
            "v2_cleanup_under_test.inc": between(
                source, "    bool preserve_recovery_snapshots = false;", "    fs::path input_path;"),
            "v2_apply_under_test.inc": between(
                source, "        const bool init_boot_changed =", "        if (previous_backup"),
        }
        for name, content in extracts.items():
            (build / name).write_text(content, encoding="utf-8")
        binary = build / ("boot_patch_v2_flash_test.exe" if os.name == "nt"
                          else "boot_patch_v2_flash_test")
        flags = (["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
                 if args.sanitize else [])
        subprocess.run([
            *compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror", *flags,
            "-I" + str(build), str(tests / "boot_patch_v2_flash_test.cpp"), "-o", str(binary),
        ], check=True)
        subprocess.run([str(binary), str(build)], check=True, timeout=60)
        print("PASS boot_patch_v2_flash_test", flush=True)


if __name__ == "__main__":
    main()
