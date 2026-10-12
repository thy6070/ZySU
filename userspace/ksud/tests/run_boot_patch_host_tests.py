"""Exercise boot patch configuration and ramdisk cleanup without an Android device.

Linux: python3 userspace/ksud/tests/run_boot_patch_host_tests.py --cxx clang++
Windows: python run_boot_patch_host_tests.py --zig /path/to/zig.exe
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
    boot = tests.parent / "src/boot"
    legacy = (boot / "boot_patch.cpp").read_text(encoding="utf-8")
    direct = (boot / "boot_patch_v2.cpp").read_text(encoding="utf-8")
    utils = (boot.parent / "utils.cpp").read_text(encoding="utf-8")
    with tempfile.TemporaryDirectory(prefix="ksud-boot-patch-tests-") as temporary:
        build = Path(temporary)
        # Exercise the production functions with file/tool/crypto adapters, just
        # as boot_copy_test does, without linking the Android service runtime.
        extracts = {
            "boot_patch_config_under_test.inc": "\n".join([
                between(legacy, "constexpr uint64_t SUPERKEY_MAGIC", "namespace {"),
                between(legacy, "constexpr size_t SUPERKEY_SALT_LEN", "// SHA-256(salt || key)"),
                between(legacy, "bool inject_superkey_to_lkm(", "bool inject_imgpatch_config_to_lkm("),
                between(legacy, "bool inject_superkey_into_lkm(", "bool inject_imgpatch_config_into_lkm("),
            ]),
            "boot_patch_legacy_config_under_test.inc": between(
                legacy, "    // Always inject verification mode", "    // Prepare init if specified"),
            "boot_patch_direct_config_under_test.inc": between(
                direct, "    if (!parsed.superkey.empty())", "    if (!inject_imgpatch_config_into_lkm("),
            "boot_patch_target_kmi_under_test.inc": between(
                legacy, "int boot_info_target_kmi(", "int boot_info_supported_kmis("),
            "boot_patch_cleanup_under_test.inc": between(
                direct, "constexpr std::array<const char*, 9> kLegacyRamdiskEntries", "struct CleanedRamdiskImage"),
            "boot_patch_kernel_kmi_under_test.inc": between(
                legacy, "std::string parse_kmi_from_kernel_file(", "std::string parse_kmi_from_boot("),
            "boot_patch_parse_kmi_under_test.inc": between(
                utils, "std::optional<std::string> parse_kmi_string(", "std::string_view next_token("),
            "boot_patch_detect_kmi_under_test.inc": between(
                legacy, "std::string parse_kmi_from_boot(", "bool restore_legacy_ramdisk("),
            "boot_patch_select_kmi_under_test.inc": between(
                legacy, "    // Get or detect KMI", "    // Determine boot image path"),
            "boot_patch_replace_kernel_under_test.inc": between(
                legacy, "    // Replace only after reconstructing", "    // Find ramdisk"),
            "boot_patch_adb_props_under_test.inc": between(
                legacy, "    if (parsed.enable_adbd ||", "    // Remove the legacy embedded module"),
            "boot_patch_restore_ramdisk_under_test.inc": between(
                legacy, "bool restore_legacy_ramdisk(", "}  // namespace"),
            "boot_patch_flash_under_test.inc": between(
                legacy, "    // Flash if requested", "    cleanup();\n\n    printf(\"- Done!"),
            "boot_restore_flash_under_test.inc": between(
                legacy[legacy.index("int boot_restore("):], "    // Flash if requested",
                "    if (used_backup &&"),
            "boot_patch_recovery_cleanup_under_test.inc": between(
                legacy, "    bool preserve_workdir = false;", "    // Find magiskboot"),
        }
        for name, content in extracts.items():
            (build / name).write_text(content, encoding="utf-8")
        flags = (["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
                 if args.sanitize else [])
        for name in ("boot_patch_flow_test", "boot_patch_options_test"):
            binary = build / (name + (".exe" if os.name == "nt" else ""))
            subprocess.run([
                *compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror", *flags,
                "-I" + str(build), "-I" + str(boot.parent),
                str(tests / (name + ".cpp")), "-o", str(binary),
            ], check=True)
            subprocess.run([str(binary), str(build)], check=True, timeout=60)
            print(f"PASS {name}", flush=True)


if __name__ == "__main__":
    main()
