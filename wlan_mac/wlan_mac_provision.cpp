/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Persists the factory Wi-Fi MAC so the qcacld driver can use it.
 *
 * nv_mac reads the factory MAC from modem NV and writes it as 6 raw bytes to
 * /data/vendor/mac_addr/wlan.mac, but never creates the file the WLAN driver
 * loads (firmware/wlan/qca_cld/kiwi_v2/wlan_mac.bin ->
 * /mnt/vendor/persist/wlan/wlan_mac.bin), so the driver falls back to a
 * random address.
 *
 * The file is written as "wlan0=<12 hex digits>", which both consumers parse:
 *  - qcacld (hdd_update_mac_config) accepts any "<name>=<12 hex>" line.
 *  - nv_mac reads the same file with fscanf("wlan0=%02x...") and prefers it
 *    over modem NV, so the MAC stays stable on later boots.
 *
 * The driver is loaded at early-init and reads the file once at probe, long
 * before nv_mac runs. A newly written file therefore takes effect on the
 * next boot.
 */

#define LOG_TAG "wlan_mac_provision"

#include <errno.h>
#include <fcntl.h>
#include <log/log.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>

namespace {

constexpr const char* kNvMacFile = "/data/vendor/mac_addr/wlan.mac";
constexpr const char* kWlanMacDir = "/mnt/vendor/persist/wlan";
constexpr const char* kWlanMacFile = "/mnt/vendor/persist/wlan/wlan_mac.bin";
constexpr const char* kWlanMacTmp = "/mnt/vendor/persist/wlan/.wlan_mac.bin.tmp";
constexpr size_t kMacLen = 6;

// nv_mac's fallback when the NV read fails: 00:0a:f5 + 3 random bytes.
constexpr unsigned char kNvMacRandomOui[] = {0x00, 0x0a, 0xf5};

bool IsValidMac(const unsigned char* mac) {
    bool zero = true;
    for (size_t i = 0; i < kMacLen; i++) zero &= (mac[i] == 0);
    // Reject all-zero and multicast addresses.
    return !zero && !(mac[0] & 0x01);
}

bool ReadNvMac(unsigned char* mac) {
    int fd = TEMP_FAILURE_RETRY(open(kNvMacFile, O_RDONLY | O_CLOEXEC));
    if (fd < 0) {
        ALOGE("Cannot open %s: %s", kNvMacFile, strerror(errno));
        return false;
    }
    ssize_t n = TEMP_FAILURE_RETRY(read(fd, mac, kMacLen));
    close(fd);
    if (n != static_cast<ssize_t>(kMacLen)) {
        ALOGE("%s: expected %zu bytes, got %zd", kNvMacFile, kMacLen, n);
        return false;
    }
    return true;
}

// Returns true if the existing file already holds a valid "wlan0=" MAC.
// nv_mac reads that file before NV, so a valid one is the source of the
// current MAC (factory provisioned, user override, or written by us earlier)
// and must never be replaced.
bool ExistingFileValid() {
    int fd = TEMP_FAILURE_RETRY(open(kWlanMacFile, O_RDONLY | O_CLOEXEC));
    if (fd < 0) {
        if (errno != ENOENT) ALOGW("Cannot open %s: %s", kWlanMacFile, strerror(errno));
        return false;
    }
    char buf[128] = {};
    ssize_t n = TEMP_FAILURE_RETRY(read(fd, buf, sizeof(buf) - 1));
    close(fd);
    if (n <= 0) return false;

    unsigned char mac[kMacLen];
    int matched = sscanf(buf, "wlan0=%2hhx%2hhx%2hhx%2hhx%2hhx%2hhx", &mac[0], &mac[1], &mac[2],
                         &mac[3], &mac[4], &mac[5]);
    return matched == static_cast<int>(kMacLen) && IsValidMac(mac);
}

bool FsyncDir(const char* path) {
    int fd = TEMP_FAILURE_RETRY(open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    if (fd < 0) return false;
    bool ok = fsync(fd) == 0;
    close(fd);
    return ok;
}

// Writes via a temp file + rename so a power loss can never leave a
// truncated file on persist.
bool WriteAtomically(const char* data, size_t len) {
    unlink(kWlanMacTmp);
    int fd = TEMP_FAILURE_RETRY(
            open(kWlanMacTmp, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0644));
    if (fd < 0) {
        ALOGE("Cannot create %s: %s", kWlanMacTmp, strerror(errno));
        return false;
    }
    bool ok = TEMP_FAILURE_RETRY(write(fd, data, len)) == static_cast<ssize_t>(len) &&
              fchmod(fd, 0644) == 0 && fsync(fd) == 0;
    if (!ok) ALOGE("Cannot write %s: %s", kWlanMacTmp, strerror(errno));
    close(fd);
    if (!ok) {
        unlink(kWlanMacTmp);
        return false;
    }
    if (rename(kWlanMacTmp, kWlanMacFile) != 0) {
        ALOGE("Cannot rename %s -> %s: %s", kWlanMacTmp, kWlanMacFile, strerror(errno));
        unlink(kWlanMacTmp);
        return false;
    }
    if (!FsyncDir(kWlanMacDir)) ALOGW("Cannot fsync %s: %s", kWlanMacDir, strerror(errno));
    return true;
}

}  // namespace

int main() {
    if (ExistingFileValid()) {
        ALOGI("%s already provisioned", kWlanMacFile);
        return 0;
    }

    unsigned char mac[kMacLen];
    if (!ReadNvMac(mac)) return 1;

    if (!IsValidMac(mac)) {
        ALOGE("Refusing invalid MAC from %s", kNvMacFile);
        return 1;
    }
    if (memcmp(mac, kNvMacRandomOui, sizeof(kNvMacRandomOui)) == 0) {
        // Persisting this would lock in a random MAC: nv_mac would read it
        // back from persist on every boot and never retry NV.
        ALOGW("%s holds nv_mac's random fallback, not persisting it", kNvMacFile);
        return 1;
    }

    char line[32];
    int len = snprintf(line, sizeof(line), "wlan0=%02x%02x%02x%02x%02x%02x\n", mac[0], mac[1],
                       mac[2], mac[3], mac[4], mac[5]);
    if (len <= 0 || static_cast<size_t>(len) >= sizeof(line)) return 1;

    if (!WriteAtomically(line, len)) return 1;

    ALOGI("Provisioned %s with %02x:%02x:%02x:xx:xx:xx; WLAN uses it from the next boot",
          kWlanMacFile, mac[0], mac[1], mac[2]);
    return 0;
}
