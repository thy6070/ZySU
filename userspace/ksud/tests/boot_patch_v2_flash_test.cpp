#include <algorithm>
#include <array>
#include <cassert>
#include <cstdarg>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <optional>
#include <set>
#include <string>
#include <vector>

namespace fs = std::filesystem;

namespace {

struct WriteAttempt {
    fs::path image;
    fs::path device;
};

fs::path fixture_directory;
std::vector<WriteAttempt> attempts;
std::set<std::size_t> failing_writes;
std::string logs;

void log_message(const char* format, ...) {
    std::array<char, 4096> buffer{};
    va_list args;
    va_start(args, format);
    const int length = std::vsnprintf(buffer.data(), buffer.size(), format, args);
    va_end(args);
    assert(length >= 0 && static_cast<std::size_t>(length) < buffer.size());
    logs.append(buffer.data(), static_cast<std::size_t>(length));
    logs.push_back('\n');
}

#define LOGE(...) log_message(__VA_ARGS__)
#define LOGW(...) log_message(__VA_ARGS__)

std::string read_file(const fs::path& path) {
    std::ifstream input(path, std::ios::binary);
    assert(input);
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

void write_file(const fs::path& path, const std::string& content) {
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    output.write(content.data(), static_cast<std::streamsize>(content.size()));
    output.close();
    assert(output);
}

bool set_block_device_rw(const std::string& device) {
    assert(fs::path(device).parent_path() == fixture_directory);
    assert(fs::is_regular_file(device));
    return true;
}

bool exec_dd(const std::string& image, const std::string& device) {
    const fs::path target(device);
    assert(target.parent_path() == fixture_directory && fs::is_regular_file(target));
    attempts.push_back({image, device});
    const std::string data = read_file(image);
    const bool fail = failing_writes.count(attempts.size()) != 0;
    const bool patched = fs::path(image).filename().string().rfind("new-", 0) == 0;
    const std::size_t count = fail ? std::min<std::size_t>(patched ? 23 : 7, data.size())
                                   : data.size();
    // Simulate a block write: preserve the target's tail when the write fails
    // partway through. A failed rollback repairs fewer bytes than the original
    // failure changed, leaving observable damage until recovery succeeds.
    std::fstream output(target, std::ios::binary | std::ios::in | std::ios::out);
    output.write(data.data(), static_cast<std::streamsize>(count));
    output.close();
    assert(output);
    return !fail;
}

#include "v2_flash_under_test.inc"

struct CleanedRamdiskImage {
    bool changed;
    fs::path repacked;
};

int apply_images(const fs::path& work, const std::string& boot_device,
                 const std::string& init_boot_device, int init_boot_state) {
    // Production cleanup can only remove this fixture's private work folder.
    assert(work.is_absolute() && work.parent_path() == fixture_directory &&
           work.filename() == "work");
#include "v2_cleanup_under_test.inc"
    const fs::path input_path = work / "boot-current.img";
    const fs::path repacked = work / "new-boot.img";
    const std::optional<CleanedRamdiskImage> cleaned_init_boot =
        init_boot_state < 0
            ? std::nullopt
            : std::make_optional(CleanedRamdiskImage{init_boot_state > 0,
                                                     work / "new-init_boot.img"});
#include "v2_apply_under_test.inc"
    cleanup();
    return 0;
}

void test_scenario(const fs::path& root, const std::string& name, int init_boot_state,
                   std::set<std::size_t> failures, std::size_t expected_attempts,
                   bool preserve_snapshots) {
    fixture_directory = root / name;
    const fs::path work = fixture_directory / "work";
    fs::create_directories(work);
    const fs::path boot_device = fixture_directory / "boot.partition";
    const fs::path init_device = fixture_directory / "init_boot.partition";
    const std::string original_boot(64, 'B');
    const std::string original_init(64, 'I');
    const std::string patched_boot(64, 'P');
    const std::string patched_init(64, 'C');
    write_file(boot_device, original_boot);
    write_file(init_device, original_init);
    write_file(work / "boot-current.img", original_boot);
    write_file(work / "init_boot-input.img", original_init);
    write_file(work / "new-boot.img", patched_boot);
    write_file(work / "new-init_boot.img", patched_init);
    attempts.clear();
    logs.clear();
    failing_writes = std::move(failures);

    const int status = apply_images(work, boot_device.string(), init_device.string(), init_boot_state);
    assert((status == 0) == failing_writes.empty());
    assert(attempts.size() == expected_attempts);
    assert(attempts.front().image == work / "new-boot.img" &&
           attempts.front().device == boot_device);
    if (failing_writes.count(1) != 0) {
        // The failed first write is rolled back; init_boot is never written.
        assert(attempts[1].image == work / "boot-current.img" &&
               attempts[1].device == boot_device);
        assert(read_file(init_device) == original_init);
        assert((read_file(boot_device) == original_boot) == (failing_writes.count(2) == 0));
    } else if (!failing_writes.empty()) {
        // Restore the partially written second partition, then the first,
        // continuing even when restoring the second partition fails.
        assert(attempts[1].image == work / "new-init_boot.img" &&
               attempts[1].device == init_device);
        assert(attempts[2].image == work / "init_boot-input.img" &&
               attempts[2].device == init_device);
        assert(attempts[3].image == work / "boot-current.img" &&
               attempts[3].device == boot_device);
        assert((read_file(init_device) == original_init) == (failing_writes.count(3) == 0));
        assert((read_file(boot_device) == original_boot) == (failing_writes.count(4) == 0));
    } else {
        assert(read_file(boot_device) == patched_boot);
        assert(read_file(init_device) == (init_boot_state > 0 ? patched_init : original_init));
    }
    assert(fs::exists(work) == preserve_snapshots);
    if (preserve_snapshots) {
        assert(read_file(work / "boot-current.img") == original_boot);
        assert(read_file(work / "init_boot-input.img") == original_init);
        assert(logs.find("rollback is incomplete") != std::string::npos);
        assert(logs.find("preserving original partition snapshots") != std::string::npos);
        assert(logs.find(work.string()) != std::string::npos);
        assert(logs.find("all affected partitions were restored") == std::string::npos);
        for (const auto failure : failing_writes) {
            const auto& attempt = attempts[failure - 1];
            if (attempt.image.filename() == "boot-current.img" ||
                attempt.image.filename() == "init_boot-input.img") {
                assert(logs.find("rollback failed for " + attempt.device.string()) !=
                       std::string::npos);
                assert(logs.find(attempt.image.string()) != std::string::npos);
            }
        }
    } else if (!failing_writes.empty()) {
        assert(logs.find("all affected partitions were restored") != std::string::npos);
        assert(logs.find("rollback is incomplete") == std::string::npos);
    }
    std::cout << "PASS " << name << '\n';
}

}  // namespace

int main(int argc, char** argv) {
    assert(argc == 2);
    const fs::path root = fs::canonical(argv[1]);
    test_scenario(root, "boot_only_success", -1, {}, 1, false);
    test_scenario(root, "unchanged_init_boot_success", 0, {}, 1, false);
    test_scenario(root, "both_partitions_success", 1, {}, 2, false);
    test_scenario(root, "boot_partial_write_recovers", 1, {1}, 2, false);
    test_scenario(root, "boot_rollback_failure_preserves_snapshot", 1, {1, 2}, 2, true);
    test_scenario(root, "init_boot_partial_write_recovers_both", 1, {2}, 4, false);
    test_scenario(root, "init_boot_rollback_failure_still_restores_boot", 1, {2, 3}, 4, true);
    test_scenario(root, "boot_rollback_failure_after_init_boot_recovery", 1, {2, 4}, 4, true);
    test_scenario(root, "both_rollbacks_fail", 1, {2, 3, 4}, 4, true);
    return 0;
}
