#include <string>
#include <map>
#include <mutex>
#include <fstream>
#include <sstream>
#include <cstring>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <android/log.h>

#define LOG_TAG "MockNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static std::map<std::string, std::string> gCfg;
static std::mutex gLock;
static bool gReady = false;

static int (*orig_open)(const char*, int, ...) = nullptr;
static int (*orig_access)(const char*, int) = nullptr;
static int (*orig_stat)(const char*, struct stat*) = nullptr;

bool loadNativeConfig() {
    std::ifstream f("/data/data/com.miui.miuibbs/files/db/device");
    if (!f.is_open()) return false;
    std::stringstream ss; ss << f.rdbuf(); f.close();
    std::string content = ss.str();
    if (content.length() < 2) return false;

    std::map<std::string, std::string> newCfg;
    bool inStr = false, escaped = false;
    std::string key, val;
    enum { FK, RK, WV, RV } state = FK;

    for (size_t i = 0; i < content.length(); i++) {
        char ch = content[i];
        if (escaped) { if(state==RK)key+=ch; else if(state==RV)val+=ch; escaped=false; continue; }
        if (ch=='\\') { escaped=true; continue; }
        if (ch=='"') {
            if(!inStr) { inStr=true; if(state==FK)state=RK; else if(state==WV)state=RV; }
            else { inStr=false; if(state==RK)state=WV; else if(state==RV){ if(!key.empty())newCfg[key]=val; key.clear(); val.clear(); state=FK; } }
            continue;
        }
        if(inStr) { if(state==RK)key+=ch; else if(state==RV)val+=ch; }
    }
    if(newCfg.empty()) return false;
    gCfg.swap(newCfg);
    gReady = true;
    LOGI("Native层配置加载成功");
    return true;
}

bool isBlockedPath(const char* p) {
    if (!p || !gReady) return false;
    const char* blocked[] = {
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/data/adb/magisk", "/data/adb/ksu", "/data/adb/modules",
        "/data/adb/lspd", "/data/data/com.miui.miuibbs", nullptr
    };
    for (int i=0; blocked[i]; i++) if (strstr(p, blocked[i]) == p) return true;
    return false;
}

extern "C" int open(const char* path, int flags, ...) {
    if (isBlockedPath(path)) { errno = EACCES; return -1; }
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, mode_t); va_end(ap); }
    return orig_open(path, flags, mode);
}

extern "C" int access(const char* path, int mode) {
    if (isBlockedPath(path)) { errno = EACCES; return -1; }
    return orig_access(path, mode);
}

extern "C" int stat(const char* path, struct stat* buf) {
    if (isBlockedPath(path)) { errno = ENOENT; return -1; }
    return orig_stat(path, buf);
}

__attribute__((constructor)) static void init() {
    orig_open = (decltype(orig_open))dlsym(RTLD_NEXT, "open");
    orig_access = (decltype(orig_access))dlsym(RTLD_NEXT, "access");
    orig_stat = (decltype(orig_stat))dlsym(RTLD_NEXT, "stat");
    loadNativeConfig();
}
