#pragma once

#include <filesystem>
#include <map>
#include <string>
#include <vector>

#include "sumhp/config.hpp"

namespace sumhp::mount {

enum class BackendKind {
    SUMH,
    Overlayfs,
    MagicMount,
};

struct BackendStatus {
    BackendKind kind;
    std::string name;
    bool available;
    bool preferred;
    std::string detail;
};

std::vector<BackendStatus> backend_statuses();
std::string backend_kind_name(BackendKind kind);

// A regular module eligible for mounting: /data/adb/modules/<id>.
struct ModuleEntry {
    std::string id;
    std::filesystem::path path;
};

// A path-specific backend override persisted in module_rules.json. The longest
// matching absolute path wins, matching the ZySU hymo control-plane contract.
struct ModuleRule {
    std::string path;
    std::string mode;
};

using ModuleModeMap = std::map<std::string, std::string>;
using ModuleRuleMap = std::map<std::string, std::vector<ModuleRule>>;

struct MountReport {
    bool ok = false;
    int modules = 0;  // enabled modules processed
    int mounts = 0;   // committed partition mount points
    std::string backend;
    std::string detail;
};

// Enabled, mountable modules under runtime_modules_dir(): skips entries with
// no module.prop or with a disable/remove/skip_mount marker.
std::vector<ModuleEntry> enumerate_mountable_modules();

bool load_module_configuration(ModuleModeMap& modes, ModuleRuleMap& rules, std::string& error);
bool valid_module_id(const std::string& id);
bool valid_module_rule_path(const std::string& path);
bool parse_module_rules(const std::string& json, std::vector<ModuleRule>& rules,
                        std::string& error);
// Mode and rules share a single atomic commit in module_rules.json. Legacy
// module_mode.json/array entries are imported on the first successful mutation.
bool configure_module(const std::string& id, const std::string& mode,
                      const std::vector<ModuleRule>& rules, std::string& error);
bool set_module_mode(const std::string& id, const std::string& mode, std::string& error);
bool upsert_module_rule(const std::string& id, const ModuleRule& rule, std::string& error);
bool remove_module_rule(const std::string& id, const std::string& path, std::string& error);

// Resolve a module's effective backend ("overlay"|"magic"|"sumh"|"none") from
// its configured mode (modes[id], the global override config.mount_backend, or
// the auto fallback). Used by the orchestrator and to report a module's actual
// mount method. `modes` contains the saved per-module modes. Set
// allow_sumh=false when reporting a module without SUMH boot ownership.
std::string resolve_module_backend(const ModuleEntry& module, const Config& config,
                                   const ModuleModeMap& modes, bool allow_sumh = true);

// Rebuild only SUMH mappings in the init namespace. This is safe to expose
// post-boot for the SUMH hot-mount controls; Overlay/Magic remain boot-only.
// mappings_applied reports whether the new mappings were retained, including
// a Maps-only restoration failure; preflight failure preserves the old view.
bool refresh_sumh_modules(const Config& config, bool* mappings_applied = nullptr);

// Apply the boot-only module mount plan inside the init mount namespace.
MountReport mount_all_enabled(const Config& config);

// Tear down everything SUMHP mounted (enters the init mount ns).
bool unmount_all(const Config& config);

// Bootloop protection: mount_all_enabled() bumps an unconfirmed-boot counter and
// refuses once it exceeds the limit. recovery_boot_completed() clears it once a
// boot reaches boot_completed; recovery_reset() re-enables after a trip.
void recovery_boot_completed();
void recovery_reset();
std::string recovery_status_json();

}  // namespace sumhp::mount
