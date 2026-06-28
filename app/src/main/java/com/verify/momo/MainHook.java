package com.verify.momo;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 无论什么应用，只要启动，就刷这个日志（证明这行代码被执行了！）
        XposedBridge.log("══════ 🚨 硬核验证日志 🚨 ══════");
        XposedBridge.log("▶ 当前加载的应用包名: " + lpparam.packageName);
        XposedBridge.log("▶ 插件的包名: com.verify.momo");
        XposedBridge.log("═══════════════════════════════════");

        if (lpparam.packageName.equals("com.immomo.momo")) {
            XposedBridge.log("🔥🔥🔥 目标成功命中：com.immomo.momo (陌陌) 正在启动！");
            XposedBridge.log("✅ 陌陌进程已完美接管，无需任何配置。");
        }
    }
}
