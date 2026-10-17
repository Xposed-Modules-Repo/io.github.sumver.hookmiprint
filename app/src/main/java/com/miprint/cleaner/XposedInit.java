package com.miprint.cleaner;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * LSPosed / EdXposed 模块入口。
 *
 * 目标：com.mi.print（小米打印）v4.1.713 (versionCode 458)
 * 作用：隐藏底部 docker 的「资源」tab（rb_main_study → LearnWrapperFragment）。
 *       只操作 View 层与 tab 切换回调，不动 fragment 机制、不拦网络请求（详见 LearnTabHook）。
 */
public class XposedInit implements IXposedHookLoadPackage {

    public static final String TAG = "MiPrintCleaner";
    public static final String TARGET_PKG = "com.mi.print";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }
        // 仅在主进程安装 Hook（避免 :push 等子进程重复安装）
        if (lpparam.processName != null && lpparam.processName.contains(":")) {
            log("skip secondary process: " + lpparam.processName);
            return;
        }

        log("loaded target: " + lpparam.packageName + " process=" + lpparam.processName);

        // 先触发配置加载（异步、经模块自带只读 ContentProvider 读取，见 Prefs）：
        // 每个进程只加载一次；开关修改后需重启（强制停止）目标应用才生效
        try {
            Prefs.prime();
        } catch (Throwable t) {
            log("Prefs prime failed: " + t);
        }

        try {
            LearnTabHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("LearnTabHook install failed: " + t);
        }

        try {
            HomeBottomHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("HomeBottomHook install failed: " + t);
        }

        try {
            UserCenterCleanHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("UserCenterCleanHook install failed: " + t);
        }

        try {
            HomeMenuHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("HomeMenuHook install failed: " + t);
        }
    }

    public static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }
}
