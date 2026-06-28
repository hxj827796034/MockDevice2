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
import java.lang.reflect.Modifier;
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

    private static final String TAG = "[MockDevice-Complete]";
    private static final String CONFIG_PATH = "/data/data/com.miui.miuibbs/files/db/device";
    private static final String CONFIG_DIR = "/data/data/com.miui.miuibbs/files/db";

    private static final ConcurrentHashMap<String, String> deviceProps = new ConcurrentHashMap<>();
    private static volatile boolean sConfigReady = false;
    private static FileObserver sObserver;
    private static ScheduledExecutorService guardianExecutor;
    private static boolean isGuardianRunning = false;

    // ================= 1. Zygote 级抢先注入 =================
    @Override
    public void initZygote(StartupParam startupParam) {
        XposedBridge.log("[" + TAG + "] 成功抢占 Zygote 进程，全方位防御体系启动！");
    }

    // ================= 2. 主入口 =================
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals("com.immomo.momo") &&
            !lpparam.packageName.equals("com.immomo.young")) {
            return;
        }

        XposedBridge.log("[" + TAG + "] 成功劫持陌陌包名，加载完整防御体系");

        if (!loadMoBaData()) {
            XposedBridge.log("[" + TAG + "] 错误：无法读取陌霸配置文件！");
            return;
        }

        // 启动文件监听（陌霸改机热同步）
        startFileWatcher();

        // 启动本地 Socket 守护接收器
        startDaemonListener();

        // 加载 Native 底层拦截库
        loadNativeLibrary();

        // 执行全套 Hook
        initLocalDefense(lpparam);

        // 启动定时护卫
        initGuardian();
        
        XposedBridge.log("[" + TAG + "] ✅ 陌陌风控屏蔽体系已完整运行！");
    }

    // ================= 3. Native 底层注入 =================
    private void loadNativeLibrary() {
        try {
            System.loadLibrary("mockdevice");
            XposedBridge.log("[" + TAG + "] ✅ 底层 Native 库加载成功（拦截 syscall）");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] ⚠️ Native 库加载失败: " + t.getMessage());
        }
    }

    // ================= 4. 文件监听（改机热同步） =================
    private void startFileWatcher() {
        if (sObserver != null) return;
        try {
            sObserver = new FileObserver(CONFIG_DIR, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
                @Override
                public void onEvent(int event, String path) {
                    if ("device".equals(path)) {
                        XposedBridge.log("[" + TAG + "] 检测到陌霸配置文件变化，热重载...");
                        try { Thread.sleep(200); } catch (Exception ignored) {}
                        loadMoBaData();
                        refreshBuildProps();
                    }
                }
            };
            sObserver.startWatching();
            XposedBridge.log("[" + TAG + "] ✅ 陌霸配置热同步监听已启动");
        } catch (Exception ignored) {}
    }

    // ================= 5. 本地守护 Socket 接收器（备份还原联动） =================
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
                            XposedBridge.log("[" + TAG + "] 收到备份还原通知，同步更新配置！");
                            loadMoBaData();
                            refreshBuildProps();
                        }
                    }
                } catch (Exception ignored) {
                    try { Thread.sleep(2000); } catch (Exception ignored2) {}
                }
            }
        }).start();
        XposedBridge.log("[" + TAG + "] ✅ 守护进程联动通信已启动");
    }

    // ================= 6. 核心配置读取 =================
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

    // ================= 7. 全量 Hook 模块 =================
    private void initLocalDefense(XC_LoadPackage.LoadPackageParam lpp) {
        // 7.1 伪装 Build 信息
        refreshBuildProps();

        // 7.2 伪装 Telephony (IMEI, 运营商)
        try {
            Class<?> telClass = lpp.classLoader.loadClass("android.telephony.TelephonyManager");
            hookReturn(telClass, "getDeviceId", getProp("phone.Imei1", "phone.Imei"));
            hookReturn(telClass, "getSubscriberId", getProp("phone.SubscriberId"));
            hookReturn(telClass, "getLine1Number", getProp("phone.Tel", "phone.MobileNumber"));
            hookReturn(telClass, "getSimSerialNumber", getProp("phone.SimSerialNumber"));
            hookReturn(telClass, "getNetworkOperator", getProp("phone.NetworkOperator"));
            hookReturn(telClass, "getSimState", "5");
        } catch (Throwable ignored) {}

        // 7.3 伪装 WiFi
        try {
            Class<?> wifiClass = lpp.classLoader.loadClass("android.net.wifi.WifiInfo");
            hookReturn(wifiClass, "getMacAddress", getProp("phone.WifiMAC"));
            hookReturn(wifiClass, "getBSSID", getProp("phone.BSSIDHook"));
        } catch (Throwable ignored) {}

        // 7.4 伪装 Android ID
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
            } catch (Throwable ignored) {}
        }

        // 7.5 伪装蓝牙
        String btMac = getProp("phone.BlueToothMAC");
        if (btMac != null) {
            try {
                Class<?> btClass = lpp.classLoader.loadClass("android.bluetooth.BluetoothAdapter");
                hookReturn(btClass, "getAddress", btMac);
            } catch (Throwable ignored) {}
        }

        // 7.6 伪装 NetworkInterface (底层网卡地址)
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
            } catch (Throwable ignored) {}
        }

        // 7.7 伪装系统属性
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
        } catch (Throwable ignored) {}

        // 7.8 拦截 Su 文件访问 (Java 层)
        try {
            XposedHelpers.findAndHookMethod(File.class, "exists", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    String path = ((File) p.thisObject).getAbsolutePath();
                    if (path != null && (path.contains("/su") || path.contains("/magisk") || path.contains("/xposed") || path.contains("/lsposed"))) {
                        p.setResult(false);
                    }
                }
            });
        } catch (Throwable ignored) {}

        // 7.9 拦截 Runtime.exec 命令
        try {
            XposedHelpers.findAndHookMethod(Runtime.class, "exec", String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    String cmd = (String) p.args[0];
                    if (cmd != null && (cmd.contains("su") || cmd.contains("magisk") || cmd.contains("which"))) {
                        p.setThrowable(new SecurityException("Blocked"));
                    }
                }
            });
        } catch (Throwable ignored) {}

        // 7.10 拦截调试检测
        try {
            XposedHelpers.findAndHookMethod(Debug.class, "isDebuggerConnected", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setResult(false);
                }
            });
        } catch (Throwable ignored) {}

        XposedBridge.log("[" + TAG + "] ✅ 全量 Hook 已全部注入！");
    }

    // ================= 8. 刷新核心伪装数据 =================
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

    // ================= 9. 守护进程 (定时刷防雷) =================
    private void initGuardian() {
        if (isGuardianRunning) return;
        guardianExecutor = Executors.newScheduledThreadPool(1);
        isGuardianRunning = true;
        guardianExecutor.scheduleWithFixedDelay(() -> {
            try {
                if (!deviceProps.isEmpty()) {
                    refreshBuildProps();
                    XposedBridge.log("[" + TAG + "] 🔄 守护进程执行定时刷新伪装数据...");
                }
            } catch (Throwable ignored) {}
        }, 60, 60, TimeUnit.SECONDS);
        XposedBridge.log("[" + TAG + "] ✅ 60秒定时护卫队已启动");
    }

    // ================= 工具方法 =================
    private void setBuildField(String name, String val) {
        if (val != null && !val.isEmpty()) {
            try { Field f = Build.class.getDeclaredField(name); f.setAccessible(true); f.set(null, val); } catch (Throwable ignored) {}
        }
    }

    private void hookReturn(Class<?> clz, String method, String val) {
        if (val == null) return;
        try { XposedHelpers.findAndHookMethod(clz, method, XC_MethodReplacement.returnConstant(val)); } catch (Throwable ignored) {}
    }
}
