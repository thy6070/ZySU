// Exercise actual planner functions; only filesystem/transport boundaries are doubles.
#include <algorithm>
#include <cassert>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <utility>
#include <vector>

namespace realfs = std::filesystem;
namespace fixture {
std::map<std::string, std::string> links;
std::set<std::string> directories;
std::set<std::string> whiteouts, opaque, metadata_errors;
std::map<std::string, std::error_code> resolution_errors;
std::vector<std::string> operations;
realfs::path root;
bool available = true;
std::string key(const realfs::path& path) {
    return path.generic_string();
}
bool virtual_path(const realfs::path& path) {
    const auto value = key(path);
    return value == "/" || value.rfind("/system", 0) == 0 || value.rfind("/vendor", 0) == 0 ||
           value.rfind("/product", 0) == 0 || value.rfind("/extra", 0) == 0;
}
std::string resolved(const realfs::path& path) {
    auto value = key(path);
    while (value.size() > 1 && value.back() == '/')
        value.pop_back();
    for (const auto& [from, to] : links) {
        if (!virtual_path(from))
            continue;
        if (value == from ||
            (value.size() > from.size() && value.compare(0, from.size(), from) == 0 &&
             value[from.size()] == '/'))
            return to + value.substr(from.size());
    }
    return value;
}
void file(const std::string& name) {
    const auto path = root / name;
    realfs::create_directories(path.parent_path());
    std::ofstream(path) << "module";
}
void reset() {
    realfs::remove_all(root);
    realfs::create_directories(root);
    links = {{"/system/vendor", "/vendor"}};
    directories = {"/",           "/system",         "/system/etc", "/vendor",
                   "/vendor/etc", "/vendor/etc/sub", "/extra",      "/extra/etc"};
    whiteouts.clear();
    opaque.clear();
    metadata_errors.clear();
    operations.clear();
    resolution_errors.clear();
    available = true;
}
}  // namespace fixture
namespace fs {
using realfs::file_status;
using realfs::file_type;
using realfs::path;
bool is_directory(file_status status) {
    return realfs::is_directory(status);
}
bool exists(file_status status) {
    return realfs::exists(status);
}
using realfs::is_symlink;
bool is_directory(const path& path, std::error_code& ec) {
    ec.clear();
    if (fixture::resolution_errors.count(fixture::key(path))) {
        ec = fixture::resolution_errors.at(fixture::key(path));
        return false;
    }
    if (fixture::virtual_path(path))
        return fixture::directories.count(fixture::resolved(path));
    return realfs::is_directory(path, ec);
}
bool exists(const path& path, std::error_code& ec) {
    ec.clear();
    if (fixture::resolution_errors.count(fixture::key(path))) {
        ec = fixture::resolution_errors.at(fixture::key(path));
        return false;
    }
    if (fixture::virtual_path(path))
        return fixture::directories.count(fixture::resolved(path));
    return realfs::exists(path, ec);
}
path canonical(const path& path, std::error_code& ec) {
    ec.clear();
    if (fixture::resolution_errors.count("canonical:" + fixture::key(path))) {
        ec = fixture::resolution_errors.at("canonical:" + fixture::key(path));
        return {};
    }
    if (fixture::virtual_path(path))
        return fixture::resolved(path);
    return realfs::canonical(path, ec);
}
file_status symlink_status(const path& path, std::error_code& ec) {
    ec.clear();
    if (fixture::links.count(fixture::key(path)))
        return file_status(file_type::symlink);
    if (fixture::virtual_path(path))
        return file_status(fixture::directories.count(fixture::key(path)) ? file_type::directory
                                                                          : file_type::not_found);
    return realfs::symlink_status(path, ec);
}
path read_symlink(const path& path, std::error_code& ec) {
    ec.clear();
    const auto it = fixture::links.find(fixture::key(path));
    if (it != fixture::links.end())
        return it->second;
    ec = std::make_error_code(std::errc::invalid_argument);
    return {};
}
using realfs::create_directories;
using realfs::remove;
struct directory_entry {
    realfs::path value;
    realfs::path path() const { return value; }
    file_status symlink_status(std::error_code& ec) const { return fs::symlink_status(value, ec); }
    bool is_directory(std::error_code& ec) const { return fs::is_directory(value, ec); }
};
struct recursive_directory_iterator {
    realfs::recursive_directory_iterator value;
    mutable directory_entry entry;
    recursive_directory_iterator() = default;
    recursive_directory_iterator(const path& root, std::error_code& ec) : value(root, ec) {}
    const directory_entry* operator->() const {
        entry.value = value->path();
        return &entry;
    }
    bool operator!=(const recursive_directory_iterator& other) const {
        return value != other.value;
    }
    void increment(std::error_code& ec) { value.increment(ec); }
    void disable_recursion_pending() { value.disable_recursion_pending(); }
    int depth() const { return value.depth(); }
};
}  // namespace fs
namespace sumhp::sumh {
bool is_available() {
    return fixture::available;
}
bool add_rule(const std::string& path, const std::string&, int) {
    fixture::operations.push_back("add " + path);
    return true;
}
bool add_merge_rule(const std::string& path, const std::string&) {
    fixture::operations.push_back("merge " + path);
    return true;
}
bool hide_path(const std::string& path) {
    fixture::operations.push_back("hide " + path);
    return true;
}
}  // namespace sumhp::sumh
namespace planner {
namespace logging {
enum class Level { Debug, Error };
}
void mlog(const std::string&, logging::Level = logging::Level::Debug) {}
struct ModuleRule {
    std::string path, mode;
};
struct ModuleEntry {
    std::string id;
    fs::path path;
};
struct Config {
    std::vector<std::string> partitions;
};
using ModuleRuleMap = std::map<std::string, std::vector<ModuleRule>>;
namespace fsutil {
std::vector<std::string> managed_partitions(const std::vector<std::string>& extras) {
    std::vector<std::string> result{"system", "vendor", "product"};
    for (const auto& part : extras)
        if (std::find(result.begin(), result.end(), part) == result.end())
            result.push_back(part);
    return result;
}
bool directory_is_opaque(const std::string& path, bool& opaque) {
    opaque = fixture::opaque.count(fixture::key(path)) ||
             realfs::exists(realfs::path(path) / ".replace");
    return !fixture::metadata_errors.count(fixture::key(path));
}
bool path_is_whiteout(const std::string& path, bool& whiteout) {
    whiteout = fixture::whiteouts.count(fixture::key(path));
    return !fixture::metadata_errors.count(fixture::key(path));
}
std::optional<fs::path> module_partition_alias(const fs::path& path) {
    const auto it = fixture::links.find(fixture::key(path));
    if (it != fixture::links.end() && path.parent_path().filename() == "system" &&
        it->second == "../" + path.filename().generic_string())
        return path.parent_path().parent_path() / path.filename();
    return std::nullopt;
}
}  // namespace fsutil
struct stat {
    unsigned st_mode = 0;
};
int lstat(const fs::path::value_type* name, struct stat* st) {
    std::error_code ec;
    const auto status = fs::symlink_status(fs::path(name), ec);
    if (ec)
        return -1;
    st->st_mode = static_cast<unsigned>(status.type());
    return 0;
}
#define S_ISDIR(mode) ((mode) == static_cast<unsigned>(fs::file_type::directory))
#define S_ISREG(mode) ((mode) == static_cast<unsigned>(fs::file_type::regular))
#define S_ISLNK(mode) ((mode) == static_cast<unsigned>(fs::file_type::symlink))
fs::path active_file() {
    return fixture::root / "run" / "active";
}
bool write_boot_marker(const fs::path& path) {
    std::ofstream(path) << "active";
    return true;
}
#include "sumh_planner_under_test.inc"
}  // namespace planner
namespace whiteout_test {
namespace fsutil = ::whiteout_test;
using ssize_t = long long;
struct stat {
    unsigned st_mode = 0;
    unsigned st_rdev = 0;
    long long st_size = 0;
};
constexpr unsigned regular = static_cast<unsigned>(fs::file_type::regular);
constexpr unsigned character = static_cast<unsigned>(fs::file_type::character);
#define S_ISCHR(mode) ((mode) == static_cast<unsigned>(fs::file_type::character))
#ifndef ENODATA
#define ENODATA 61
#endif
unsigned makedev(unsigned major, unsigned minor) {
    return major * 256 + minor;
}
struct stat current;
int stat_error = 0, attribute_error = ENODATA, attribute_calls = 0;
int lstat(const char*, struct stat* result) {
    if (stat_error) {
        errno = stat_error;
        return -1;
    }
    *result = current;
    return 0;
}
ssize_t lgetxattr(const char*, const char*, void*, std::size_t) {
    ++attribute_calls;
    if (attribute_error) {
        errno = attribute_error;
        return -1;
    }
    return 0;
}
namespace logging {
enum class Level { Error };
}
void mlog(const std::string&, logging::Level) {}
[[noreturn]] void scan_error(const std::string&) {
    throw std::runtime_error("metadata failure");
}
enum class NType { Regular, Directory, Symlink, Whiteout };
#include "sumh_whiteout_under_test.inc"
void run() {
    bool whiteout = false;
    current = {character, 0, 0};
    assert(path_is_whiteout("device", whiteout) && whiteout && attribute_calls == 0);
    assert(type_from_lstat("device", current) == NType::Whiteout);
    current = {character, 1, 0};
    assert(path_is_whiteout("other-device", whiteout) && !whiteout);
    current = {regular, 0, 0};
    attribute_error = 0;
    assert(path_is_whiteout("attribute", whiteout) && whiteout);
    assert(type_from_lstat("attribute", current) == NType::Whiteout);
    attribute_error = ENODATA;
    assert(path_is_whiteout("empty", whiteout) && !whiteout);
    assert(type_from_lstat("empty", current) == NType::Regular);
    attribute_error = EOPNOTSUPP;
    assert(path_is_whiteout("unsupported", whiteout) && !whiteout);
    attribute_error = EPERM;
    assert(!path_is_whiteout("denied", whiteout));
    bool threw = false;
    try {
        (void)type_from_lstat("denied", current);
    } catch (const std::runtime_error&) {
        threw = true;
    }
    assert(threw);
    current.st_size = 1;
    attribute_calls = 0;
    assert(path_is_whiteout("nonempty", whiteout) && !whiteout && attribute_calls == 0);
    assert(type_from_lstat("nonempty", current) == NType::Regular);
    stat_error = EACCES;
    assert(!path_is_whiteout("missing", whiteout));
}
}  // namespace whiteout_test

int main() {
    whiteout_test::run();
    using namespace planner;
    fixture::root = realfs::temp_directory_path() /
                    ("sumh-planner-fixture-" +
                     std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    fixture::reset();
    std::string error;
    std::vector<ModuleRule> rules;
    assert(resolve_module_rules({{"/vendor/etc", "none"}, {"/vendor/etc", "sumh"}}, rules, error));
    assert(rules.size() == 1 && effective_mode("/vendor/etc/a", rules) == "sumh");
    assert(resolve_module_rules({{"/system/vendor/etc", "none"}, {"/vendor/etc", "none"}}, rules,
                                error));
    assert(rules.size() == 1 && rules[0].path == "/vendor/etc");
    assert(!resolve_module_rules({{"/system/vendor/etc", "none"}, {"/vendor/etc", "hide"}}, rules,
                                 error));
    assert(error.find("conflicting") != std::string::npos);
    assert(resolve_module_rules({{"/system/vendor/", "none"}}, rules, error));
    assert(effective_mode("/vendor", rules) == "sumh");
    assert(effective_mode("/vendor/etc/a", rules) == "none");
    assert(resolve_module_rules({{"/system/vendor/ Missing ", "none"}}, rules, error));
    assert(rules[0].path == "/vendor/ Missing ");

    fixture::file("m/vendor/etc/a");
    fixture::file("m/vendor/etc/sub/keep");
    ModuleMountPlan plan;
    std::vector<ModuleEntry> modules{{"m", fixture::root / "m"}};
    Config config;
    ModuleRuleMap saved{
        {"m", {{"/system/vendor/etc", "none"}, {"/system/vendor/etc/sub/keep", "sumh"}}}};
    assert(prepare_modules(modules, config, saved, plan, error));
    assert(plan.mappings.size() == 1 && plan.mappings[0].path == "/vendor/etc/sub/keep" &&
           !plan.mappings[0].merge);
    assert(fixture::operations.empty());
    saved["m"] = {{"/system/vendor", "none"}};
    assert(prepare_modules(modules, config, saved, plan, error));
    assert(plan.mappings.empty());
    saved.clear();
    assert(prepare_modules(modules, config, saved, plan, error));
    assert(plan.mappings.size() == 1 && plan.mappings[0].merge);

    // Both device and attribute whiteouts enter the shared path classifier;
    // either prevents bulk MERGE and is emitted through existing HIDE.
    fixture::whiteouts.insert(fixture::key(fixture::root / "m/vendor/etc/a"));
    assert(prepare_modules(modules, config, saved, plan, error));
    assert(plan.hide.count("/vendor/etc/a") == 1);
    assert(std::none_of(plan.mappings.begin(), plan.mappings.end(),
                        [](const auto& m) { return m.path == "/vendor/etc"; }));
    assert(apply_modules(plan));
    assert(std::find(fixture::operations.begin(), fixture::operations.end(),
                     "hide /vendor/etc/a") != fixture::operations.end());

    // A failed preflight does not overwrite a previously prepared plan or emit ioctls.
    fixture::operations.clear();
    const auto old_count = plan.mappings.size();
    fixture::opaque.insert(fixture::key(fixture::root / "m/vendor/etc/sub"));
    assert(!prepare_modules(modules, config, saved, plan, error));
    assert(plan.mappings.size() == old_count && plan.hide.count("/vendor/etc/a"));
    assert(fixture::operations.empty());
    saved["m"] = {{"/vendor/etc/sub", "none"}};
    assert(prepare_modules(modules, config, saved, plan, error));
    fixture::opaque.clear();
    fixture::metadata_errors.insert(fixture::key(fixture::root / "m/vendor/etc/a"));
    assert(!prepare_modules(modules, config, {}, plan, error));
    fixture::metadata_errors.clear();
    const auto previous_hide = plan.hide;
    fixture::resolution_errors["/system/vendor/etc"] =
        std::make_error_code(std::errc::permission_denied);
    assert(
        !prepare_modules(modules, config, {{"m", {{"/system/vendor/etc", "none"}}}}, plan, error));
    assert(error.find("cannot resolve") != std::string::npos && plan.hide == previous_hide);
    fixture::resolution_errors.clear();
    fixture::resolution_errors["canonical:/vendor/etc"] = std::make_error_code(std::errc::io_error);
    assert(!prepare_modules(modules, config, {}, plan, error));
    assert(plan.hide == previous_hide && fixture::operations.empty());
    fixture::resolution_errors.clear();
    fixture::resolution_errors["/vendor/etc"] =
        std::make_error_code(std::errc::too_many_symbolic_link_levels);
    assert(!prepare_modules(modules, config, {}, plan, error));
    fixture::resolution_errors.clear();
    fixture::links[fixture::key(fixture::root / "m/vendor")] = "../elsewhere";
    assert(!prepare_modules(modules, config, {}, plan, error));
    assert(error.find("real directory") != std::string::npos && fixture::operations.empty());

    // Installer-created relative aliases are compiled only through /vendor.
    fixture::reset();
    fixture::file("m/vendor/etc/a");
    fixture::file("m/system/vendor");
    fixture::links[fixture::key(fixture::root / "m/system/vendor")] = "../vendor";
    assert(prepare_modules(modules, config, {}, plan, error));
    assert(plan.mappings.size() == 1 && plan.mappings[0].path == "/vendor/etc");
    // An arbitrary replacement symlink remains an error rather than being followed.
    fixture::links[fixture::key(fixture::root / "m/system/vendor")] = "../other";
    assert(!prepare_modules(modules, config, {}, plan, error));

    fixture::reset();
    fixture::file("m/vendor/etc/a");
    fixture::file("m/extra/etc/b");
    config.partitions = {"extra"};
    assert(prepare_modules(modules, config, {}, plan, error));
    assert(plan.mappings.size() == 2);
    fixture::available = false;
    assert(!prepare_modules(modules, config, {}, plan, error));
    realfs::remove_all(fixture::root);
    std::cout << "SUMH native planner regressions passed\n";
}
