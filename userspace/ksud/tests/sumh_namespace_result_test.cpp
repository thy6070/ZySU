// The Windows fixture models the pipe and child branch; Linux additionally
// exercises actual fork/read/write/waitpid to prove child diagnostics survive.
#include <algorithm>
#include <cassert>
#include <cerrno>
#include <climits>
#include <cstring>
#include <functional>
#include <iostream>
#include <string>
#include <vector>

namespace {
bool switch_ok = true;
bool pipe_ok = true;
}  // namespace
namespace ksud {
bool switch_mnt_ns(int) {
    return switch_ok;
}
}  // namespace ksud
namespace logging {
enum class Level { Error };
}
void mlog(const std::string&, logging::Level) {}

#ifdef _WIN32
using pid_t = int;
#define PIPE_BUF 4096
#define O_CLOEXEC 0
namespace {
std::vector<char> pipe_bytes;
std::size_t read_cursor = 0;
bool interrupted_write = false;
bool interrupted_read = false;
struct ChildExit {
    int status;
};
}  // namespace
int pipe2(int* fds, int) {
    if (!pipe_ok) {
        errno = EMFILE;
        return -1;
    }
    pipe_bytes.clear();
    read_cursor = 0;
    fds[0] = 5;
    fds[1] = 6;
    return 0;
}
int close(int) {
    return 0;
}
int fork() {
    return 0;
}
int waitpid(int, int*, int) {
    return -1;
}
#define WIFEXITED(value) ((value) == 0)
#define WEXITSTATUS(value) (value)
#define _exit maps_test_exit
[[noreturn]] void maps_test_exit(int status) {
    throw ChildExit{status};
}
int write(int, const void* data, std::size_t count) {
    if (interrupted_write) {
        interrupted_write = false;
        errno = EINTR;
        return -1;
    }
    const auto* bytes = static_cast<const char*>(data);
    const auto written = std::min(count, std::size_t{19});
    pipe_bytes.insert(pipe_bytes.end(), bytes, bytes + written);
    return static_cast<int>(written);
}
int read(int, void* data, std::size_t count) {
    if (interrupted_read) {
        interrupted_read = false;
        errno = EINTR;
        return -1;
    }
    const auto received = std::min({count, pipe_bytes.size() - read_cursor, std::size_t{23}});
    std::memcpy(data, pipe_bytes.data() + read_cursor, received);
    read_cursor += received;
    return static_cast<int>(received);
}
// Route the overload's inner call through a fake child-exit boundary. The
// production one-argument helper still runs its fork/namespace/exit branches.
#define run_in_init_mount_ns run_in_init_mount_ns_child
#include "sumh_namespace_child_under_test.inc"
#undef run_in_init_mount_ns
bool run_in_init_mount_ns(const std::function<bool()>& fn) {
    try {
        return run_in_init_mount_ns_child(fn);
    } catch (const ChildExit& exit) {
        return exit.status == 0;
    }
}
#include "sumh_namespace_pipe_under_test.inc"
#else
#include <fcntl.h>
#include <sys/wait.h>
#include <unistd.h>
#include "sumh_namespace_result_under_test.inc"
#endif

int main() {
    std::string error;
#ifdef _WIN32
    interrupted_read = interrupted_write = true;
#endif
    const auto invoke = [&](const std::function<bool()>& fn) {
        return run_in_init_mount_ns(fn, error);
    };
    assert(!invoke([&]() {
        error = "missing /target";
        errno = ENOENT;
        return false;
    }));
    assert(errno == ENOENT && error == "missing /target");
    assert(!invoke([&]() {
        error.assign(5000, 'x');
        errno = EEXIST;
        return false;
    }));
    assert(errno == EEXIST && error == std::string(1023, 'x'));
    assert(invoke([]() { return true; }));
    assert(error.empty());
    switch_ok = false;
    assert(!invoke([]() {
        assert(false);
        return true;
    }));
    assert(!error.empty());
    switch_ok = true;
#ifdef _WIN32
    pipe_ok = false;
    assert(!invoke([]() { return true; }));
    assert(errno == EMFILE && error.find("pipe") != std::string::npos);
#else
    (void)pipe_ok;
#endif
    std::cout << "sumh_namespace_result_test: all checks passed\n";
}
