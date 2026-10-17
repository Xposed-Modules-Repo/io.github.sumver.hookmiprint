package com.miprint.cleaner;

import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 隐藏首页「文档打印 / 照片打印 / 拍摄扫描 / 扫描仪」功能区下方的整个区域（H5 内容区）。
 *
 * 链路（小米打印 4.1.713 / versionCode 458，layout fragment_new_homepage.xml 核实）：
 *   DeviceHomePageFragment.onCreateView → inflate(fragment_new_homepage)
 *   fragment_new_homepage 根 = LinearLayoutCompat（垂直），子视图：
 *     [0] homepage_header_bar_layout        ← 顶部设备栏（保留）
 *     [1] 快速功能区                          ← 文档打印/照片打印/拍摄扫描/扫描仪（保留）
 *         function_doc_print / function_image_print / function_shoot / function_scan
 *     [2] FrameLayout                        ← 下方整个区域（隐藏）
 *         ├── web_view      （H5 内容，WebView）
 *         ├── loading_view  （加载中遮罩）
 *         └── ll_error      （错误重试）
 *
 * 处理：onViewCreated 后把 [2]（web_view 的父容器 FrameLayout）置 GONE。
 * 只隐藏该容器，不动子视图：App 对 web_view / loading_view / ll_error 的显隐操作
 * 只改子视图，不会把父容器重新显示。
 *
 * 开关：受设置界面「隐藏首页下方内容区」({@link Prefs#K_HOME_BOTTOM}) 控制，默认开启。
 */
final class HomeBottomHook {

    private static final String FRAGMENT = "com.mi.print.fragment.DeviceHomePageFragment";

    private HomeBottomHook() {
    }

    static void install(ClassLoader cl) {
        // 一次性解析 web_view 资源 id（安装期解析，Hook 回调里复用）
        final Integer webViewId = resolveId(cl, "web_view");
        try {
            Class<?> fragment = XposedHelpers.findClass(FRAGMENT, cl);
            XposedBridge.hookAllMethods(fragment, "onViewCreated", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Prefs.isEnabled(Prefs.K_HOME_BOTTOM)) {
                        return;
                    }
                    hideBottomArea(param, webViewId);
                }
            });
            XposedInit.log("hooked DeviceHomePageFragment.onViewCreated (hide home bottom area)");
        } catch (Throwable t) {
            XposedInit.log("HomeBottomHook install failed: " + t);
        }
    }

    private static void hideBottomArea(XC_MethodHook.MethodHookParam param, Integer webViewId) {
        try {
            if (param.args.length < 1 || !(param.args[0] instanceof View)) {
                return;
            }
            View root = (View) param.args[0];
            View webView;
            if (webViewId != null) {
                webView = root.findViewById(webViewId);
            } else {
                webView = null;
            }
            if (webView == null) {
                // 兜底：按 WebView 类型查找
                webView = findWebView(root);
            }
            if (webView == null) {
                XposedInit.log("hideBottomArea: web_view not found");
                return;
            }
            if (webView.getParent() instanceof View) {
                View container = (View) webView.getParent();
                container.setVisibility(View.GONE);
                XposedInit.log("home bottom area hidden (web container -> GONE)");
            }
        } catch (Throwable t) {
            XposedInit.log("hideBottomArea failed: " + t);
        }
    }

    /** 兜底：在根视图树中深度优先找第一个 WebView。 */
    private static View findWebView(View root) {
        if (root instanceof android.webkit.WebView) {
            return root;
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findWebView(vg.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Integer resolveId(ClassLoader cl, String fieldName) {
        try {
            Class<?> rid = XposedHelpers.findClass("com.mi.print.R$id", cl);
            return XposedHelpers.getStaticIntField(rid, fieldName);
        } catch (Throwable t) {
            XposedInit.log("resolve id failed: " + fieldName + " (" + t + ")");
            return null;
        }
    }
}
