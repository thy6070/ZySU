#include <fcntl.h>
#include <algorithm>
#include <array>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>
#ifdef _WIN32
#include <io.h>
using ssize_t = std::ptrdiff_t;
constexpr int O_CLOEXEC = 0;

int fixture_open(const char* path, int flags, int mode) {
    return _open(path, flags | _O_BINARY, mode);
}
ssize_t fixture_read_at(int fd, void* data, std::size_t size, off_t offset) {
    const auto original = _lseek(fd, 0, SEEK_CUR);
    if (original < 0 || _lseek(fd, offset, SEEK_SET) < 0)
        return -1;
    const auto count = _read(fd, data, static_cast<unsigned int>(size));
    if (_lseek(fd, original, SEEK_SET) < 0)
        return -1;
    return count;
}
ssize_t fixture_write_at(int fd, const void* data, std::size_t size, off_t offset) {
    const auto original = _lseek(fd, 0, SEEK_CUR);
    if (original < 0 || _lseek(fd, offset, SEEK_SET) < 0)
        return -1;
    const auto count = _write(fd, data, static_cast<unsigned int>(size));
    if (_lseek(fd, original, SEEK_SET) < 0)
        return -1;
    return count;
}
#define open fixture_open
#define close _close
#define unlink _unlink
#define ftruncate _chsize
#define lseek _lseek
#define write _write
#define pread fixture_read_at
#define pwrite fixture_write_at
#define fsync _commit
#else
#include <unistd.h>
#endif

// The production I/O helpers and DHTB structure/branches run unchanged. Windows
// adapters above only translate POSIX calls to real binary-mode CRT file I/O.
#include "repack_dhtb_header_under_test.inc"
#include "repack_io_under_test.inc"

namespace fs = std::filesystem;
constexpr int DHTB_FLAG = 0;
constexpr int SEANDROID_FLAG = 1;
constexpr int LG_BUMP_FLAG = 2;
constexpr std::size_t SHA256_DIGEST_SIZE = 32;
constexpr std::size_t AOSP_BYTES = 8192;
static_assert(sizeof(dhtb_hdr) == 512);

void require(bool condition, const std::string& message) {
    if (!condition)
        throw std::runtime_error(message);
}

struct byte_view {
    byte_view(const void* bytes, std::size_t count)
        : data(static_cast<const char*>(bytes)), size(count) {}
    const char* data;
    std::size_t size;
};
struct byte_data {
    byte_data(void* bytes, std::size_t count)
        : data(static_cast<std::uint8_t*>(bytes)), size(count) {}
    std::uint8_t* data;
    std::size_t size;
};

std::vector<char> expected_payload;
std::size_t hash_calls = 0;
const std::array<std::uint8_t, SHA256_DIGEST_SIZE> fixture_digest = {
    0x96, 0x35, 0xFA, 0x6A, 0x3F, 0xBA, 0x7C, 0x8E, 0x01, 0x46, 0x2D, 0x88, 0x39, 0xAF, 0xE2, 0xDA,
    0x51, 0x09, 0xC8, 0xFF, 0x71, 0x37, 0x11, 0xD5, 0x63, 0xC4, 0x90, 0x82, 0x4A, 0xAC, 0xD3, 0x27};

// SHA-256 itself is deliberately a substitute: this fixture checks the exact
// bytes and length passed to it, then checks that its digest reaches the header.
void sha256_hash(byte_view input, byte_data output) {
    require(input.size == expected_payload.size(), "DHTB hash includes the wrong payload length");
    require(std::equal(expected_payload.begin(), expected_payload.end(), input.data),
            "DHTB hash does not cover the complete AOSP image and Samsung marker");
    require(output.size == fixture_digest.size(), "DHTB checksum output length changed");
    std::copy(fixture_digest.begin(), fixture_digest.end(), output.data);
    ++hash_calls;
}

dhtb_hdr original_header() {
    dhtb_hdr header{};
    std::memcpy(header.magic.data(), DHTB_MAGIC, header.magic.size());
    header.checksum.fill(0xA5);
    header.size = 7;
    header.padding.fill('\x6B');
    return header;
}

void repack_dhtb_fixture(const fs::path& path, std::size_t padding) {
    const auto original = original_header();
    struct {
        std::array<bool, 3> flags{true, true, false};
        std::vector<char> map = std::vector<char>(sizeof(dhtb_hdr));
    } boot;
    std::memcpy(boot.map.data(), &original, sizeof(original));
    struct {
        std::uint32_t header;
        std::uint32_t tail;
    } off{};
    repack_io::Output output(path.string());
    const int fd = output.fd();
    repack_io::write(fd, boot.map.data(), boot.map.size());
    off.header = repack_io::seek(fd, 0, SEEK_CUR);
    repack_io::write(fd, expected_payload.data(), AOSP_BYTES);

    // These writes and off.tail calculation are extracted from repack, so the
    // size under test includes the marker exactly as the production layout does.
#include "repack_dhtb_markers_under_test.inc"
    require(off.tail == 8724, "DHTB marker placement changed");
    const std::vector<char> trailer(padding, '\x7D');
    repack_io::write(fd, trailer.data(), trailer.size());
    require(repack_io::seek(fd, 0, SEEK_END) == static_cast<off_t>(8724 + padding),
            "fixture output has an unexpected file length");
#include "repack_dhtb_under_test.inc"
    output.finish();
}

#ifdef _WIN32
#undef open
#undef close
#undef unlink
#undef ftruncate
#undef lseek
#undef write
#undef pread
#undef pwrite
#undef fsync
#endif

void verify_fixture(const fs::path& path, std::size_t padding) {
    expected_payload.resize(AOSP_BYTES);
    for (std::size_t i = 0; i < AOSP_BYTES; ++i)
        expected_payload[i] = static_cast<char>((i * 31 + 17) % 251);
    std::memcpy(expected_payload.data(), "ANDROID!", 8);
    const std::string marker = "SEANDROIDENFORCE";
    expected_payload.insert(expected_payload.end(), marker.begin(), marker.end());
    expected_payload.insert(expected_payload.end(), 4, '\xFF');
    hash_calls = 0;
    repack_dhtb_fixture(path, padding);

    require(fs::file_size(path) == 8724 + padding, "repack changed the output file length");
    std::ifstream stream(path, std::ios::binary);
    const std::vector<char> actual{std::istreambuf_iterator<char>(stream),
                                   std::istreambuf_iterator<char>()};
    require(actual.size() == 8724 + padding, "could not read the complete output image");
    dhtb_hdr header{};
    std::memcpy(&header, actual.data(), sizeof(header));
    require(header.size == AOSP_BYTES + 20, "DHTB header size counts the marker twice");
    require(hash_calls == 1, "DHTB payload was not hashed exactly once");
    auto expected_header = original_header();
    expected_header.size = expected_payload.size();
    std::copy(fixture_digest.begin(), fixture_digest.end(), expected_header.checksum.begin());
    require(std::memcmp(&header, &expected_header, sizeof(header)) == 0,
            "DHTB checksum or preserved header bytes are incorrect");
    require(std::equal(expected_payload.begin(), expected_payload.end(), actual.begin() + 512),
            "repack modified payload bytes");
    require(
        std::all_of(actual.begin() + 8724, actual.end(), [](char byte) { return byte == '\x7D'; }),
        "repack modified tail padding");
}

int main(int argc, char** argv) {
    if (argc != 2)
        return 2;
    const fs::path directory = fs::absolute(argv[1]);
    int failures = 0;
    for (const std::size_t padding : {0, 64}) {
        const auto path = directory / ("dhtb-" + std::to_string(padding) + ".img");
        try {
            verify_fixture(path, padding);
            std::cout << "DHTB fixture passed with " << padding << " tail padding bytes\n";
        } catch (const std::exception& error) {
            std::cerr << "DHTB fixture failed with " << padding
                      << " tail padding bytes: " << error.what() << '\n';
            ++failures;
        }
        fs::remove(path);
    }
    return failures == 0 ? 0 : 1;
}
