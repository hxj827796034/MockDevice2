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

/**
 * 基于 LSPosed 框架（API 82）的原生 LSPosed 模块入口。
 * 必须配合 app/src/main/assets/xposed_init 使用。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "MockDevice";
    private static final String CONFIG_PATH = "/data/data/com.miui.miuibbs/files/db/device";

    private static volatile ConcurrentHashMap<String, String> sConfig = new ConcurrentHashMap<>();
    private static volatile boolean sConfigReady = false;
    private static FileObserver sObserver;

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 测试是否被 LSPosed 加载
        log("== 模块被 LSPosed 成功加载 ==");
        log("当前应用的包名是: " + lpparam.packageName);

        // === 作用域限制：只处理陌陌和陌陌极速版 ===
        if (!lpparam.packageName.equals("com.immomo.momo") &&
            !lpparam.packageName.equals("com.immomo.young")) {
            return;
        }

        log("=== 成功命中陌陌进程，开始加载配置 ===");

        // 加载陌霸配置文件
        if (!loadConfig()) {
            log("加载陌霸配置文件失败，请检查 /data/data/com.miui.miuibbs/files/db/device 是否存在。");
            return;
        }

        log("配置加载成功，开始执行 Hook...");

        // 执行全部防护 Hook
        hookBuildInfo();
        hookTelephonyManager(lpparam);
        hookWifiInfo(lpparam);
        hookSettingsSecure(lpparam);
        hookSystemProperties();
        hookRuntimeExec();
        hookPackageManager(lpparam);
        hookFileExists();
        hookSELinux();
        hookDebugDetection();

        log("所有 LSPosed Hook 执行完毕，插件运行正常！");
    }

    // ==================== 加载陌霸配置文件 ====================
    private boolean loadConfig() {
        try {
            File file = new File(CONFIG_PATH);
            if (!file.exists() || !file.canRead()) {
                return false;
            }

            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();

            // 简单 JSON 解析
            String content = sb.toString().trim();
            if (content.startsWith("[")) {
                org.json.JSONArray array = new org.json.JSONArray(content);
                if (array.length() > 0) {
                    org.json.JSONObject obj = array.getJSONObject(0);
                    java.util.Iterator<String> keys = obj.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String value = obj.optString(key);
                        if (value != null) {
                            sConfig.put(key, value);
                        }
                    }
                }
            }

            sConfigReady = true;
            log("成功读取陌霸配置，共解析 " + sConfig.size() + " 项伪装数据。");
            return true;

        } catch (Exception e) {
            log("解析配置文件失败: " + e.getMessage());
            return false;
        }
    }

    private String getConfigValue(String... keys) {
        if (!sConfigReady) return null;
        for (String key : keys) {
            String val = sConfig.get(key);
            if (val != null && !val.isEmpty()) {
                return val;
            }
        }
        return null;
    }

    // ==================== 核心 Hook 方法 ====================
    private void hookBuildInfo() {
        // 反射修改 Build 信息
        setBuildField("BRAND", getConfigValue("build.BRAND", "build.brand"));
        setBuildField("MODEL", getConfigValue("build.MODEL", "build.model"));
        setBuildField("MANUFACTURER", getConfigValue("build.MANUFACTURER", "build.manufacturer"));
        setBuildField("FINGERPRINT", getConfigValue("build.FINGERPRINT", "hardware.fingerprint"));
        setBuildField("SERIAL", getConfigValue("build.SERIAL", "build.serial"));
        setBuildField("PRODUCT", getConfigValue("build.PRODUCT", "build.product"));
        setBuildField("DEVICE", getConfigValue("build.DEVICE", "build.device"));
        setBuildField("HARDWARE", getConfigValue("build.HARDWARE"));
        setBuildField("TAGS", getConfigValue("build.TAGS", "build.tags"));
        setBuildField("TYPE", getConfigValue("build.TYPE"));
    }

    private void setBuildField(String name, String value) {
        if (value == null || value.isEmpty()) return;
        try {
            Field field = Build.class.getDeclaredField(name);
            field.setAccessible(true);
            Field modifiersField = Field.class.getDeclaredField("accessFlags");
            modifiersField.setAccessible(true);
            int modifiers = field.getModifiers();
            modifiersField.setInt(field, modifiers & ~Modifier.FINAL);
            field.set(null, value);
            modifiersField.setInt(field, modifiers);
        } catch (Exception ignored) {}
    }

    private void hookTelephonyManager(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> telephonyClass = lpp.classLoader.loadClass("android.telephony.TelephonyManager");

            XposedHelpers.findAndHookMethod(telephonyClass, "getDeviceId", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String imei = getConfigValue("phone.Imei1", "phone.Imei");
                    if (imei != null) param.setResult(imei);
                }
            });

            XposedHelpers.findAndHookMethod(telephonyClass, "getSubscriberId", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String imsi = getConfigValue("phone.SubscriberId");
                    if (imsi != null) param.setResult(imsi);
                }
            });

            XposedHelpers.findAndHookMethod(telephonyClass, "getLine1Number", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String number = getConfigValue("phone.Tel", "phone.MobileNumber");
                    if (number != null) param.setResult(number);
                }
            });

            XposedHelpers.findAndHookMethod(telephonyClass, "getSimSerialNumber", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String iccid = getConfigValue("phone.SimSerialNumber");
                    if (iccid != null) param.setResult(iccid);
                }
            });

            XposedHelpers.findAndHookMethod(telephonyClass, "getNetworkOperator", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String op = getConfigValue("phone.NetworkOperator");
                    if (op != null) param.setResult(op);
                }
            });

            XposedHelpers.findAndHookMethod(telephonyClass, "getSimState", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // 强制返回 SIM 卡就绪状态
                    param.setResult(5);
                }
            });

        } catch (Exception e) {
            log("Hook TelephonyManager 失败: " + e.getMessage());
        }
    }

    private void hookWifiInfo(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> wifiClass = lpp.classLoader.loadClass("android.net.wifi.WifiInfo");

            XposedHelpers.findAndHookMethod(wifiClass, "getMacAddress", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String mac = getConfigValue("phone.WifiMAC");
                    if (mac != null) param.setResult(mac);
                }
            });

            XposedHelpers.findAndHookMethod(wifiClass, "getBSSID", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String bssid = getConfigValue("phone.BSSIDHook", "phone.BSSID");
                    if (bssid != null) param.setResult(bssid);
                }
            });

        } catch (Exception e) {
            log("Hook WifiInfo 失败: " + e.getMessage());
        }
    }

    private void hookSettingsSecure(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> secureClass = lpp.classLoader.loadClass("android.provider.Settings$Secure");

            XposedHelpers.findAndHookMethod(secureClass, "getString",
                android.content.ContentResolver.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[1];
                        if ("android_id".equals(key)) {
                            String androidId = getConfigValue("build.ANDROIDID", "phone.DeviceId");
                            if (androidId != null) {
                                param.setResult(androidId);
                            }
                        }
                    }
                });

        } catch (Exception e) {
            log("Hook Settings.Secure 失败: " + e.getMessage());
        }
    }

    private void hookSystemProperties() {
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");

            XC_MethodHook propHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String key = (String) param.args[0];
                    if (key == null) return;

                    switch (key) {
                        case "ro.debuggable":
                            param.setResult("0");
                            break;
                        case "init.svc.adbd":
                            param.setResult("stopped");
                            break;
                        case "ro.secure":
                            param.setResult("1");
                            break;
                        case "ro.build.tags":
                            param.setResult("release-keys");
                            break;
                        case "ro.build.type":
                            param.setResult("user");
                            break;
                        case "ro.boot.verifiedbootstate":
                            param.setResult("green");
                            break;
                        case "ro.boot.flash.locked":
                            param.setResult("1");
                            break;
                        case "ro.boot.veritymode":
                            param.setResult("enforcing");
                            break;
                    }
                }
            };

            XposedHelpers.findAndHookMethod(spClass, "get", String.class, String.class, propHook);
            XposedHelpers.findAndHookMethod(spClass, "get", String.class, propHook);

        } catch (Exception e) {
            log("Hook SystemProperties 失败: " + e.getMessage());
        }
    }

    private void hookRuntimeExec() {
        try {
            XposedHelpers.findAndHookMethod(Runtime.class, "exec", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String cmd = (String) param.args[0];
                    if (cmd != null) {
                        String lower = cmd.toLowerCase();
                        if (lower.contains("su") || lower.contains("magisk")) {
                            param.setThrowable(new SecurityException("Blocked by MockDevice"));
                        }
                    }
                }
            });
        } catch (Exception e) {
            log("Hook Runtime.exec 失败: " + e.getMessage());
        }
    }

    private void hookPackageManager(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> pmClass = lpp.classLoader.loadClass("android.app.ApplicationPackageManager");

            XposedHelpers.findAndHookMethod(pmClass, "getInstalledApplications", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    List<?> list = (List<?>) param.getResult();
                    if (list == null) return;

                    List<Object> filteredList = new ArrayList<>();
                    for (Object app : list) {
                        try {
                            String pkg = (String) XposedHelpers.getObjectField(app, "packageName");
                            if (pkg == null) continue;

                            String lowerPkg = pkg.toLowerCase();
                            // 过滤掉常见 Root/框架 和 你自己
                            if (lowerPkg.contains("magisk") || lowerPkg.contains("xposed") ||
                                lowerPkg.contains("lsposed") || lowerPkg.contains("miuibbs") ||
                                lowerPkg.contains("ksu")) {
                                continue;
                            }
                            filteredList.add(app);
                        } catch (Exception ignored) {
                            filteredList.add(app);
                        }
                    }
                    param.setResult(filteredList);
                }
            });

        } catch (Exception e) {
            log("Hook PackageManager 失败: " + e.getMessage());
        }
    }

    private void hookFileExists() {
        try {
            XposedHelpers.findAndHookMethod(File.class, "exists", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String path = ((File) param.thisObject).getAbsolutePath();
                    if (path != null) {
                        String lower = path.toLowerCase();
                        if (lower.contains("magisk") || lower.contains("xposed") ||
                            lower.contains("lsposed") || lower.contains("su")) {
                            param.setResult(false);
                        }
                    }
                }
            });
        } catch (Exception e) {
            log("Hook File.exists 失败: " + e.getMessage());
        }
    }

    private void hookSELinux() {
        try {
            Class<?> seClass = Class.forName("android.os.SELinux");
            XposedHelpers.findAndHookMethod(seClass, "isSELinuxEnforced", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(true);
                }
            });
        } catch (Exception e) {
            log("Hook SELinux 失败: " + e.getMessage());
        }
    }

    private void hookDebugDetection() {
        try {
            XposedHelpers.findAndHookMethod(android.os.Debug.class, "isDebuggerConnected", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(false);
                }
            });
        } catch (Exception e) {
            log("Hook Debug 失败: " + e.getMessage());
        }
    }
}
