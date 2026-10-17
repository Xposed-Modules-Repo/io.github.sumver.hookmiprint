package com.miprint.cleaner;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 首页自定义快捷菜单：隐藏原 H5 内容区（FrameLayout）后，在相同位置放一个原生菜单，
 * 直达 文档打印 / 照片打印 / 拍摄扫描 / 扫描仪 的二级子功能（跳过 FunctionActivity 中间页）。
 *
 * 链路（小米打印 4.1.713，jadx 反编译核实）：
 *   DeviceHomePageFragment.onViewCreated → 本 Hook 在 after 阶段：
 *     1) registerDocPick()：提前注册 DocPickService 的 ActivityResultLauncher（否则「本地文档」
 *        Android 11+ 的 SAF 引导框关闭后因 launcher 为 null 无法拉起系统文件选择器）；
 *     2) 隐藏 web_view 的父容器 FrameLayout（原 H5 内容区）；
 *     3) 在该位置插入程序化构建的菜单（4 个分类，各分类子项横向排列）。
 *   子项点击 → 反射调用 RouterUtil 组件服务（与原 FunctionActivity 各 fragment 的 onClick 等价）：
 *     图片文档       → PhotoPickService.setPhotoPickResponse + openPhotoPick(max9)
 *                     → PageService.setPhotoPreviewResponse + openPhotoPreview(files,1) → 文档打印预览
 *     本地文档       → DocPickService.setPickedResponse + openPhoneDoc(DocArgumentsEntity)（依赖 launcher）
 *     我的文档       → DocPickService.setPickedResponse + openMyDocumentType()
 *     本地相册       → PhotoPickService + PageService.openPhotoPreview(files,0) → 照片打印预览
 *     拼图           → JigsawService.setJigsawResponse + openJigsaw()
 *     文档拍摄扫描   → CameraScanService.setPrintResponse + openCameraScan(activity)
 *     图片转word/Excel → OrionService.openImg2OfcCamera(0/1)
 *     身份证扫描     → IdCardService.setPrintResponse + openIdCard(activity)
 *     文档扫描/身份证/户口本/护照 → IdcardScanService.setPrintResponse + openIdScanScan(ctx,host,type,iotType)
 *
 * 视觉：与 App 风格一致 —— 白底圆角 12dp 卡片、App 自带的 mipmap 图标（com.mi.print.R$mipmap 分类图标、
 * com.hannto.functioncore.R$mipmap 子项图标）、深黑标题/正文（#E6000000）、页面灰底；
 * 分类标题 = 小图标 + 15sp 加粗；子项 = 图标在上 + 12.5sp 文字居中的宫格卡片（同首页快捷宫格语言）。
 *
 * Kotlin 回调（Function1/Function2）用 java.lang.reflect.Proxy 动态实现，不依赖任何具体回调类。
 * backHomeResponse 一律置空（本来就在首页，无需"返回功能页"）。
 *
 * 开关：受设置界面「首页自定义快捷菜单」({@link Prefs#K_HOME_MENU}) 控制，默认开启。
 */
final class HomeMenuHook {

    private static final String FRAGMENT = "com.mi.print.fragment.DeviceHomePageFragment";
    private static final String ROUTER_UTIL = "com.hannto.common_config.service.RouterUtil";
    private static final String FILE_OPERATE = "com.hannto.foundation.file.FileOperateUtil";
    private static final String MODULE_CONFIG = "com.hannto.common_config.account.ModuleConfig";
    private static final String DEVICE_TYPE = "com.hannto.comres.type.DeviceType";
    private static final String ENTITY_DOC_RESULT = "com.hannto.comres.entity.result.DocModuleResultEntity";
    private static final String ENTITY_DOC_ARGS = "com.hannto.comres.entity.arguments.DocArgumentsEntity";
    private static final String ENTITY_PICK_PHOTO = "com.hannto.comres.entity.arguments.PickPhotoEntity";

    private static final String MIPMAP_APP = "com.mi.print.R$mipmap";
    private static final String MIPMAP_FUNCTION = "com.hannto.functioncore.R$mipmap";

    /** 页面底色（App common_bg_grey）。 */
    private static final int COLOR_BG = 0xFFF7F7F7;
    /** 卡片白底。 */
    private static final int COLOR_CARD = 0xFFFFFFFF;
    /** 卡片描边。 */
    private static final int COLOR_CARD_STROKE = 0xFFEBEBEB;
    /** 深黑正文/标题（App black_90_transparent）。 */
    private static final int COLOR_TEXT = 0xE6000000;

    private static ClassLoader CL;
    private static Class<?> sRouterUtil;

    private HomeMenuHook() {
    }

    static void install(ClassLoader cl) {
        CL = cl;
        try {
            sRouterUtil = XposedHelpers.findClass(ROUTER_UTIL, cl);
        } catch (Throwable t) {
            XposedInit.log("HomeMenuHook: RouterUtil not found, skip. " + t);
            return;
        }
        try {
            Class<?> fragment = XposedHelpers.findClass(FRAGMENT, cl);
            XposedBridge.hookAllMethods(fragment, "onViewCreated", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Prefs.isEnabled(Prefs.K_HOME_MENU)) {
                        return;
                    }
                    // 提前注册「本地文档」SAF 文件选择器的 launcher（用 fragment 自身作为 ActivityResultCaller，
                    // 与原 DocumentFragment.onCreateView 的注册时机一致：fragment 生命周期处于 CREATED）
                    registerDocPick(param.thisObject);
                    if (param.args.length < 1 || !(param.args[0] instanceof View)) {
                        return;
                    }
                    try {
                        insertMenu((View) param.args[0]);
                    } catch (Throwable t) {
                        XposedInit.log("HomeMenuHook insertMenu failed: " + t);
                    }
                }
            });
            XposedInit.log("hooked DeviceHomePageFragment.onViewCreated (home custom menu)");
        } catch (Throwable t) {
            XposedInit.log("HomeMenuHook install failed: " + t);
        }
    }

    /** 注册 DocPickService 的 ActivityResultLauncher，保证「本地文档」能拉起系统文件选择器。 */
    private static void registerDocPick(Object fragmentCaller) {
        try {
            Object docPick = svc("getDocPickService");
            if (docPick == null) {
                return;
            }
            XposedHelpers.callMethod(docPick, "registerForActivityResult", fragmentCaller);
            XposedInit.log("DocPickService launcher registered (local document)");
        } catch (Throwable t) {
            XposedInit.log("HomeMenuHook registerDocPick failed: " + t);
        }
    }

    // ------------------------------------------------------------------ UI

    private static void insertMenu(View root) {
        if (!(root instanceof ViewGroup)) {
            XposedInit.log("HomeMenuHook: home root is not ViewGroup");
            return;
        }
        ViewGroup container = (ViewGroup) root;

        // 隐藏原 H5 内容区（菜单替代它）
        hideWebArea(root);

        final Activity activity = findActivity(root);
        Context ctx = container.getContext();

        LinearLayout menu = new LinearLayout(ctx);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(ctx, 10), dp(ctx, 2), dp(ctx, 10), dp(ctx, 14));

        addCategory(menu, "文档打印", "function_doc_print",
                new String[][]{
                        {"图片文档", "fun_document_photo"},
                        {"本地文档", "fun_document_local"},
                        {"我的文档", "fun_document_scan"},
                }, activity);
        addCategory(menu, "照片打印", "function_image_print",
                new String[][]{
                        {"本地相册", "fun_local_album"},
                        {"拼图", "fun_puzzle"},
                }, activity);
        addCategory(menu, "拍摄扫描", "function_shoot",
                new String[][]{
                        {"文档拍摄扫描", "fun_picture_scan"},
                        {"图片转word", "fun_to_word"},
                        {"图片转Excel", "fun_to_excel"},
                        {"身份证扫描", "fun_idcard"},
                }, activity);
        addCategory(menu, "扫描仪", "function_scan",
                new String[][]{
                        {"文档扫描", "fun_document_device_scan"},
                        {"身份证", "fun_idcard"},
                        {"户口本", "fun_residence"},
                        {"护照", "fun_passport"},
                }, activity);

        Integer webId = resolveId(CL, "web_view");
        View webView = webId != null ? root.findViewById(webId) : null;
        View frame = (webView != null && webView.getParent() instanceof View) ? (View) webView.getParent() : null;
        if (frame != null) {
            int idx = container.indexOfChild(frame);
            container.addView(menu, Math.max(idx, 0));
        } else {
            container.addView(menu);
        }
        XposedInit.log("home custom menu inserted");
    }

    private static void hideWebArea(View root) {
        try {
            Integer webId = resolveId(CL, "web_view");
            View web = webId != null ? root.findViewById(webId) : null;
            if (web != null && web.getParent() instanceof View) {
                ((View) web.getParent()).setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 一个分类 = 标题行（小图标 + 15sp 加粗）+ 子项宫格行（图标在上、文字居中）。 */
    private static void addCategory(LinearLayout parent, String title, String catIcon,
                                    String[][] items, final Activity activity) {
        Context ctx = parent.getContext();

        // ---- 分类标题行 ----
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(ctx, 2), dp(ctx, 12), dp(ctx, 2), dp(ctx, 8));
        int catIconId = mipmapId(MIPMAP_APP, catIcon);
        if (catIconId != 0) {
            ImageView iv = new ImageView(ctx);
            iv.setImageResource(catIconId);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22));
            ilp.setMargins(0, 0, dp(ctx, 6), 0);
            header.addView(iv, ilp);
        }
        TextView titleTv = new TextView(ctx);
        titleTv.setText(title);
        titleTv.setTextSize(18);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setTextColor(COLOR_TEXT);
        header.addView(titleTv);
        parent.addView(header);

        // ---- 子项宫格行 ----
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] item : items) {
            final String label = item[0];
            final String cat = title;
            View cell = createItemCell(ctx, label, item[1]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(ctx, 78), 1f);
            lp.setMargins(dp(ctx, 4), 0, dp(ctx, 4), 0);
            row.addView(cell, lp);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onItemClick(activity, cat, label);
                }
            });
        }
        parent.addView(row);
    }

    /** 子项卡片：白底圆角 12dp + 轻描边，图标在上、文字居中。 */
    private static View createItemCell(Context ctx, String text, String iconName) {
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD);
        bg.setCornerRadius(dp(ctx, 12));
        bg.setStroke(dp(ctx, 1), COLOR_CARD_STROKE);
        cell.setBackground(bg);

        int iconId = mipmapId(MIPMAP_FUNCTION, iconName);
        if (iconId != 0) {
            ImageView iv = new ImageView(ctx);
            iv.setImageResource(iconId);
            cell.addView(iv, new LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28)));
        }
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(2);
        tv.setMinLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setTextColor(COLOR_TEXT);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(dp(ctx, 4), dp(ctx, 7), dp(ctx, 4), 0);
        cell.addView(tv, tlp);
        return cell;
    }

    private static Activity findActivity(View v) {
        Context c = v.getContext();
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) {
                return (Activity) c;
            }
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private static int dp(Context ctx, float value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------- dispatch

    private static void onItemClick(Activity activity, String category, String item) {
        if (activity == null) {
            XposedInit.log("HomeMenuHook: no activity for menu click");
            return;
        }
        try {
            if ("文档打印".equals(category)) {
                if ("图片文档".equals(item)) {
                    launchPicDocument();
                } else if ("本地文档".equals(item)) {
                    launchDocPick(0);
                } else if ("我的文档".equals(item)) {
                    launchDocPick(1);
                }
            } else if ("照片打印".equals(category)) {
                if ("本地相册".equals(item)) {
                    launchLocalAlbum();
                } else if ("拼图".equals(item)) {
                    launchJigsaw();
                }
            } else if ("拍摄扫描".equals(category)) {
                if ("文档拍摄扫描".equals(item)) {
                    launchCameraScan(activity);
                } else if ("图片转word".equals(item)) {
                    launchImg2Ofc(0);
                } else if ("图片转Excel".equals(item)) {
                    launchImg2Ofc(1);
                } else if ("身份证扫描".equals(item)) {
                    launchIdCard(activity);
                }
            } else if ("扫描仪".equals(category)) {
                if ("文档扫描".equals(item)) {
                    launchIdScan(activity, 4);
                } else if ("身份证".equals(item)) {
                    launchIdScan(activity, 0);
                } else if ("户口本".equals(item)) {
                    launchIdScan(activity, 1);
                } else if ("护照".equals(item)) {
                    launchIdScan(activity, 2);
                }
            }
            XposedInit.log("home menu clicked: " + category + "/" + item);
        } catch (Throwable t) {
            XposedInit.log("HomeMenuHook menu click failed (" + category + "/" + item + "): " + t);
        }
    }

    // ------------------------------------------------------- launch helpers

    /** 图片文档：选图(≤9) → 图片预览(type=1) → 打印为文档。 */
    private static void launchPicDocument() {
        Object photoPick = svc("getPhotoPickService");
        Object page = svc("getPageService");
        if (photoPick == null || page == null) {
            return;
        }
        XposedHelpers.callMethod(page, "setPhotoPreviewResponse", fn2(new Unit2() {
            @Override
            public void invoke(Object[] args) throws Throwable {
                ArrayList<String> files = (ArrayList<String>) args[0];
                if (files != null && !files.isEmpty()) {
                    Object entity = XposedHelpers.newInstance(cls(ENTITY_DOC_RESULT), files.get(0));
                    showDocPreview(entity);
                }
            }
        }));
        XposedHelpers.callMethod(photoPick, "setPhotoPickResponse", fn2(new Unit2() {
            @Override
            public void invoke(Object[] args) throws Throwable {
                ArrayList<String> files = uriListToFiles(args[1]);
                if (files == null) {
                    return;
                }
                Object page2 = svc("getPageService");
                if (page2 != null) {
                    XposedHelpers.callMethod(page2, "openPhotoPreview", files, 1);
                }
            }
        }));
        XposedHelpers.callMethod(photoPick, "openPhotoPick", newPickPhotoEntity());
    }

    /** 本地文档(0) / 我的文档(1)。 */
    private static void launchDocPick(final int kind) {
        Object docPick = svc("getDocPickService");
        if (docPick == null) {
            return;
        }
        XposedHelpers.callMethod(docPick, "setPickedResponse", fn1(new Unit1() {
            @Override
            public void invoke(Object[] args) throws Throwable {
                showDocPreview(args[0]);
            }
        }));
        if (kind == 0) {
            Object entity = XposedHelpers.newInstance(cls(ENTITY_DOC_ARGS));
            XposedHelpers.callMethod(docPick, "openPhoneDoc", entity);
        } else {
            XposedHelpers.callMethod(docPick, "openMyDocumentType");
        }
    }

    /** 本地相册：选图(≤9) → 照片预览(type=0)。 */
    private static void launchLocalAlbum() {
        Object photoPick = svc("getPhotoPickService");
        Object page = svc("getPageService");
        if (photoPick == null || page == null) {
            return;
        }
        XposedHelpers.callMethod(page, "setPhotoPreviewResponse", fn2(new Unit2() {
            @Override
            public void invoke(Object[] args) throws Throwable {
                Object pps = svc("getPrintPreviewService");
                if (pps != null) {
                    XposedHelpers.callMethod(pps, "showPhotoPreview", args[0], 0);
                }
            }
        }));
        XposedHelpers.callMethod(photoPick, "setPhotoPickResponse", fn2(new Unit2() {
            @Override
            public void invoke(Object[] args) throws Throwable {
                ArrayList<String> files = uriListToFiles(args[1]);
                if (files == null) {
                    return;
                }
                Object page2 = svc("getPageService");
                if (page2 != null) {
                    XposedHelpers.callMethod(page2, "openPhotoPreview", files, 0);
                }
            }
        }));
        XposedHelpers.callMethod(photoPick, "openPhotoPick", newPickPhotoEntity());
    }

    /** 拼图 → 照片预览(type=11)。 */
    private static void launchJigsaw() {
        Object jigsaw = svc("getJigsawService");
        if (jigsaw == null) {
            return;
        }
        XposedHelpers.callMethod(jigsaw, "setJigsawResponse", fn2(new Unit2() {
            @Override
            public void invoke(Object[] args) throws Throwable {
                ArrayList<String> list = new ArrayList<>();
                if (args.length > 1 && args[1] != null) {
                    list.add((String) args[1]);
                }
                Object pps = svc("getPrintPreviewService");
                if (pps != null) {
                    XposedHelpers.callMethod(pps, "showPhotoPreview", list, 11);
                }
            }
        }));
        XposedHelpers.callMethod(jigsaw, "openJigsaw");
    }

    /** 文档拍摄扫描。 */
    private static void launchCameraScan(Activity activity) {
        Object cam = svc("getCameraScanService");
        if (cam == null) {
            return;
        }
        XposedHelpers.callMethod(cam, "setPrintResponse", fn1(new Unit1() {
            @Override
            public void invoke(Object[] args) throws Throwable {
                showDocPreview(args[0]);
            }
        }));
        XposedHelpers.callMethod(cam, "setBackHomeResponse", fn1(null));
        XposedHelpers.callMethod(cam, "openCameraScan", activity);
    }

    /** 图片转word(0) / 图片转Excel(1)。 */
    private static void launchImg2Ofc(int type) {
        Object orion = svc("getOrionService");
        if (orion == null) {
            return;
        }
        XposedHelpers.callMethod(orion, "openImg2OfcCamera", type);
    }

    /** 身份证扫描（拍摄扫描分类）。 */
    private static void launchIdCard(Activity activity) {
        Object idc = svc("getIdCardService");
        if (idc == null) {
            return;
        }
        XposedHelpers.callMethod(idc, "setPrintResponse", fn1(new Unit1() {
            @Override
            public void invoke(Object[] args) throws Throwable {
                showDocPreview(args[0]);
            }
        }));
        XposedHelpers.callMethod(idc, "setBackHomeResponse", fn1(null));
        XposedHelpers.callMethod(idc, "openIdCard", activity);
    }

    /** 扫描仪：文档扫描(4) / 身份证(0) / 户口本(1) / 护照(2)。 */
    private static void launchIdScan(Activity activity, int idType) {
        Object device = XposedHelpers.callStaticMethod(cls(MODULE_CONFIG), "getCurrentDevice");
        if (device == null) {
            XposedInit.log("HomeMenuHook: no current device, skip id scan");
            return;
        }
        String host;
        if (isModel(device, "ROSEMARY") || isModel(device, "LAGER") || isModel(device, "APRICOT")) {
            host = "";
        } else {
            Object hd = XposedHelpers.callMethod(device, "getHanntoDevice");
            host = hd == null ? "" : (String) XposedHelpers.callMethod(hd, "getHostName");
        }
        int iotType = (Integer) XposedHelpers.callMethod(device, "getIotType");
        Object idScan = svc("getIdCardScanService");
        if (idScan == null) {
            return;
        }
        XposedHelpers.callMethod(idScan, "setPrintResponse", fn1(new Unit1() {
            @Override
            public void invoke(Object[] args) throws Throwable {
                showDocPreview(args[0]);
            }
        }));
        XposedHelpers.callMethod(idScan, "setBackHomeResponse", fn1(null));
        XposedHelpers.callMethod(idScan, "openIdScanScan", activity, host, idType, iotType);
    }

    // ------------------------------------------------------------ reflection

    private static void showDocPreview(Object docResult) {
        Object pps = svc("getPrintPreviewService");
        if (pps == null) {
            XposedInit.log("HomeMenuHook: printPreviewService is null");
            return;
        }
        XposedHelpers.callMethod(pps, "showDocPreview", docResult);
    }

    private static ArrayList<String> uriListToFiles(Object photoPickResult) {
        try {
            Object uriList = XposedHelpers.callMethod(photoPickResult, "getUriList");
            if (!(uriList instanceof List)) {
                return null;
            }
            Object fop = XposedHelpers.getStaticObjectField(cls(FILE_OPERATE), "INSTANCE");
            ArrayList<String> files = new ArrayList<>();
            for (Object uri : (List<?>) uriList) {
                Object file = XposedHelpers.callMethod(fop, "uri2File", uri, null);
                files.add((String) XposedHelpers.callMethod(file, "getAbsolutePath"));
            }
            return files;
        } catch (Throwable t) {
            XposedInit.log("HomeMenuHook uriListToFiles failed: " + t);
            return null;
        }
    }

    private static Object newPickPhotoEntity() {
        Object entity = XposedHelpers.newInstance(cls(ENTITY_PICK_PHOTO));
        XposedHelpers.callMethod(entity, "setMaxNumber", 9);
        return entity;
    }

    private static boolean isModel(Object device, String enumName) {
        try {
            Object modelEnum = XposedHelpers.getStaticObjectField(cls(DEVICE_TYPE), enumName);
            String model = (String) XposedHelpers.callMethod(modelEnum, "getModel");
            String current = (String) XposedHelpers.callMethod(device, "getDeviceModel");
            return current != null && current.equals(model);
        } catch (Throwable t) {
            return false;
        }
    }

    private static int mipmapId(String rClass, String name) {
        try {
            Class<?> c = XposedHelpers.findClass(rClass, CL);
            return XposedHelpers.getStaticIntField(c, name);
        } catch (Throwable t) {
            XposedInit.log("mipmap id failed: " + rClass + "/" + name + " " + t);
            return 0;
        }
    }

    private static Object svc(String getter) {
        try {
            return XposedHelpers.callStaticMethod(sRouterUtil, getter);
        } catch (Throwable t) {
            XposedInit.log("HomeMenuHook svc fail: " + getter + " " + t);
            return null;
        }
    }

    private static Class<?> cls(String name) {
        return XposedHelpers.findClass(name, CL);
    }

    /** kotlin.jvm.functions.Function1 的 Proxy 实现（单参回调）。body 为 null 时为空回调。 */
    private static Object fn1(final Unit1 body) {
        return proxy("kotlin.jvm.functions.Function1", new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if ("invoke".equals(name)) {
                    if (body != null && args != null) {
                        body.invoke(args);
                    }
                    return null; // Unit
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == args[0];
                }
                if ("toString".equals(name)) {
                    return "HomeMenuHook-Function1";
                }
                return null;
            }
        });
    }

    /** kotlin.jvm.functions.Function2 的 Proxy 实现（双参回调）。body 为 null 时为空回调。 */
    private static Object fn2(final Unit2 body) {
        return proxy("kotlin.jvm.functions.Function2", new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if ("invoke".equals(name)) {
                    if (body != null && args != null) {
                        body.invoke(args);
                    }
                    return null; // Unit
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == args[0];
                }
                if ("toString".equals(name)) {
                    return "HomeMenuHook-Function2";
                }
                return null;
            }
        });
    }

    private static Object proxy(String ifaceName, InvocationHandler handler) {
        Class<?> iface = XposedHelpers.findClass(ifaceName, CL);
        return Proxy.newProxyInstance(CL, new Class<?>[]{iface}, handler);
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

    /** Function1 回调体。 */
    private interface Unit1 {
        void invoke(Object[] args) throws Throwable;
    }

    /** Function2 回调体。 */
    private interface Unit2 {
        void invoke(Object[] args) throws Throwable;
    }
}
