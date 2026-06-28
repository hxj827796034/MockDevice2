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
    private static final String CONFIG_PATH = "/data/data/com.miui.miuibbs/files/db/device";

    private static volatile ConcurrentHashMap<String, String> sConfig = new ConcurrentHashMap<>();
    private static volatile boolean sConfigReady = false;
    private static FileObserver sObserver;

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 【强校验】：无论作用域勾没勾，只要包名是陌陌，立刻触发！
        log("【系统检测】LSPosed 传递了包名: " + lpparam.packageName);
        
        if (lpparam.packageName.equals("com.immomo.momo")) {
            log("【绝对执行】成功锁定陌陌进程（com.immomo.momo），开始伪装！");
            
            // 即便 LSPosed 本身可能拦截了某些服务，我们提前调用启动
            if (!loadConfig()) {
                log("【警告】加载陌霸配置文件失败，但不影响插件继续存活。");
            } else {
                log("【成功】陌霸伪装配置已加载，准备执行 Hook！");
                executeAllHooks(lpparam);
                return;
            }
        }
        
        // 如果 LSPosed 在 Android 13 上只给了系统进程，那就只打印
        if (!lpparam.packageName.equals("com.immomo.momo") &&
            !lpparam.packageName.equals("com.immomo.young")) {
            log("非陌陌应用，跳过（" + lpparam.packageName + "）");
        }
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

    // ==================== 执行全部 Hook ====================
    private void executeAllHooks(XC_LoadPackage.LoadPackageParam lpp) {
        hookBuildInfo();
        hookTelephonyManager(lpp);
        hookWifiInfo(lpp);
        hookSettingsSecure(lpp);
        hookSystemProperties();
        hookRuntimeExec();
        hookPackageManager(lpp);
        hookFileExists();
        hookSELinux();
        hookDebugDetection();
        
        log("【完成】所有伪装 Hook 已全部注入到陌陌！");
    }

    // ==================== 核心 Hook 方法 ====================
    private void hookBuildInfo() {
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
