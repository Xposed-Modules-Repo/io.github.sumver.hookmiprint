package com.miprint.cleaner;

import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 隐藏小米打印底部 docker 的「资源」tab。
 *
 * 链路（小米打印 4.1.713 / versionCode 458，jadx 反编译核实）：
 *   MainActivity.onCreate
 *     → setContentView(activity_main)   // rg_bottom_menu 下三个 RadioButton：
 *                                       //   rb_main_home(首页) / rb_main_study(资源) / rb_main_user_center(我的)
 *     → x()                             // 预创建三个 fragment（含 LearnWrapperFragment）
 *     → initView()                      // 绑定 RadioGroup 与 3 个 RadioButton ← 主 Hook 点
 *   onCheckedChanged(RadioGroup,int)    // tab 切换：rb_main_study → changeFragment(LearnWrapperFragment.class)
 *                                       //                                          ← 防御 Hook 点
 *
 * 处理：
 *   1) initView() 执行后把 rb_main_study 置 GONE（剩余两个按钮 layout_weight=1 自动平分整行）；
 *   2) onCheckedChanged() 拦截 rb_main_study 的选中，改选首页，防程序化 setChecked 绕开。
 *
 * 「资源」页无其他入口（无路由注解、无深链、ILearnService 仅埋点），隐藏按钮即完整下线该页。
 *
 * 开关：受设置界面「隐藏底部「资源」tab」({@link Prefs#K_LEARN_TAB}) 控制，默认开启。
 */
final class LearnTabHook {

    private static final String MAIN_ACTIVITY = "com.mi.print.MainActivity";

    private LearnTabHook() {
    }

    static void install(ClassLoader cl) {
        // ---- 主 Hook：initView() 执行后隐藏「资源」按钮 ----
        try {
            Class<?> main = XposedHelpers.findClass(MAIN_ACTIVITY, cl);
            XposedBridge.hookAllMethods(main, "initView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Prefs.isEnabled(Prefs.K_LEARN_TAB)) {
                        return;
                    }
                    hideLearnTab(param);
                }
            });
            XposedInit.log("hooked MainActivity.initView (hide learn tab)");
        } catch (Throwable t) {
            XposedInit.log("LearnTabHook initView hook failed: " + t);
        }

        // ---- 防御 Hook：onCheckedChanged 拦截「资源」选中，改选首页 ----
        try {
            Class<?> main = XposedHelpers.findClass(MAIN_ACTIVITY, cl);
            XposedBridge.hookAllMethods(main, "onCheckedChanged", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!Prefs.isEnabled(Prefs.K_LEARN_TAB)) {
                        return;
                    }
                    if (param.args.length < 2 || !(param.args[1] instanceof Integer)) {
                        return;
                    }
                    Integer checkedId = (Integer) param.args[1];
                    Integer studyId = resolveId(param.thisObject.getClass().getClassLoader(), "rb_main_study");
                    if (studyId != null && studyId.equals(checkedId)) {
                        XposedInit.log("learn tab checked, redirect to home");
                        param.setResult(null); // 消费本次回调，不切学习页
                        checkHome(param.thisObject);
                    }
                }
            });
            XposedInit.log("hooked MainActivity.onCheckedChanged (learn tab guard)");
        } catch (Throwable t) {
            XposedInit.log("LearnTabHook onCheckedChanged hook failed: " + t);
        }
    }

    /** 隐藏「资源」按钮；R$id 解析失败时按文本「资源」在 RadioGroup 中兜底查找。 */
    private static void hideLearnTab(XC_MethodHook.MethodHookParam param) {
        try {
            Object thisObj = param.thisObject;
            Integer studyId = resolveId(thisObj.getClass().getClassLoader(), "rb_main_study");
            if (studyId != null) {
                View btn = (View) XposedHelpers.callMethod(thisObj, "findViewById", studyId);
                if (btn != null) {
                    btn.setVisibility(View.GONE);
                    XposedInit.log("learn tab hidden (rb_main_study -> GONE)");
                    return;
                }
            }
            hideByText(thisObj);
        } catch (Throwable t) {
            XposedInit.log("hideLearnTab failed: " + t);
        }
    }

    /** 按文本兜底：在 rg_bottom_menu 里找文本为「资源」的 RadioButton 并隐藏。 */
    private static void hideByText(Object thisObj) {
        try {
            Integer groupId = resolveId(thisObj.getClass().getClassLoader(), "rg_bottom_menu");
            if (groupId == null) {
                XposedInit.log("hideByText: rg_bottom_menu id not resolved");
                return;
            }
            View group = (View) XposedHelpers.callMethod(thisObj, "findViewById", groupId);
            if (group instanceof RadioGroup) {
                RadioGroup rg = (RadioGroup) group;
                for (int i = 0; i < rg.getChildCount(); i++) {
                    View child = rg.getChildAt(i);
                    if (child instanceof RadioButton) {
                        CharSequence text = ((RadioButton) child).getText();
                        if (text != null && "资源".equals(text.toString().trim())) {
                            child.setVisibility(View.GONE);
                            XposedInit.log("learn tab hidden (text fallback: " + text + ")");
                            return;
                        }
                    }
                }
            }
            XposedInit.log("hideByText: no button with text 资源 found");
        } catch (Throwable t) {
            XposedInit.log("hideByText failed: " + t);
        }
    }

    /** 把「首页」按钮设为选中（触发 onCheckedChanged(home) 走正常切页逻辑）。 */
    private static void checkHome(Object thisObj) {
        try {
            Integer homeId = resolveId(thisObj.getClass().getClassLoader(), "rb_main_home");
            if (homeId == null) {
                return;
            }
            View home = (View) XposedHelpers.callMethod(thisObj, "findViewById", homeId);
            if (home instanceof RadioButton) {
                ((RadioButton) home).setChecked(true);
            }
        } catch (Throwable t) {
            XposedInit.log("checkHome failed: " + t);
        }
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
