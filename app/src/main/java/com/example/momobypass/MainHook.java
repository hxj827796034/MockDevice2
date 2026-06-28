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

// 恢复为你成功案例的包名：com.example.momobypass
public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "[MomoBypass]";
    private static final String CONFIG_PATH = "/data/data/com.miui.miuibbs/files/db/device";

    private static final ConcurrentHashMap<String, String> deviceProps = new ConcurrentHashMap<>();
    private static volatile boolean sConfigReady = false;

    // 极早期 Zygote 抢占（采用你成功案例的结构）
    @Override
    public void initZygote(StartupParam startupParam) {
        // Zygote 阶段不做任何复杂操作，只打印存活证明
        XposedBridge.log(TAG + " Zygote 抢占成功！");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals("com.immomo.momo") &&
            !lpparam.packageName.equals("com.immomo.young")) {
            return;
        }

        // 极早期安全日志（确保代码被执行）
        XposedBridge.log("══════ 🚨 启动日志确认 🚨 ══════");
        XposedBridge.log("▶ 陌陌进程命中: " + lpparam.packageName);
        XposedBridge.log("═══════════════════════════════════");

        // 加载陌霸配置
        if (!loadMoBaData()) {
            XposedBridge.log(TAG + " ⚠️ 陌霸配置未找到，但插件将继续运行");
        }

        // 加载 Native 底层拦截
        loadNativeLibrary();

        // 执行核心伪装（极简版，防止崩溃）
        initLocalDefense(lpparam);

        XposedBridge.log("▶ 插件主流程执行完毕！");
    }

    private void loadNativeLibrary() {
        try {
            System.loadLibrary("mockdevice");
            XposedBridge.log(TAG + " Native 底层拦截库加载成功");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Native 库加载失败: " + t.getMessage());
        }
    }

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
            XposedBridge.log(TAG + " 陌霸配置加载成功，共 " + deviceProps.size() + " 项");
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

    private void initLocalDefense(XC_LoadPackage.LoadPackageParam lpp) {
        // Build 伪装
        setBuildField("BRAND", getProp("build.BRAND", "build.brand"));
        setBuildField("MODEL", getProp("build.MODEL", "build.model"));
        setBuildField("MANUFACTURER", getProp("build.MANUFACTURER", "build.manufacturer"));
        setBuildField("DEVICE", getProp("build.DEVICE", "build.device"));
        setBuildField("FINGERPRINT", getProp("build.FINGERPRINT", "hardware.fingerprint"));
        setBuildField("SERIAL", getProp("build.SERIAL", "build.serial"));

        // 核心电话伪装
        try {
            Class<?> telClass = lpp.classLoader.loadClass("android.telephony.TelephonyManager");
            hookReplacement(telClass, "getDeviceId", getProp("phone.Imei1", "phone.Imei"));
            hookReplacement(telClass, "getSubscriberId", getProp("phone.SubscriberId"));
            hookReplacement(telClass, "getLine1Number", getProp("phone.Tel", "phone.MobileNumber"));
            hookReplacement(telClass, "getSimSerialNumber", getProp("phone.SimSerialNumber"));
            hookReplacement(telClass, "getNetworkOperator", getProp("phone.NetworkOperator"));
            hookReplacement(telClass, "getSimState", "5");
        } catch (Throwable ignored) {}

        // WiFi 伪装
        try {
            Class<?> wifiClass = lpp.classLoader.loadClass("android.net.wifi.WifiInfo");
            hookReplacement(wifiClass, "getMacAddress", getProp("phone.WifiMAC"));
        } catch (Throwable ignored) {}

        // Android ID 伪装
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

        XposedBridge.log(TAG + " 基础伪装 Hook 全部激活");
    }

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
