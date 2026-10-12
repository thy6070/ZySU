"""Exercise repack I/O, failure cleanup, CLI status and DHTB layout on host files."""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--zig", type=Path)
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    compiler = [str(args.zig), "c++"] if args.zig else [args.cxx]
    tests = Path(__file__).resolve().parent
    source = tests.parent / "third_party/MagiskbootAlone/src"
    boot = (source / "bootimg.cpp").read_text(encoding="utf-8")
    headers = (source / "bootimg.hpp").read_text(encoding="utf-8")
    definitions = (source / "magiskboot.hpp").read_text(encoding="utf-8")
    io = (source / "repack_io.hpp").read_text(encoding="utf-8")
    cli = (source / "magiskboot_main.cpp").read_text(encoding="utf-8")
    begin = boot.index("int repack(")
    prefix_end = boot.index("    // Copy non-standard headers", begin)
    header_begin = boot.index("    // Copy main header", prefix_end)
    header_end = boot.index("    if (boot.flags[AVB_FLAG])", header_begin)
    finish = boot.index("    output.finish();", header_end)
    finish_end = boot.index("\nvoid cleanup()", finish)
    dispatch = cli.index('            if (argc < 3)', cli.index('cmd == "repack"'))
    dispatch_end = cli.index('        } else if (cmd == "split-dtb")', dispatch)
    dhtb_begin = boot.index("    if (boot.flags[DHTB_FLAG])", header_end)
    dhtb_end = boot.index("    } else if (boot.flags[BLOB_FLAG])", dhtb_begin)
    marker_begin = boot.index("    // Proprietary stuffs", prefix_end)
    marker_end = boot.index("    file_align();", marker_begin)
    size_begin = boot.index("    uint32_t aosp_img_size =", marker_end)
    size_end = boot.index("\n", size_begin)
    struct_begin = headers.index("struct dhtb_hdr {")
    struct_end = headers.index(";", headers.index("} __attribute__", struct_begin)) + 1
    with tempfile.TemporaryDirectory(prefix="ksud-repack-tests-") as temporary:
        build = Path(temporary)
        (build / "repack_io_under_test.inc").write_text(
            io[io.index("namespace repack_io {"):], encoding="utf-8")
        (build / "repack_input_under_test.inc").write_text(
            boot[boot.index("class repack_input {"):begin], encoding="utf-8")
        # Keep allocation, header write, cleanup and exception/return handling
        # unchanged. The fixture replaces format parsing and payload compression.
        (build / "repack_under_test.inc").write_text(
            boot[begin:prefix_end]
            + "    write_fixture_payload(fd);\n"
            + "    const size_t out_sz = invalid_header ? 0 : 8192;\n"
            + boot[header_begin:header_end]
            + boot[finish:finish_end], encoding="utf-8")
        (build / "repack_dispatch_under_test.inc").write_text(
            "int dispatch_repack(int argc, char** argv) {\n"
            + cli[dispatch:dispatch_end] + "}\n", encoding="utf-8")
        (build / "repack_dhtb_header_under_test.inc").write_text(
            headers[struct_begin:struct_end] + "\n"
            + "\n".join(line for line in definitions.splitlines()
                        if any(line.startswith("#define " + name + " ")
                               for name in ("DHTB_MAGIC", "SEANDROID_MAGIC", "LG_BUMP_MAGIC")))
            + "\n", encoding="utf-8")
        (build / "repack_dhtb_markers_under_test.inc").write_text(
            boot[marker_begin:marker_end], encoding="utf-8")
        (build / "repack_dhtb_under_test.inc").write_text(
            boot[size_begin:size_end] + "\n"
            + boot[dhtb_begin:dhtb_end] + "    }\n", encoding="utf-8")
        flags = (["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
                 if args.sanitize else [])
        for name in ("repack_io_test", "repack_dhtb_test"):
            binary = build / (name + (".exe" if os.name == "nt" else ""))
            subprocess.run([
                *compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror", *flags,
                "-Wno-unused-parameter", "-Wno-unused-but-set-variable",
                "-I" + str(build), str(tests / (name + ".cpp")), "-o", str(binary),
            ], check=True)
            subprocess.run([str(binary), str(build)], check=True, timeout=60)
            print("PASS " + name, flush=True)


if __name__ == "__main__":
    main()
