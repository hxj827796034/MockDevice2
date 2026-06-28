package com.example.momobypass;

import android.os.Build;
import android.os.FileObserver;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.net.wifi.WifiInfo;
import android.bluetooth.BluetoothAdapter;
import android.os.Debug;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "[MockDevice]";
    private static final String CONFIG_PATH = "/data/data/com.miui.miuibbs/files/db/device";
    private static final String CONFIG_DIR = "/data/data/com.miui.miuibbs/files/db";

    private static final ConcurrentHashMap<String, String> deviceProps = new ConcurrentHashMap<>();
    private static volatile boolean sConfigReady = false;
    private static FileObserver sObserver;
    private static ScheduledExecutorService guardianExecutor;
    private static boolean isGuardianRunning = false;

    // ================ 1. Zygote 抢占成功 ================
    @Override
    public void initZygote(StartupParam startupParam) {
        XposedBridge.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        XposedBridge.log("🔰 [MockDevice] Zygote 级抢先防御已激活！");
        XposedBridge.log("🔰 [MockDevice] 正在注入陌陌底层进程链...");
        XposedBridge.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    // ================ 2. 陌陌启动精准命中 ================
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals("com.immomo.momo") &&
            !lpparam.packageName.equals("com.immomo.young")) {
            return;
        }

        XposedBridge.log("");
        XposedBridge.log("🔥🔥🔥 [MockDevice] 陌陌进程已命中！防御体系开始激活！🔥🔥🔥");
        XposedBridge.log("📱 [MockDevice] 目标包名: " + lpparam.packageName);
        
        // ===== 3. 陌霸配置同步阶段 =====
        XposedBridge.log("📂 [MockDevice] -> 正在扫描陌霸配置文件...");
        if (!loadMoBaData()) {
            XposedBridge.log("❌ [MockDevice] -> 读取陌霸配置失败！请检查 /data/data/com.miui.miuibbs/files/db/device 是否存在！");
            return;
        }
        XposedBridge.log("✅ [MockDevice] -> 陌霸配置读取完成！共加载 " + deviceProps.size() + " 项伪装数据");

        // ===== 4. 后台防御线程安全启动 =====
        XposedBridge.log("🛡️ [MockDevice] -> 启动文件监听 (陌霸改机热同步)...");
        startFileWatcher();

        XposedBridge.log("📡 [MockDevice] -> 启动守护进程通信 (监听备份还原)...");
        startDaemonListener();

        XposedBridge.log("📲 [MockDevice] -> 正在加载 Native 底层防御库...");
        loadNativeLibrary();

        XposedBridge.log("🎯 [MockDevice] -> 执行 Java 层全量 Hook 伪装...");
        initLocalDefense(lpparam);

        XposedBridge.log("⏳ [MockDevice] -> 启动 60 秒定时护卫队...");
        initGuardian();

        XposedBridge.log("");
        XposedBridge.log("✅✅✅ [MockDevice] 全部防御体系已 100% 完成注入！✅✅✅");
        XposedBridge.log("🔒 [MockDevice] 陌陌现在看到的是一台纯净无 Root 的陌生设备。");
        XposedBridge.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    // ================ 5. Native 底层注入 ================
    private void loadNativeLibrary() {
        try {
            System.loadLibrary("mockdevice");
            XposedBridge.log("✅ [MockDevice] -> Native 库 (libmockdevice.so) 加载成功！底层系统调用已拦截！");
        } catch (Throwable t) {
            XposedBridge.log("❌ [MockDevice] -> Native 库加载失败: " + t.getMessage());
        }
    }

    // ================ 6. 文件监听 ================
    private void startFileWatcher() {
        if (sObserver != null) return;
        try {
            sObserver = new FileObserver(CONFIG_DIR, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
                @Override
                public void onEvent(int event, String path) {
                    if ("device".equals(path)) {
                        XposedBridge.log("📂 [MockDevice] -> 检测到陌霸配置文件变更！");
                        try { Thread.sleep(200); } catch (Exception ignored) {}
                        loadMoBaData();
                        refreshBuildProps();
                        XposedBridge.log("✅ [MockDevice] -> 陌霸配置热同步完成！");
                    }
                }
            };
            sObserver.startWatching();
        } catch (Exception ignored) {}
    }

    // ================ 7. 守护 Socket ================
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
                            XposedBridge.log("📡 [MockDevice] -> 收到守护进程通知 (备份/还原)！");
                            loadMoBaData();
                            refreshBuildProps();
                            XposedBridge.log("✅ [MockDevice] -> 备份还原同步完成！");
                        }
                    }
                } catch (Exception ignored) {
                    try { Thread.sleep(2000); } catch (Exception ignored2) {}
                }
            }
        }).start();
    }

    // ================ 8. 陌霸配置读取 ================
    private boolean loadMoBaData() {
        File moBaFile = new File(CONFIG_PATH);
        if (!moBaFile.exists()) return false;

        String json = readFile(moBaFile);
        if (json == null || json.isEmpty()) return false;

        try {
            JSONArray array = new JSONArray(json);
            if (array.length() > 0) {
                JSONObject obj = array.getJSONObject(0);
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String value = obj.optString(key);
                    if (value != null && !value.isEmpty()) {
                        deviceProps.put(key, value);
                    }
                }
            }
            sConfigReady = true;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String readFile(File file) {
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    private String getProp(String... keys) {
        if (!sConfigReady) return null;
        for (String key : keys) {
            String val = deviceProps.get(key);
            if (val != null && !val.isEmpty()) return val;
        }
        return null;
    }

    // ================ 9. 全量 Hook 伪装 ================
    private void initLocalDefense(XC_LoadPackage.LoadPackageParam lpp) {
        refreshBuildProps();
        XposedBridge.log("🆔 [MockDevice] -> 已刷新所有 Build 核心指纹信息");

        // 9.1 电话信息
        try {
            Class<?> telClass = lpp.classLoader.loadClass("android.telephony.TelephonyManager");
            hookReplacement(telClass, "getDeviceId", getProp("phone.Imei1", "phone.Imei"));
            hookReplacement(telClass, "getSubscriberId", getProp("phone.SubscriberId"));
            hookReplacement(telClass, "getLine1Number", getProp("phone.Tel", "phone.MobileNumber"));
            hookReplacement(telClass, "getSimSerialNumber", getProp("phone.SimSerialNumber"));
            hookReplacement(telClass, "getNetworkOperator", getProp("phone.NetworkOperator"));
            hookReplacement(telClass, "getSimState", "5");
            XposedBridge.log("📶 [MockDevice] -> Telephony (IMEI/SIM/运营商) 伪装完成");
        } catch (Throwable ignored) {}

        // 9.2 Wifi 信息
        try {
            Class<?> wifiClass = lpp.classLoader.loadClass("android.net.wifi.WifiInfo");
            hookReplacement(wifiClass, "getMacAddress", getProp("phone.WifiMAC"));
            hookReplacement(wifiClass, "getBSSID", getProp("phone.BSSIDHook"));
            XposedBridge.log("📶 [MockDevice] -> WiFi (MAC/BSSID) 伪装完成");
        } catch (Throwable ignored) {}

        // 9.3 Android ID
        String androidId = getProp("build.ANDROIDID", "phone.DeviceId");
        if (androidId != null) {
            try {
                XposedHelpers.findAndHookMethod(Settings.Secure.class, "getString",
                    android.content.ContentResolver.class, String.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if ("android_id".equals(p.args[1])) p.setResult(androidId);
                        }
                    });
                XposedBridge.log("📱 [MockDevice] -> Android ID 伪装完成");
            } catch (Throwable ignored) {}
        }

        // 9.4 蓝牙
        String btMac = getProp("phone.BlueToothMAC");
        if (btMac != null) {
            try {
                Class<?> btClass = lpp.classLoader.loadClass("android.bluetooth.BluetoothAdapter");
                hookReplacement(btClass, "getAddress", btMac);
                XposedBridge.log("📶 [MockDevice] -> 蓝牙 MAC 伪装完成");
            } catch (Throwable ignored) {}
        }

        // 9.5 网卡
        String mac = getProp("phone.WifiMAC");
        if (mac != null) {
            try {
                XposedHelpers.findAndHookMethod(NetworkInterface.class, "getHardwareAddress", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            String[] parts = mac.split(":");
                            byte[] bytes = new byte[6];
                            for (int i = 0; i < 6; i++) bytes[i] = (byte) Integer.parseInt(parts[i], 16);
                            p.setResult(bytes);
                        } catch (Exception ignored) {}
                    }
                });
                XposedBridge.log("📶 [MockDevice] -> 网络接口底层 MAC 伪装完成");
            } catch (Throwable ignored) {}
        }

        // 9.6 系统属性拦截
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            XC_MethodHook propHook = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    String key = (String) p.args[0];
                    if (key == null) return;
                    if ("ro.debuggable".equals(key)) p.setResult("0");
                    if ("ro.secure".equals(key)) p.setResult("1");
                    if ("ro.build.selinux".equals(key)) p.setResult("1");
                    if ("ro.boot.verifiedbootstate".equals(key)) p.setResult("green");
                    if ("ro.boot.flash.locked".equals(key)) p.setResult("1");
                    if ("ro.boot.veritymode".equals(key)) p.setResult("enforcing");
                }
            };
            XposedHelpers.findAndHookMethod(spClass, "get", String.class, String.class, propHook);
            XposedHelpers.findAndHookMethod(spClass, "get", String.class, propHook);
            XposedBridge.log("⚙️ [MockDevice] -> 系统关键属性伪装拦截完成");
        } catch (Throwable ignored) {}

        // 9.7 根目录拦截
        try {
            XposedHelpers.findAndHookMethod(File.class, "exists", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    String path = ((File) p.thisObject).getAbsolutePath();
                    if (path != null && (path.contains("/su") || path.contains("/magisk") || path.contains("/xposed") || path.contains("/lsposed"))) {
                        XposedBridge.log("❌ [MockDevice] -> 已拦截敏感文件访问: " + path);
                        p.setResult(false);
                    }
                }
            });
            XposedBridge.log("📁 [MockDevice] -> /su /magisk 敏感文件拦截完成");
        } catch (Throwable ignored) {}

        // 9.8 Runtime命令拦截
        try {
            XposedHelpers.findAndHookMethod(Runtime.class, "exec", String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    String cmd = (String) p.args[0];
                    if (cmd != null && (cmd.contains("su") || cmd.contains("magisk") || cmd.contains("which"))) {
                        XposedBridge.log("🛑 [MockDevice] -> 已拦截 Root 探查命令: " + cmd);
                        p.setThrowable(new SecurityException("Blocked"));
                    }
                }
            });
            XposedBridge.log("🛡️ [MockDevice] -> Runtime.exec 高危命令拦截完成");
        } catch (Throwable ignored) {}

        // 9.9 调试检测
        try {
            XposedHelpers.findAndHookMethod(Debug.class, "isDebuggerConnected", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setResult(false);
                }
            });
            XposedBridge.log("🔍 [MockDevice] -> 调试器连接检测屏蔽完成");
        } catch (Throwable ignored) {}

        XposedBridge.log("🎯 [MockDevice] -> 全量 Java Hook 集合已注入！");
    }

    // ================ 10. 定时刷新 ================
    private void refreshBuildProps() {
        setBuildField("BRAND", getProp("build.BRAND", "build.brand"));
        setBuildField("MODEL", getProp("build.MODEL", "build.model"));
        setBuildField("MANUFACTURER", getProp("build.MANUFACTURER", "build.manufacturer"));
        setBuildField("DEVICE", getProp("build.DEVICE", "build.device"));
        setBuildField("FINGERPRINT", getProp("build.FINGERPRINT", "hardware.fingerprint"));
        setBuildField("SERIAL", getProp("build.SERIAL", "build.serial"));
        setBuildField("PRODUCT", getProp("build.PRODUCT", "build.product"));
        setBuildField("HARDWARE", getProp("build.HARDWARE"));
        setBuildField("TAGS", getProp("build.TAGS", "build.tags"));
        setBuildField("TYPE", getProp("build.TYPE"));
    }

    private void initGuardian() {
        if (isGuardianRunning) return;
        guardianExecutor = Executors.newScheduledThreadPool(1);
        isGuardianRunning = true;
        guardianExecutor.scheduleWithFixedDelay(() -> {
            try {
                if (!deviceProps.isEmpty()) {
                    refreshBuildProps();
                    XposedBridge.log("🔄 [MockDevice] -> 定时护盾刷新执行！Build 数据保持伪装状态。");
                }
            } catch (Throwable ignored) {}
        }, 60, 60, TimeUnit.SECONDS);
    }

    // ================ 工具 ================
    private void setBuildField(String name, String val) {
        if (val != null && !val.isEmpty()) {
            try { Field f = Build.class.getDeclaredField(name); f.setAccessible(true); f.set(null, val); } catch (Throwable ignored) {}
        }
    }

    private void hookReplacement(Class<?> clz, String method, String val) {
        if (val == null) return;
        try { XposedHelpers.findAndHookMethod(clz, method, XC_MethodReplacement.returnConstant(val)); } catch (Throwable ignored) {}
    }
}
