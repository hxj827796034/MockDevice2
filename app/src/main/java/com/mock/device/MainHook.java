package com.mock.device;

import android.os.Build;
import android.os.FileObserver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "MockDevice";
    private static final String CFG_PATH = "/data/data/com.miui.miuibbs/files/db/device";
    private static final String CFG_DIR = "/data/data/com.miui.miuibbs/files/db";

    private static volatile ConcurrentHashMap<String, String> sCfg = new ConcurrentHashMap<>();
    private static volatile boolean sReady = false;
    private static FileObserver sObs;

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        log(">>> 插件已成功注入到进程: " + lpparam.packageName);

        if (!lpparam.packageName.equals("com.immomo.momo") &&
            !lpparam.packageName.equals("com.immomo.young")) {
            return;
        }

        log("=== 成功命中陌陌进程，开始加载配置和 Hook ===");

        if (!loadConfig()) {
            log("配置加载失败，终止 Hook");
            return;
        }

        startFileWatcher();
        startDaemonListener();
        performAllHooks(lpparam);
        loadNativeLibrary();

        log("=== 全部 Hook 和 Native 注入完成 ===");
    }

    // ========== 配置文件加载 ==========
    private boolean loadConfig() {
        try {
            File f = new File(CFG_PATH);
            if (!f.exists() || !f.canRead()) return false;
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            JSONArray arr = new JSONArray(sb.toString().trim());
            if (arr.length() == 0) return false;
            JSONObject obj = arr.getJSONObject(0);
            ConcurrentHashMap<String, String> nc = new ConcurrentHashMap<>();
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String v = obj.optString(k, null);
                if (v != null && !v.isEmpty()) nc.put(k, v);
            }
            if (nc.isEmpty()) return false;
            sCfg = nc;
            sReady = true;
            log("配置加载成功，共 " + sCfg.size() + " 项");
            return true;
        } catch (Exception e) {
            log("配置加载异常: " + e.getMessage());
            return false;
        }
    }

    private String getCfg(String... keys) {
        if (!sReady) return null;
        Map<String, String> m = sCfg;
        for (String k : keys) {
            String v = m.get(k);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    // ========== 文件监听 (改机热重载) ==========
    private void startFileWatcher() {
        if (sObs != null) return;
        try {
            sObs = new FileObserver(CFG_DIR, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
                @Override
                public void onEvent(int event, String path) {
                    if ("device".equals(path)) {
                        try { Thread.sleep(200); } catch (Exception ignored) {}
                        log("检测到陌霸配置变更，正在热重载...");
                        loadConfig();
                        hookBuildInfo();
                    }
                }
            };
            sObs.startWatching();
        } catch (Exception ignored) {}
    }

    // ========== 守护进程监听 ==========
    private void startDaemonListener() {
        new Thread(() -> {
            while (true) {
                try {
                    android.net.LocalSocket sock = new android.net.LocalSocket();
                    sock.connect(new android.net.LocalSocketAddress("/data/local/tmp/mock_device.sock"));
                    BufferedReader r = new BufferedReader(new java.io.InputStreamReader(sock.getInputStream()));
                    String msg;
                    while ((msg = r.readLine()) != null) {
                        if (msg.startsWith("CONFIG_CHANGE:") || msg.startsWith("CONFIG_SYNC:")) {
                            boolean isRestore = msg.contains(":1:");
                            if (isRestore) Thread.sleep(500);
                            log("收到守护进程通知，还原=" + isRestore);
                            loadConfig();
                            if (isRestore) hookBuildInfo();
                        }
                    }
                } catch (Exception ignored) {
                    try { Thread.sleep(2000); } catch (Exception ignored2) {}
                }
            }
        }).start();
    }

    // ========== Native 库加载 ==========
    private void loadNativeLibrary() {
        try {
            System.loadLibrary("mockdevice");
            log("Native 库加载成功");
        } catch (Throwable t) {
            log("Native 库加载失败: " + t.getMessage());
        }
    }

    // ========== 核心 Hook 逻辑 ==========
    private void performAllHooks(XC_LoadPackage.LoadPackageParam p) {
        hookBuildInfo();
        hookTelephony(p);
        hookWifi(p);
        hookSettings(p);
        hookSystemProperties();
        hookRuntimeExec();
        hookPackageManager(p);
        hookFileOperations();
        hookSELinux();
        hookDebugDetection();
        hookBluetooth(p);
        hookNetworkInterface(p);
    }

    // ---------- 伪造 Build 信息 ----------
    private void hookBuildInfo() {
        setBuildField("BRAND", getCfg("build.BRAND", "build.brand"));
        setBuildField("MODEL", getCfg("build.MODEL", "build.model"));
        setBuildField("MANUFACTURER", getCfg("build.MANUFACTURER", "build.manufacturer"));
        setBuildField("FINGERPRINT", getCfg("build.FINGERPRINT", "hardware.fingerprint"));
        setBuildField("SERIAL", getCfg("build.SERIAL", "build.serial"));
        setBuildField("PRODUCT", getCfg("build.PRODUCT", "build.product"));
        setBuildField("DEVICE", getCfg("build.DEVICE", "build.device"));
        setBuildField("HARDWARE", getCfg("build.HARDWARE"));
        setBuildField("TAGS", getCfg("build.TAGS", "build.tags"));
        setBuildField("TYPE", getCfg("build.TYPE"));
    }

    private void setBuildField(String name, String value) {
        if (value == null) return;
        try {
            Field f = Build.class.getDeclaredField(name);
            f.setAccessible(true);
            Field mf = Field.class.getDeclaredField("accessFlags");
            mf.setAccessible(true);
            int mod = f.getModifiers();
            mf.setInt(f, mod & ~Modifier.FINAL);
            f.set(null, value);
            mf.setInt(f, mod);
        } catch (Exception ignored) {}
    }

    // ---------- 伪造电话信息 ----------
    private void hookTelephony(XC_LoadPackage.LoadPackageParam p) {
        String c = "android.telephony.TelephonyManager";
        hook(c, p, "getDeviceId", (x) -> { String v = getCfg("phone.Imei1", "phone.Imei"); if (v != null) x.setResult(v); });
        hook(c, p, "getSubscriberId", (x) -> { String v = getCfg("phone.SubscriberId"); if (v != null) x.setResult(v); });
        hook(c, p, "getLine1Number", (x) -> { String v = getCfg("phone.Tel", "phone.MobileNumber"); if (v != null) x.setResult(v); });
        hook(c, p, "getSimSerialNumber", (x) -> { String v = getCfg("phone.SimSerialNumber"); if (v != null) x.setResult(v); });
        hook(c, p, "getNetworkOperator", (x) -> { String v = getCfg("phone.NetworkOperator"); if (v != null) x.setResult(v); });
        hook(c, p, "getSimState", (x) -> { String v = getCfg("phone.SimState"); x.setResult(v != null ? Integer.parseInt(v) : 5); });
    }

    // ---------- 伪造 WiFi 信息 ----------
    private void hookWifi(XC_LoadPackage.LoadPackageParam p) {
        String c = "android.net.wifi.WifiInfo";
        hook(c, p, "getMacAddress", (x) -> { String v = getCfg("phone.WifiMAC"); if (v != null) x.setResult(v); });
        hook(c, p, "getBSSID", (x) -> { String v = getCfg("phone.BSSIDHook"); if (v == null) v = getCfg("phone.BSSID"); x.setResult(v); });
        hook(c, p, "getSSID", (x) -> { String v = getCfg("phone.SSIDHook"); if (v == null) v = getCfg("phone.WifiName"); x.setResult(v); });
    }

    // ---------- 伪造 Android ID ----------
    private void hookSettings(XC_LoadPackage.LoadPackageParam p) {
        try {
            XposedHelpers.findAndHookMethod("android.provider.Settings$Secure", p.classLoader,
                "getString", android.content.ContentResolver.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if ("android_id".equals(param.args[1])) {
                            String v = getCfg("build.ANDROIDID", "phone.DeviceId");
                            if (v != null) param.setResult(v);
                        }
                    }
                });
        } catch (Exception ignored) {}
    }

    // ---------- 伪造系统属性 ----------
    private void hookSystemProperties() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String key = (String) param.args[0];
                    if (key == null) return;
                    switch (key) {
                        case "ro.debuggable": param.setResult("0"); break;
                        case "init.svc.adbd": param.setResult("stopped"); break;
                        case "ro.secure": param.setResult("1"); break;
                        case "ro.build.tags": param.setResult("release-keys"); break;
                        case "ro.build.type": param.setResult("user"); break;
                        case "ro.boot.verifiedbootstate": param.setResult("green"); break;
                        case "ro.boot.flash.locked": param.setResult("1"); break;
                        case "ro.boot.veritymode": param.setResult("enforcing"); break;
                    }
                }
            };
            XposedHelpers.findAndHookMethod(sp, "get", String.class, String.class, hook);
            XposedHelpers.findAndHookMethod(sp, "get", String.class, hook);
        } catch (Exception ignored) {}
    }

    // ---------- 拦截命令执行 ----------
    private void hookRuntimeExec() {
        try {
            XposedHelpers.findAndHookMethod(Runtime.class, "exec", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String cmd = (String) param.args[0];
                    if (cmd != null && (cmd.toLowerCase().contains("su") || cmd.toLowerCase().contains("magisk"))) {
                        param.setThrowable(new SecurityException("Blocked by MockDevice"));
                    }
                }
            });
        } catch (Exception ignored) {}
    }

    // ---------- 隐藏应用列表 ----------
    private void hookPackageManager(XC_LoadPackage.LoadPackageParam p) {
        try {
            XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", p.classLoader,
                "getInstalledApplications", int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        List<?> list = (List<?>) param.getResult();
                        if (list == null) return;
                        List<Object> filtered = new ArrayList<>();
                        for (Object app : list) {
                            try {
                                String pkg = (String) XposedHelpers.getObjectField(app, "packageName");
                                if (!pkg.toLowerCase().contains("magisk") && 
                                    !pkg.toLowerCase().contains("xposed") &&
                                    !pkg.toLowerCase().contains("lsposed") &&
                                    !pkg.toLowerCase().contains("miuibbs")) {
                                    filtered.add(app);
                                }
                            } catch (Exception e) {
                                filtered.add(app);
                            }
                        }
                        param.setResult(filtered);
                    }
                });
        } catch (Exception ignored) {}
    }

    // ---------- 伪造 File.exists ----------
    private void hookFileOperations() {
        try {
            XposedHelpers.findAndHookMethod(File.class, "exists", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String path = ((File) param.thisObject).getAbsolutePath();
                    if (path != null && (path.contains("magisk") || path.contains("su") || path.contains("xposed"))) {
                        param.setResult(false);
                    }
                }
            });
        } catch (Exception ignored) {}
    }

    private void hookSELinux() {
        try {
            Class<?> sc = Class.forName("android.os.SELinux");
            XposedHelpers.findAndHookMethod(sc, "isSELinuxEnforced", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(true);
                }
            });
        } catch (Exception ignored) {}
    }

    private void hookDebugDetection() {
        try {
            XposedHelpers.findAndHookMethod(android.os.Debug.class, "isDebuggerConnected", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(false);
                }
            });
        } catch (Exception ignored) {}
    }

    private void hookBluetooth(XC_LoadPackage.LoadPackageParam p) {
        try {
            String mac = getCfg("phone.BlueToothMAC");
            if (mac != null) {
                XposedHelpers.findAndHookMethod("android.bluetooth.BluetoothAdapter", p.classLoader,
                    "getAddress", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(mac);
                        }
                    });
            }
        } catch (Exception ignored) {}
    }

    private void hookNetworkInterface(XC_LoadPackage.LoadPackageParam p) {
        try {
            String mac = getCfg("phone.WifiMAC");
            if (mac != null) {
                XposedHelpers.findAndHookMethod("java.net.NetworkInterface", p.classLoader,
                    "getHardwareAddress", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String[] parts = mac.split(":");
                                byte[] bytes = new byte[6];
                                for (int i = 0; i < 6; i++) {
                                    bytes[i] = (byte) Integer.parseInt(parts[i], 16);
                                }
                                param.setResult(bytes);
                            } catch (Exception ignored) {}
                        }
                    });
            }
        } catch (Exception ignored) {}
    }

    // ---------- 简化 Hook 工具 ----------
    private void hook(String cls, XC_LoadPackage.LoadPackageParam p, String method, HookCallback cb) {
        try {
            XposedHelpers.findAndHookMethod(cls, p.classLoader, method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try { cb.onHook(param); } catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}
    }

    interface HookCallback {
        void onHook(XC_MethodHook.MethodHookParam param);
    }
}
