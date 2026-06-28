#include <string>
#include <map>
#include <mutex>
#include <fstream>
#include <sstream>
#include <cstring>
#include <cstdio>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <android/log.h>

#define LOG_TAG "MockNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::map<std::string, std::string> gConfig;
static std::mutex gMutex;
static bool gReady = false;

static int (*real_open)(const char*, int, ...) = nullptr;
static int (*real_openat)(int, const char*, int, ...) = nullptr;
static int (*real_access)(const char*, int) = nullptr;
static int (*real_faccessat)(int, const char*, int, int) = nullptr;
static int (*real_stat)(const char*, struct stat*) = nullptr;
static int (*real_lstat)(const char*, struct stat*) = nullptr;
static ssize_t (*real_readlink)(const char*, char*, size_t) = nullptr;

static bool loadConfig() {
    std::ifstream f("/data/data/com.miui.miuibbs/files/db/device");
    if (!f.is_open()) return false;

    std::stringstream b;
    b << f.rdbuf();
    std::string c = b.str();
    f.close();

    if (c.length() < 2) return false;

    std::map<std::string, std::string> nc;
    bool inS = false, esc = false;
    std::string k, v;
    enum { FK, RK, WV, RV } s = FK;

    for (size_t i = 0; i < c.length(); i++) {
        char ch = c[i];
        if (esc) {
            if (s == RK) k += ch;
            else if (s == RV) v += ch;
            esc = false;
            continue;
        }
        if (ch == '\\') { esc = true; continue; }
        if (ch == '"') {
            if (!inS) {
                inS = true;
                if (s == FK) s = RK;
                else if (s == WV) s = RV;
            } else {
                inS = false;
                if (s == RK) s = WV;
                else if (s == RV) {
                    if (!k.empty()) nc[k] = v;
                    k.clear();
                    v.clear();
                    s = FK;
                }
            }
            continue;
        }
        if (inS) {
            if (s == RK) k += ch;
            else if (s == RV) v += ch;
        }
    }

    if (nc.empty()) return false;

    gConfig.swap(nc);
    gReady = true;
    LOGI("Native配置加载成功: %zu项", gConfig.size());
    return true;
}

static bool isBlocked(const char* p) {
    if (!p || !gReady) return false;
    const char* list[] = {
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/system/app/Superuser.apk",
        "/data/adb/magisk", "/data/adb/ksu", "/data/adb/modules",
        "/data/adb/lspd", "/data/adb/zygisk", "/data/adb/tricky_store",
        "/data/data/com.miui.miuibbs",
        nullptr
    };
    for (int i = 0; list[i]; i++) {
        if (strstr(p, list[i]) == p) return true;
    }
    const char* kw[] = {"magisk", "supersu", "busybox", "frida", "xposed", "substrate", "ksu", nullptr};
    for (int i = 0; kw[i]; i++) {
        if (strcasestr(p, kw[i])) return true;
    }
    return false;
}

extern "C" int open(const char* p, int fl, ...) {
    if (isBlocked(p)) { errno = EACCES; return -1; }
    mode_t m = 0;
    if (fl & O_CREAT) { va_list a; va_start(a, fl); m = va_arg(a, mode_t); va_end(a); }
    return real_open(p, fl, m);
}

extern "C" int openat(int fd, const char* p, int fl, ...) {
    if (isBlocked(p)) { errno = EACCES; return -1; }
    mode_t m = 0;
    if (fl & O_CREAT) { va_list a; va_start(a, fl); m = va_arg(a, mode_t); va_end(a); }
    return real_openat(fd, p, fl, m);
}

extern "C" int access(const char* p, int m) {
    if (isBlocked(p)) { errno = EACCES; return -1; }
    return real_access(p, m);
}

extern "C" int faccessat(int fd, const char* p, int m, int fl) {
    if (isBlocked(p)) { errno = EACCES; return -1; }
    return real_faccessat(fd, p, m, fl);
}

extern "C" int stat(const char* p, struct stat* b) {
    if (isBlocked(p)) { errno = ENOENT; return -1; }
    return real_stat(p, b);
}

extern "C" int lstat(const char* p, struct stat* b) {
    if (isBlocked(p)) { errno = ENOENT; return -1; }
    return real_lstat(p, b);
}

extern "C" ssize_t readlink(const char* p, char* b, size_t sz) {
    if (gReady && strstr(p, "/proc/self/exe")) {
        const char* fake = "/system/bin/app_process64";
        size_t len = strlen(fake);
        if (sz > len) { strcpy(b, fake); return len; }
    }
    return real_readlink(p, b, sz);
}

__attribute__((constructor)) static void init() {
    real_open = (decltype(real_open)) dlsym(RTLD_NEXT, "open");
    real_openat = (decltype(real_openat)) dlsym(RTLD_NEXT, "openat");
    real_access = (decltype(real_access)) dlsym(RTLD_NEXT, "access");
    real_faccessat = (decltype(real_faccessat)) dlsym(RTLD_NEXT, "faccessat");
    real_stat = (decltype(real_stat)) dlsym(RTLD_NEXT, "stat");
    real_lstat = (decltype(real_lstat)) dlsym(RTLD_NEXT, "lstat");
    real_readlink = (decltype(real_readlink)) dlsym(RTLD_NEXT, "readlink");
    LOGI("MockNative初始化完成");
    loadConfig();
}
