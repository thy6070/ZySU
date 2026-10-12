#include "kernel_version.hpp"
#include <algorithm>
#include <array>
#include <cassert>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <vector>

namespace fs = std::filesystem;
using ksud::is_supported_kmi;
#define LOGE(...) ((void)0)
#define LOGW(...) ((void)0)

std::optional<std::string> read_file(const std::string& path) {
    std::ifstream stream(path, std::ios::binary);
    if (!stream)
        return std::nullopt;
    return std::string(std::istreambuf_iterator<char>(stream), {});
}
bool write_file(const std::string& path, const std::string& contents) {
    std::ofstream stream(path, std::ios::binary);
    stream << contents;
    stream.close();
    return !stream.fail();
}
bool read_file_bytes(const std::string& path, std::vector<uint8_t>* data) {
    const auto contents = read_file(path);
    if (!contents)
        return false;
    data->assign(contents->begin(), contents->end());
    return true;
}
bool copy_file_data(const std::string& src, const std::string& dst) {
    const auto contents = read_file(src);
    return contents && write_file(dst, *contents);
}

#include "boot_patch_parse_kmi_under_test.inc"
#include "boot_patch_kernel_kmi_under_test.inc"

struct Options {
    std::string kernel, boot_image, kmi, adb_debug_prop;
    bool ota = false, enable_adbd = false, flash = false;
};
unsigned current_kmi_reads = 0, cleanup_calls = 0;
std::string inspected_boot;
std::string detected_kmi = "android15-6.6";
std::string get_current_kmi() {
    ++current_kmi_reads;
    return "android14-6.1";
}
std::string parse_kmi_from_boot(const std::string&, const std::string&, const std::string& path) {
    inspected_boot = path;
    return detected_kmi;
}
void cleanup() { ++cleanup_calls; }
int select_kmi(const Options& parsed, const std::string& workdir, std::string* selected) {
    const std::string magiskboot = "", ota_slot = "_b";
#include "boot_patch_select_kmi_under_test.inc"
    *selected = kmi;
    return 0;
}
int replace_kernel(const Options& parsed, const std::string& workdir) {
#include "boot_patch_replace_kernel_under_test.inc"
    return 0;
}

std::map<std::string, std::string> entries;
std::string fail_cpio;
int magiskboot_query(const std::vector<std::string>& args) {
    assert(args.size() == 3 && args[2].rfind("exists ", 0) == 0);
    return entries.count(args[2].substr(7)) ? 0 : 1;
}
bool do_cpio_cmd(const std::string&, const std::string&, const std::string&, const std::string& cmd) {
    if (cmd == fail_cpio)
        return false;
    if (cmd.rfind("rm ", 0) == 0)
        return entries.erase(cmd.substr(3)) == 1;
    if (cmd == "mv init.real init") {
        // The real archive refuses destination collisions.
        if (entries.count("init") || !entries.count("init.real"))
            return false;
        entries["init"] = entries.at("init.real");
        entries.erase("init.real");
        return true;
    }
    return cmd.rfind("add ", 0) == 0;
}
struct CommandResult {
    int exit_code = 0;
    std::string stdout_str;
    std::string stderr_str;
};
CommandResult exec_command_magiskboot(const std::string&, const std::vector<std::string>& args,
                                      const std::string& workdir) {
    if (args[0] == "unpack") {
        // Model subprocess cwd resolution; a relative input would resolve in
        // target_kmi instead of the caller's directory and fail this test.
        const auto source = read_file((fs::path(workdir) / args[1]).string());
        if (!source || !write_file((fs::path(workdir) / "kernel").string(), *source))
            return {1, "", "input not found"};
        return {};
    }
    assert(args[2] == "ls -r");
    CommandResult result;
    for (const auto& [name, content] : entries) {
        (void)content;
        result.stdout_str += "100644\t" + name + "\n";
    }
    return result;
}
namespace target_detection {
bool ensure_clean_dir(const std::string& directory) {
    std::error_code error;
    fs::create_directories(directory, error);
    return !error;
}
#include "boot_patch_detect_kmi_under_test.inc"
}
#include "boot_patch_restore_ramdisk_under_test.inc"
int add_adb_props(const Options& parsed, const std::string& workdir) {
    const std::string magiskboot = "", ramdisk = "ramdisk";
#include "boot_patch_adb_props_under_test.inc"
    return 0;
}

struct DirectLkmBootRestore {
    std::string device, original_image, restored_image;
};
std::map<std::string, std::string> devices;
std::set<unsigned> failed_writes;
unsigned flash_calls = 0;
bool flash_boot(const std::string& device, const std::string& source) {
    const auto data = read_file(source);
    assert(data);
    const bool fail = failed_writes.count(++flash_calls) != 0;
    devices[device] = fail ? data->substr(0, 2) : *data;
    return !fail;
}
int flash_images(const std::string& original, const std::string& patched,
                 const std::optional<DirectLkmBootRestore>& companion_boot_restore,
                 bool& preserve_workdir) {
    Options parsed;
    parsed.flash = true;
    const std::string bootdevice = "primary", new_boot = patched;
    const std::string bootimage = original, rollback_bootimage = original;
#include "boot_patch_flash_under_test.inc"
    return 0;
}
int flash_restore(const std::string& original, const std::string& restored, bool& preserve_workdir) {
    Options parsed;
    parsed.flash = true;
    struct Backup { std::string stored_image; };
    const std::optional<Backup> used_backup;
    const std::string bootdevice = "primary", new_boot = restored, bootimage = original;
#include "boot_restore_flash_under_test.inc"
    return 0;
}

void test_cleanup(const fs::path& base) {
    for (bool retain : {false, true}) {
        const std::string workdir = (base / "recovery-snapshots").string();
        fs::create_directory(workdir);
        const auto original = (fs::path(workdir) / "boot.img").string();
        assert(write_file(original, "original snapshot"));
#include "boot_patch_recovery_cleanup_under_test.inc"
        preserve_workdir = retain;
        cleanup();
        if (retain)
            assert(read_file(original) == "original snapshot");
        else
            assert(!fs::exists(workdir));
    }
}

void test_options(const fs::path& base) {
    const std::string workdir = base.string();
    Options options;
    std::string selected;
    options.boot_image = "other-firmware.img";
    assert(select_kmi(options, workdir, &selected) == 0 && selected == "android15-6.6");
    assert(inspected_boot == options.boot_image && current_kmi_reads == 0);
    detected_kmi.clear();
    assert(select_kmi(options, workdir, &selected) != 0 && current_kmi_reads == 0);
    options.kmi = "android16-6.12";
    assert(select_kmi(options, workdir, &selected) == 0 && selected == options.kmi);
    options = {};
    options.ota = true;
    detected_kmi = "android15-6.6";
    assert(select_kmi(options, workdir, &selected) == 0);
    assert(inspected_boot == "/dev/block/by-name/boot_b" && current_kmi_reads == 0);
    options.ota = false;
    assert(select_kmi(options, workdir, &selected) == 0 && selected == "android14-6.1");
    assert(current_kmi_reads == 1);

    const auto replacement = (base / "replacement.img").string();
    const std::string kernel = "Linux version 6.12.1-android16-0-test" + std::string(1, '\0');
    assert(write_file(replacement, kernel));
    const auto old_cwd = fs::current_path();
    fs::current_path(base);
    assert(target_detection::parse_kmi_from_boot("", workdir, "replacement.img") ==
           "android16-6.12");
    fs::current_path(old_cwd);
    options.kernel = replacement;
    assert(select_kmi(options, workdir, &selected) == 0 && selected == "android16-6.12");
    assert(current_kmi_reads == 1);
    // init_boot cannot grow a kernel via --kernel.
    assert(replace_kernel(options, workdir) != 0);
    assert(write_file((base / "kernel").string(), "old kernel"));
    assert(replace_kernel(options, workdir) == 0);
    assert(read_file((base / "kernel").string()) == kernel);
    assert(write_file(replacement, ""));
    assert(replace_kernel(options, workdir) != 0);
    assert(read_file((base / "kernel").string()) == kernel);

    options = {};
    options.adb_debug_prop = (base / "custom properties.prop").string();
    assert(write_file(options.adb_debug_prop, "persist.test=1"));
    assert(add_adb_props(options, workdir) == 0);
    assert(read_file((base / "adb_debug.prop").string()) == "persist.test=1\n");
    options.enable_adbd = true;
    assert(add_adb_props(options, workdir) == 0);
    assert(read_file((base / "adb_debug.prop").string()) ==
           "ro.debuggable=1\nro.force.debuggable=1\nro.adb.secure=0\npersist.test=1\n");
    assert(write_file(options.adb_debug_prop, ""));
    options.enable_adbd = false;
    assert(add_adb_props(options, workdir) == 0);
    assert(read_file((base / "adb_debug.prop").string()) == "");
    fs::remove(options.adb_debug_prop);
    assert(add_adb_props(options, workdir) != 0);
}

void test_restore(const fs::path& base) {
    const auto ramdisk = (base / "ramdisk.cpio").string();
    const std::map<std::string, std::string> patched = {
        {"init", "ksuinit"}, {"init.real", "original init"}, {"kernelsu.ko", "module"},
        {"ksu_config", "config"}, {"adb_debug.prop", "props"}, {"force_debuggable", ""},
        {"vendor.rc", "preserve"}, {"stock_image.sha1", "digest"},
    };
    entries = patched;
    assert(restore_legacy_ramdisk("", base.string(), ramdisk));
    assert(entries == (std::map<std::string, std::string>{
        {"init", "original init"}, {"vendor.rc", "preserve"}}));
    for (const auto& failure : {"rm init", "mv init.real init", "rm kernelsu.ko"}) {
        entries = patched;
        fail_cpio = failure;
        assert(!restore_legacy_ramdisk("", base.string(), ramdisk));
    }
    fail_cpio.clear();
    assert(write_file(ramdisk, "archive"));
    entries = {{"init", "ksuinit"}, {"kernelsu.ko", "module"}, {".backup", ""},
               {"vendor.rc", "unknown original"}};
    const auto ambiguous = entries;
    assert(!restore_legacy_ramdisk("", base.string(), ramdisk));
    assert(entries == ambiguous && fs::exists(ramdisk));
    entries.erase("vendor.rc");
    assert(restore_legacy_ramdisk("", base.string(), ramdisk) && !fs::exists(ramdisk));
}

void test_rollback(const fs::path& base) {
    const auto original = (base / "original.img").string();
    const auto patched = (base / "patched.img").string();
    const auto other = (base / "other.img").string();
    assert(write_file(original, "original primary"));
    assert(write_file(patched, "patched primary"));
    assert(write_file(other, "original companion"));
    const DirectLkmBootRestore companion{"companion", other, patched};
    for (const auto& failures : {std::set<unsigned>{1}, {1, 2}, {2}, {2, 3}, {2, 4}, {2, 3, 4}}) {
        failed_writes = failures;
        flash_calls = 0;
        bool preserve = false;
        assert(flash_images(original, patched, companion, preserve) != 0);
        const bool first_failed = failures.count(1) != 0;
        assert(flash_calls == (first_failed ? 2U : 4U));
        assert(preserve == (failures.size() > 1));
        if ((first_failed && !failures.count(2)) || (!first_failed && !failures.count(4)))
            assert(devices["primary"] == "original primary");
        if (!first_failed && !failures.count(3))
            assert(devices["companion"] == "original companion");
    }
    for (const auto& failures : {std::set<unsigned>{}, {1}, {1, 2}}) {
        failed_writes = failures;
        flash_calls = 0;
        bool preserve = false;
        const int status = flash_restore(original, patched, preserve);
        assert((status == 0) == failures.empty());
        assert(flash_calls == (failures.empty() ? 1U : 2U));
        assert(preserve == (failures.size() == 2));
        if (failures == std::set<unsigned>{1})
            assert(devices["primary"] == "original primary");
    }
}

int main(int argc, char** argv) {
    assert(argc == 2);
    const auto base = fs::path(argv[1]) / "options";
    fs::create_directory(base);
    test_options(base);
    test_restore(base);
    test_rollback(base);
    test_cleanup(base);
    std::puts("boot_patch_options_test: all tests passed");
}
