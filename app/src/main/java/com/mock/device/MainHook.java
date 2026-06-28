package com.example.momobypass;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    // 1. 这是最核心的入口，且没有任何复杂的读取文件操作
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        
        // 2. 无论什么包名，第一时间先把这一行输出（证明入口绝对被调用了）
        XposedBridge.log(">>> [MockDevice] 插件入口已被 LSPosed 调用！当前应用: " + lpparam.packageName);

        // 3. 只针对陌陌生效
        if (!lpparam.packageName.equals("com.immomo.momo") && !lpparam.packageName.equals("com.immomo.young")) {
            return;
        }

        // 4. 打印命中陌陌的日志
        XposedBridge.log(">>> [MockDevice] 成功命中陌陌进程 (com.immomo.momo)，正在准备启动防御！");

        // 5. 启动一个独立的子线程，在里面做复杂的初始化操作
        new Thread(() -> {
            try {
                XposedBridge.log(">>> [MockDevice] 子线程启动，开始加载完整防御体系...");
                
                // ⬇️ 把之前所有的加载逻辑都放在这里 ⬇️
                // 读取陌霸配置、Hook 等...
                
                XposedBridge.log(">>> [MockDevice] 防御体系在子线程中加载完成！");
            } catch (Throwable t) {
                XposedBridge.log(">>> [MockDevice] 子线程加载异常: " + t.getMessage());
            }
        }).start();
    }
}
