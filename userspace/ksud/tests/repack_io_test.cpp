#include <fcntl.h>
#include <sys/stat.h>
#include <algorithm>
#include <array>
#include <cassert>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#ifdef _WIN32
#include <io.h>
using ssize_t = std::ptrdiff_t;
constexpr int O_CLOEXEC = 0;
constexpr int AT_FDCWD = -100;
#ifndef S_ISREG
#define S_ISREG(mode) (((mode) & _S_IFMT) == _S_IFREG)
#endif
#else
#include <unistd.h>
#endif

namespace fs = std::filesystem;
using std::min;
using Utf8CStr = std::string;

enum class Fault {
    None,
    Open,
    Truncate,
    Seek,
    Write,
    ZeroWrite,
    Read,
    PositionedWrite,
    Header,
    Sync,
    Close,
    Compression,
    ComponentOpen,
    ComponentStat,
    ComponentMap
};
Fault fault = Fault::None;
bool invalid_header = false;
bool short_io = false;
bool interrupt_write = false;
bool interrupt_read = false;
bool interrupt_positioned_write = false;
bool interrupt_sync = false;
int output_fd = -1;

struct component_stat {
    long long st_size = 0;
    int st_mode = 0;
};

int test_openat(int dirfd, const char* path, int flags) {
    if (fault == Fault::ComponentOpen) {
        errno = EACCES;
        return -1;
    }
#ifdef _WIN32
    assert(dirfd == AT_FDCWD);
    return _open(path, flags | _O_BINARY);
#else
    return ::openat(dirfd, path, flags);
#endif
}

int test_fstat(int fd, component_stat* info) {
    if (fault == Fault::ComponentStat) {
        errno = EIO;
        return -1;
    }
#ifdef _WIN32
    struct _stat64 actual{};
    const auto result = _fstat64(fd, &actual);
#else
    struct stat actual{};
    const auto result = ::fstat(fd, &actual);
#endif
    info->st_size = actual.st_size;
    info->st_mode = actual.st_mode;
    return result;
}

int test_open(const char* path, int flags, int mode) {
    if (fault == Fault::Open) {
        errno = EACCES;
        return -1;
    }
#ifdef _WIN32
    output_fd = _open(path, flags | _O_BINARY, mode);
#else
    output_fd = ::open(path, flags, mode);
#endif
    return output_fd;
}
int test_close(int fd) {
#ifdef _WIN32
    const int result = _close(fd);
#else
    const int result = ::close(fd);
#endif
    if (fault == Fault::Close) {
        errno = EIO;
        return -1;
    }
    return result;
}
int test_unlink(const char* path) {
#ifdef _WIN32
    return _unlink(path);
#else
    return ::unlink(path);
#endif
}
int test_access(const char* path, int mode) {
#ifdef _WIN32
    return _access(path, mode);
#else
    return ::access(path, mode);
#endif
}
off_t raw_seek(int fd, off_t offset, int whence) {
#ifdef _WIN32
    return _lseek(fd, offset, whence);
#else
    return ::lseek(fd, offset, whence);
#endif
}
off_t test_seek(int fd, off_t offset, int whence) {
    if (fault == Fault::Seek) {
        errno = EIO;
        return -1;
    }
    return raw_seek(fd, offset, whence);
}
int test_truncate(int fd, off_t size) {
    if (fault == Fault::Truncate) {
        errno = ENOSPC;
        return -1;
    }
#ifdef _WIN32
    return _chsize(fd, size);
#else
    return ::ftruncate(fd, size);
#endif
}
ssize_t test_write(int fd, const void* data, std::size_t size) {
    if (interrupt_write) {
        interrupt_write = false;
        errno = EINTR;
        return -1;
    }
    if (fault == Fault::Write) {
        errno = ENOSPC;
        return -1;
    }
    if (fault == Fault::ZeroWrite)
        return 0;
    const auto count = short_io ? min(size, std::size_t{3}) : size;
#ifdef _WIN32
    return _write(fd, data, static_cast<unsigned int>(count));
#else
    return ::write(fd, data, count);
#endif
}
ssize_t test_read_at(int fd, void* data, std::size_t size, off_t offset) {
    if (interrupt_read) {
        interrupt_read = false;
        errno = EINTR;
        return -1;
    }
    if (fault == Fault::Read) {
        errno = EIO;
        return -1;
    }
    const auto count = short_io ? min(size, std::size_t{3}) : size;
#ifdef _WIN32
    const auto original = raw_seek(fd, 0, SEEK_CUR);
    raw_seek(fd, offset, SEEK_SET);
    const auto result = _read(fd, data, static_cast<unsigned int>(count));
    raw_seek(fd, original, SEEK_SET);
    return result;
#else
    return ::pread(fd, data, count, offset);
#endif
}
ssize_t test_write_at(int fd, const void* data, std::size_t size, off_t offset) {
    if (interrupt_positioned_write) {
        interrupt_positioned_write = false;
        errno = EINTR;
        return -1;
    }
    if ((fault == Fault::Header && offset == 0) ||
        (fault == Fault::PositionedWrite && offset != 0)) {
        errno = EIO;
        return -1;
    }
    const auto count = short_io && offset != 0 ? min(size, std::size_t{3}) : size;
#ifdef _WIN32
    const auto original = raw_seek(fd, 0, SEEK_CUR);
    raw_seek(fd, offset, SEEK_SET);
    const auto result = _write(fd, data, static_cast<unsigned int>(count));
    raw_seek(fd, original, SEEK_SET);
    return result;
#else
    return ::pwrite(fd, data, count, offset);
#endif
}
int test_sync(int fd) {
    if (interrupt_sync) {
        interrupt_sync = false;
        errno = EINTR;
        return -1;
    }
    if (fault == Fault::Sync) {
        errno = EIO;
        return -1;
    }
#ifdef _WIN32
    return _commit(fd);
#else
    return ::fsync(fd);
#endif
}

struct owned_fd {
    explicit owned_fd(int fd) : value(fd) {}
    ~owned_fd() {
        if (value >= 0)
            test_close(value);
    }
    operator int() const { return value; }
    int value;
};

// Supply the platform mapping boundary while executing the production input
// validation. A failed map exposes the same empty view as mmap_data.
struct mmap_data {
    mmap_data(int fd, std::size_t size, bool) {
        if (fault == Fault::ComponentMap)
            return;
        bytes.resize(size);
#ifdef _WIN32
        const auto original = raw_seek(fd, 0, SEEK_CUR);
        raw_seek(fd, 0, SEEK_SET);
        const auto count = _read(fd, bytes.data(), static_cast<unsigned int>(size));
        raw_seek(fd, original, SEEK_SET);
#else
        const auto count = ::pread(fd, bytes.data(), size, 0);
#endif
        assert(count == static_cast<ssize_t>(size));
    }
    const uint8_t* data() const { return bytes.empty() ? nullptr : bytes.data(); }
    std::size_t size() const { return bytes.size(); }
    std::vector<uint8_t> bytes;
};

#define open test_open
#define close test_close
#define unlink test_unlink
#define access test_access
#define ftruncate test_truncate
#define lseek test_seek
#define write test_write
#define pread test_read_at
#define pwrite test_write_at
#define fsync test_sync
#include "repack_io_under_test.inc"
#undef write
#define openat test_openat
#define fstat test_fstat
#define stat component_stat
#include "repack_input_under_test.inc"
#undef openat
#undef fstat
#undef stat

// Header and source parsing are outside this fault-injection fixture. Only the
// methods needed by the production allocation/header-write stages are adapted.
constexpr int CHROMEOS_FLAG = 0;
constexpr int AMONET_FLAG = 1;
constexpr std::size_t AMONET_MICROLOADER_SZ = 1024;
constexpr std::size_t MAX_REASONABLE_BOOT_SIZE = 256 * 1024 * 1024;
constexpr int RETURN_OK = 0;
constexpr int RETURN_ERROR = 1;
constexpr const char* HEADER_FILE = "absent-header";
constexpr const char* NEW_BOOT = "new-boot.img";
struct dyn_img_hdr {
    dyn_img_hdr* clone() const { return new dyn_img_hdr; }
    void set_kernel_size(int) {}
    void set_ramdisk_size(int) {}
    void set_second_size(int) {}
    void set_dtb_size(int) {}
    void set_bootconfig_size(int) {}
    void load_hdr_file() {}
    std::size_t hdr_space() const { return 4096; }
    std::size_t hdr_size() const { return 4; }
    const char* raw_hdr() const { return "NEW!"; }
};
struct boot_img {
    explicit boot_img(const char*) {}
    std::vector<char> map = std::vector<char>(8192);
    std::unique_ptr<dyn_img_hdr> hdr = std::make_unique<dyn_img_hdr>();
    std::array<bool, 2> flags{};
};

void write_fixture_payload(int fd) {
    const repack_input component("component");
    assert(component.size() == 7);
    assert(std::memcmp(component.data(), "payload", 7) == 0);
    if (fault == Fault::Compression)
        throw std::runtime_error("compression failed");
    repack_io::test_write(fd, "old!payload", 11);
    repack_io::write_at(fd, "payload", 7, 4);
    char content[11]{};
    repack_io::read_at(fd, content, sizeof(content), 0);
    assert(std::string(content, sizeof(content)) == "old!payload");
    repack_io::zero(fd, 8);
}

// clang-format off: the dispatcher calls the preceding repack definition.
#include "repack_under_test.inc"
#include "repack_dispatch_under_test.inc"
// clang-format on
#undef open
#undef close
#undef unlink
#undef access
#undef ftruncate
#undef lseek
#undef pread
#undef pwrite
#undef fsync

int main(int argc, char** argv) {
    assert(argc == 2);
    const fs::path directory = fs::absolute(argv[1]);
    fs::current_path(directory);
    const fs::path output = directory / "patched.img";
    std::ofstream(directory / "component", std::ios::binary) << "payload";
    std::array<std::string, 4> args = {"magiskboot", "repack", "input.img", output.string()};
    std::array<char*, 4> pointers{};
    for (std::size_t i = 0; i < args.size(); ++i)
        pointers[i] = args[i].data();
    const auto run = [&]() { return dispatch_repack(pointers.size(), pointers.data()); };
    for (const auto selected :
         {Fault::Truncate, Fault::Seek, Fault::Write, Fault::ZeroWrite, Fault::Read,
          Fault::PositionedWrite, Fault::Header, Fault::Sync, Fault::Close, Fault::Compression,
          Fault::ComponentOpen, Fault::ComponentStat, Fault::ComponentMap}) {
        fault = selected;
        assert(run() != 0);
        assert(!fs::exists(output));
    }
    fault = Fault::Open;
    std::ofstream(output) << "previous image";
    assert(run() != 0 && fs::file_size(output) == 14);
    fs::remove(output);
    fault = Fault::None;
    invalid_header = true;
    assert(run() != 0 && !fs::exists(output));
    invalid_header = false;
    short_io = interrupt_write = interrupt_sync = true;
    interrupt_read = interrupt_positioned_write = true;
    assert(run() == 0 && fs::file_size(output) == 8192);
    std::ifstream input(output, std::ios::binary);
    std::string content(19, '\0');
    input.read(content.data(), content.size());
    assert(content == std::string("NEW!payload\0\0\0\0\0\0\0\0", 19));
    input.close();
    fs::remove(output);
    // A deliberately empty component stays valid without attempting mmap.
    std::ofstream(directory / "empty-component", std::ios::binary);
    fault = Fault::ComponentMap;
    const repack_input empty_component("empty-component");
    assert(empty_component.size() == 0);
    std::cout << "Repack I/O failure cleanup and successful output tests passed\n";
}
