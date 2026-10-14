// Execute the real hot-mount marker transaction with filesystem fault injection.
#include "sumhp/config.hpp"

#include <cassert>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>

namespace realfs = std::filesystem;
namespace {
realfs::path fixture_dir;
realfs::path marker;
bool write_ok = true;
bool remove_ok = true;
bool inspect_ok = true;
bool mkdir_ok = true;
bool refresh_ok = true;
bool mappings_applied = false;
bool fail_rollback_write = false;
bool expected_unmounted = false;
int refresh_calls = 0;
int write_calls = 0;
int config_error = 0;
bool config_ok = true;
}  // namespace

namespace fixture {
using realfs::exists;
using realfs::is_regular_file;
using realfs::path;
realfs::file_status symlink_status(const path& value, std::error_code& ec) {
    if (!inspect_ok) {
        ec = std::make_error_code(std::errc::permission_denied);
        return {};
    }
    return realfs::symlink_status(value, ec);
}
bool remove(const path& value, std::error_code& ec) {
    if (!remove_ok) {
        ec = std::make_error_code(std::errc::permission_denied);
        return false;
    }
    return realfs::remove(value, ec);
}
bool create_directories(const path& value, std::error_code& ec) {
    if (!mkdir_ok) {
        ec = std::make_error_code(std::errc::permission_denied);
        return false;
    }
    return realfs::create_directories(value, ec);
}
}  // namespace fixture

namespace ksud {
bool write_file_atomic(const realfs::path& path, const std::string& content) {
    ++write_calls;
    if (!write_ok) {
        errno = ENOSPC;
        return false;
    }
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    output << content;
    return output.good();
}
}  // namespace ksud

namespace sumhp {
namespace fs = fixture;
bool read_config_file(Config& config, std::string& error) {
    errno = config_error;
    if (config_ok) {
        config.mount_backend = "sumh";
        return true;
    }
    error = "injected config read/parse failure";
    return false;
}
fs::path data_dir() {
    return fixture_dir;
}
namespace mount {
bool refresh_sumh_modules(const Config&, bool* applied) {
    ++refresh_calls;
    *applied = mappings_applied || refresh_ok;
    assert(realfs::exists(marker) == expected_unmounted);
    if (fail_rollback_write)
        write_ok = false;
    return refresh_ok;
}
}  // namespace mount
#include "sumh_hot_mount_under_test.inc"
}  // namespace sumhp

int main() {
    using namespace sumhp;
    fixture_dir = realfs::temp_directory_path() /
                  ("sumh-hot-mount-" +
                   std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    marker = fixture_dir / "run" / "hot_unmounted" / "example";
    Config config;
    std::string error;
    assert(read_mount_config(config, error) && config.mount_backend == "sumh");
    config_ok = false;
    config_error = ENOENT;
    assert(read_mount_config(config, error) && config.mount_backend == "auto" && error.empty());
    for (int failure : {0, EACCES, EIO}) {
        config_error = failure;
        assert(!read_mount_config(config, error) && !error.empty());
    }

    // State preparation failures must never invoke a mapping rebuild.
    expected_unmounted = true;
    inspect_ok = false;
    assert(!change_module_mount_state("example", true, config, error));
    inspect_ok = true;
    mkdir_ok = false;
    assert(!change_module_mount_state("example", true, config, error));
    mkdir_ok = true;
    write_ok = false;
    assert(!change_module_mount_state("example", true, config, error));
    assert(refresh_calls == 0 && !realfs::exists(marker));
    write_ok = true;

    // Rebuild observes the requested state and successful requests retain it.
    assert(change_module_mount_state("example", true, config, error));
    assert(refresh_calls == 1 && realfs::is_regular_file(marker));
    const int previous_writes = write_calls;
    assert(change_module_mount_state("example", true, config, error));
    assert(write_calls == previous_writes && refresh_calls == 2);
    remove_ok = false;
    assert(!change_module_mount_state("example", false, config, error));
    assert(refresh_calls == 2 && realfs::exists(marker));
    remove_ok = true;
    expected_unmounted = false;
    assert(change_module_mount_state("example", false, config, error));
    assert(refresh_calls == 3 && !realfs::exists(marker));

    // Compile/apply failure restores the prior selection in both directions.
    refresh_ok = false;
    expected_unmounted = true;
    assert(!change_module_mount_state("example", true, config, error));
    assert(!realfs::exists(marker));
    assert(ksud::write_file_atomic(marker, "previous\n"));
    expected_unmounted = false;
    assert(!change_module_mount_state("example", false, config, error));
    assert(realfs::is_regular_file(marker));

    // If restoration itself fails, the caller gets a distinct diagnostic.
    fail_rollback_write = true;
    assert(!change_module_mount_state("example", false, config, error));
    assert(!realfs::exists(marker));
    assert(error.find("previous hot-mount state could not be restored") != std::string::npos);
    write_ok = true;
    fail_rollback_write = false;

    // Maps restoration can fail after committing mappings. Keep the marker
    // consistent with those mappings instead of undoing only the saved choice.
    mappings_applied = true;
    expected_unmounted = true;
    assert(!change_module_mount_state("example", true, config, error));
    assert(realfs::is_regular_file(marker));
    assert(error.find("requested hot-mount state retained") != std::string::npos);
    expected_unmounted = false;
    assert(!change_module_mount_state("example", false, config, error));
    assert(!realfs::exists(marker));
    mappings_applied = false;

    // A directory at the marker path is not mistaken for an unmounted module.
    realfs::create_directory(marker);
    const int previous_refreshes = refresh_calls;
    assert(!change_module_mount_state("example", false, config, error));
    assert(refresh_calls == previous_refreshes && realfs::is_directory(marker));
    realfs::remove_all(fixture_dir);
    std::cout << "sumh_hot_mount_test: all checks passed\n";
}
