package com.miprint.cleaner;

import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 「我的」页净化：隐藏 会员中心 / 设备共享 / 设备耗材 / 团队管理 / 给小米打印好评 五个入口。
 *
 * 链路（小米打印 4.1.713 / versionCode 458，layout user_fragment_user_center.xml 核实）：
 *   UserCenterFragment.onCreateView → inflate(user_fragment_user_center)
 *   onViewCreated → p(view)（标题栏）/ q(view)（绑定各入口 LinearLayout）/ initObserve()
 *   入口 id（模块级 R：com.hannto.usercenter.R$id，非 com.mi.print.R）：
 *     ll_user_member_center（会员中心） ll_user_share_device（设备共享）
 *     ll_user_shop（设备耗材） ll_team_manage（团队管理） ll_user_rate（给小米打印好评）
 *
 * 处理：onViewCreated 后按各自开关把对应入口置 GONE。
 * 各入口是纵向列表中的一项，置 GONE 后下方条目自动上移。
 * onResume 链路（v()）只更新头像/昵称/VIP 列表/消息红点，不会把这些入口重新显示。
 * 以上 id 仅在 user_fragment_user_center.xml 使用，且查找限定在 UserCenterFragment 根视图内，无误伤。
 *
 * 开关：受设置界面「我的页净化」分组下 5 个开关控制，默认开启。
 */
final class UserCenterCleanHook {

    private static final String FRAGMENT = "com.hannto.usercenter.fragment.UserCenterFragment";
    private static final String RID_CLASS = "com.hannto.usercenter.R$id";

    private UserCenterCleanHook() {
    }

    static void install(ClassLoader cl) {
        // 一次性解析 5 个入口的资源 id（安装期解析，Hook 回调里复用）
        final Integer memberId = resolveId(cl, "ll_user_member_center");
        final Integer shareId = resolveId(cl, "ll_user_share_device");
        final Integer shopId = resolveId(cl, "ll_user_shop");
        final Integer teamId = resolveId(cl, "ll_team_manage");
        final Integer rateId = resolveId(cl, "ll_user_rate");
        try {
            Class<?> fragment = XposedHelpers.findClass(FRAGMENT, cl);
            XposedBridge.hookAllMethods(fragment, "onViewCreated", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length < 1 || !(param.args[0] instanceof View)) {
                        return;
                    }
                    View root = (View) param.args[0];
                    hideIfEnabled(root, memberId, "会员中心", Prefs.K_UC_MEMBER);
                    hideIfEnabled(root, shareId, "设备共享", Prefs.K_UC_SHARE);
                    hideIfEnabled(root, shopId, "设备耗材", Prefs.K_UC_SHOP);
                    hideIfEnabled(root, teamId, "团队管理", Prefs.K_UC_TEAM);
                    hideIfEnabled(root, rateId, "给小米打印好评", Prefs.K_UC_RATE);
                }
            });
            XposedInit.log("hooked UserCenterFragment.onViewCreated (my-page clean)");
        } catch (Throwable t) {
            XposedInit.log("UserCenterCleanHook install failed: " + t);
        }
    }

    private static void hideIfEnabled(View root, Integer rid, String label, String prefKey) {
        if (rid == null || !Prefs.isEnabled(prefKey)) {
            return;
        }
        View target = root.findViewById(rid);
        if (target != null && target.getVisibility() != View.GONE) {
            target.setVisibility(View.GONE);
            XposedInit.log("my-page entry hidden: " + label);
        }
    }

    private static Integer resolveId(ClassLoader cl, String fieldName) {
        try {
            Class<?> rid = XposedHelpers.findClass(RID_CLASS, cl);
            return XposedHelpers.getStaticIntField(rid, fieldName);
        } catch (Throwable t) {
            XposedInit.log("resolve id failed: " + fieldName + " (" + t + ")");
            return null;
        }
    }
}
