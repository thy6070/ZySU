// Execute production backend selection over real module trees. No mounts or
// root privileges are needed; directory symlink cases also run when permitted.
#include "mount/backend.hpp"
#include "mount/partition_alias.hpp"

#include <algorithm>
#include <cassert>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

namespace {
namespace fs = std::filesystem;
bool overlay_available = true;
bool sumh_available = true;
}  // namespace

namespace sumhp {
namespace logging {
enum class Level { Info, Warning, Error };
}
namespace sumh {
enum class Status { Available, Missing };
struct Version {
    Status status;
};
Version version_info() {
    return {sumh_available ? Status::Available : Status::Missing};
}
}  // namespace sumh
}  // namespace sumhp

namespace sumhp::mount {
namespace fs = std::filesystem;
namespace fsutil {
void mlog(const std::string&, logging::Level = logging::Level::Info) {}
#include "sumh_partitions_under_test.inc"
}  // namespace fsutil
namespace {
bool proc_filesystems_has(const std::string&) {
    return overlay_available;
}
}  // namespace
#include "sumh_backend_selection_under_test.inc"
}  // namespace sumhp::mount

int main() {
    using namespace sumhp::mount;
    const fs::path root =
        fs::temp_directory_path() /
        ("sumh-selection-" +
         std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    const ModuleEntry module{"sample", root};
    sumhp::Config config;
    config.overlayfs_enabled = config.magic_mount_enabled = true;
    config.mount_backend = "auto";
    ModuleModeMap modes;

    assert(resolve_module_backend(module, config, modes) == "none");
    fs::create_directories(root / "system" / "etc");
    std::ofstream(root / "system" / "etc" / "sample.conf").put('x');
    assert(resolve_module_backend(module, config, modes) == "overlay");

    // Root files cannot be represented by leaf overlays, including an explicit
    // request for OverlayFS. Fallback must preserve the complete module tree.
    std::ofstream(root / "system" / "build.prop").put('x');
    assert(resolve_module_backend(module, config, modes) == "magic");
    modes[module.id] = "overlay";
    assert(resolve_module_backend(module, config, modes) == "magic");
    config.magic_mount_enabled = false;
    assert(resolve_module_backend(module, config, modes) == "none");
    config.magic_mount_enabled = true;
    fs::remove(root / "system" / "build.prop");

    // Configured partitions extend defaults consistently and are deduplicated.
    config.partitions = {"custom", "vendor", "custom", ""};
    const auto partitions = fsutil::managed_partitions(config.partitions);
    assert(partitions.size() == fsutil::managed_partitions().size() + 1);
    assert(std::count(partitions.begin(), partitions.end(), "custom") == 1);
    assert(std::count(partitions.begin(), partitions.end(), "system") == 1);
    fs::create_directories(root / "custom");
    std::ofstream(root / "custom" / "default.prop").put('x');
    assert(resolve_module_backend(module, config, modes) == "magic");
    fs::remove(root / "custom" / "default.prop");

    // A normalized installer alias is not a replacement entry. Its root files
    // still count, while arbitrary symlinks must use Magic's replacement path.
    fs::create_directories(root / "vendor" / "etc");
    std::error_code link_error;
    fs::create_directory_symlink(fs::path("..") / "vendor", root / "system" / "vendor", link_error);
    if (!link_error) {
        assert(resolve_module_backend(module, config, modes) == "overlay");
        std::ofstream(root / "vendor" / "build.prop").put('x');
        assert(resolve_module_backend(module, config, modes) == "magic");
        fs::remove(root / "vendor" / "build.prop");
        fs::create_directory_symlink(fs::path("..") / "vendor" / "etc",
                                     root / "system" / "custom-link");
        assert(resolve_module_backend(module, config, modes) == "magic");
        fs::remove(root / "system" / "custom-link");
    } else {
        std::cout << "symlink fixtures unavailable: " << link_error.message() << '\n';
    }

    modes[module.id] = "sumh";
    assert(resolve_module_backend(module, config, modes) == "sumh");
    assert(resolve_module_backend(module, config, modes, false) == "overlay");
    sumh_available = false;
    assert(resolve_module_backend(module, config, modes) == "overlay");
    overlay_available = false;
    assert(resolve_module_backend(module, config, modes) == "magic");
    config.mount_backend = "none";
    assert(resolve_module_backend(module, config, modes) == "none");

    fs::remove_all(root);
    std::cout << "sumh_backend_selection_test: all checks passed\n";
}
