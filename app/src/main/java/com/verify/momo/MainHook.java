package com.verify.momo;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 为了防止陌陌极早期主线程卡死，所有逻辑丢进子线程执行！
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 只要进了这个子线程，说明代码已经100%跑起来了
                XposedBridge.log("══════ 🚨 子线程极速验证 🚨 ══════");
                XposedBridge.log("▶ 插件在子线程成功启动！当前应用: " + lpparam.packageName);
                XposedBridge.log("═══════════════════════════════════");

                if (lpparam.packageName.equals("com.immomo.momo")) {
                    XposedBridge.log("🔥 陌陌进程已成功在子线程被接管！");
                }
            }
        }).start();
    }
}
