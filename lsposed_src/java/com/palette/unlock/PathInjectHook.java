package com.palette.unlock;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 第十层：CombineLut 路径参数重定向（关键层）。
 *
 * ===== 为什么必须有这一层 =====
 * 前九层解决了"UI 能开、Feature 能建、LUT 纹理能建"，但调色仍然没效果。
 * 根因：palette 的 LUT 不在 /odm（那是只读分区，写入无效），
 * 而是由相机进程从【自己的私有目录】读取，路径由
 * CombineLutProcessUnitSDK.init 的 6 个参数传入。
 *
 * 已通过同类方案（X8CamUnlock，Find X8 上验证可用）交叉印证：
 *   slot 1 = odt 目录
 *   slot 2 = setting 目录
 *   slot 5 = palette 目录（HDR/SDR 的父目录）
 *
 * 本层做两件事：
 *   1) 把模块内置的 palette LUT 与 ini 部署到
 *      /data/data/com.oplus.camera/files/palette_unlock/basictone/
 *      （相机进程自己的沙箱，无需 root 权限，写这里必然生效）
 *   2) hook CombineLutProcessUnitSDK.init(String,String,String,int,int,String)
 *      把 slot 2 与 slot 5 替换成私有目录路径
 *
 * ===== 字节码取证 =====
 *   CombineLutProcessUnitSDK.init 签名 (String,String,String,int,int,String) -> int
 *   该签名与 X8CamUnlock 在 Find X8 上 hook 的完全一致。
 */
final class PathInjectHook {

    /** 相机应用私有部署根（由相机进程自己创建，必然可写）。 */
    static final String ROOT_NAME = "palette_unlock/basictone";

    /** /odm 系统只读源（只读，不写）。 */
    private static final String ODM = "/odm/etc/camera/basictone";

    /** LUT 文件名（与 ini 的 SCLutNum=9 对应）。 */
    private static final String[] LUTS = {
            "SC16Lut0", "SC16Lut1", "SC16Lut2", "SC16Lut3", "SC16Lut4",
            "SC16Lut5", "SC16Lut6", "SC16Lut7", "SC16Lut8"
    };

    /** LMT（影调）LUT：/odm 的 lmt 目录在相机进程里不可列，改从 assets 解。 */
    private static final String[] LMTS = { "BaseLut", "CWCM16Lut0", "CWCM16Lut1", "CWCM16Lut2", "CWCM16Lut3", "CWCM16Lut4", "CWCM16Lut5", "CWCM16Lut6", "CWCM16Lut7", "CWCM16Lut8", "CWCM32Lut0", "CWCM32Lut1", "CWCM32Lut2", "CWCM32Lut3", "CWCM32Lut4", "CWCM32Lut5", "CWCM32Lut6", "CWCM32Lut7", "CWCM32Lut8", "LMTLut0", "LMTLut1", "LMTLut2", "LMTLut3", "SC16Lut0", "SC16Lut1", "SC16Lut2", "SC16Lut3", "SC16Lut4", "SC16Lut5", "SC16Lut6", "SC16Lut7", "SC16Lut8", "SC32Lut0", "SC32Lut1", "SC32Lut2", "SC32Lut3", "SC32Lut4", "SC32Lut5", "SC32Lut6", "SC32Lut7", "SC32Lut8" };

    /** ODT（影调/色调映射）LUT：/odm 的 odt 目录在相机进程里 listFiles 可能返回 null，改从 assets 解。 */
    private static final String[] ODTS = {
            "ODT_Pre", "ODT_Photo"
    };

    /** palette 相关 ini。 */
    private static final String[] INIS = {
            "SimTool_Palette_Photo_Normal.ini",
            "SimTool_Palette_Photo_Portrait.ini",
            "SimTool_Palette_Photo_Night.ini",
            "SimTool_Preview_Palette_Normal.ini"
    };

    private static String sSettingPath;
    private static String sPalettePath;
    private static String sOdtPath;
    private static String sLmtPath;
    private static volatile boolean sDeployed;
    private static volatile int sAttempts;
    private static volatile int sLastCode = Integer.MIN_VALUE;
    private static volatile boolean sInitOk;
    private static boolean sLogged;
    /** ODT 扫描：-1 表示未确定，0/1 表示已找到可用值。 */
    private static volatile int sOdtMode = -1;
    /** ODT 候选值，与 X8CamUnlock 一致。 */
    private static final int[] ODT_CANDIDATES = {0, 1};

    private PathInjectHook() {}

    static boolean deployed() { return sDeployed; }
    static boolean initOk()   { return sInitOk; }
    static int lastCode()     { return sLastCode; }

    /**
     * 部署资源到相机私有目录。
     * 由 hook 拦截时在【相机进程内】调用，所以 Application 是相机的，
     * getFilesDir() 返回 /data/data/com.oplus.camera/files。
     */
    static synchronized boolean ensureDeployed(XposedModule module, ClassLoader cl) {
        if (sDeployed) return true;
        try {
            Object app = currentApplication();
            if (app == null) {
                XLog.w("PathInject: currentApplication() = null, 无法部署");
                return false;
            }
            java.io.File filesDir = (java.io.File)
                    app.getClass().getMethod("getFilesDir").invoke(app);
            if (filesDir == null) {
                XLog.w("PathInject: getFilesDir() = null");
                return false;
            }
            // 诊断：确认当前进程确实是相机，避免部署到错误目录
            try {
                String procName = currentProcessName();
                XLog.i("PathInject: 当前进程 = " + procName
                        + "  filesDir = " + filesDir.getAbsolutePath());
                if (procName != null && !procName.contains("com.oplus.camera")) {
                    XLog.w("PathInject: 进程名不含 com.oplus.camera，"
                            + "部署位置可能不对（仍继续尝试）");
                }
            } catch (Throwable ignored) {}

            java.io.File root = new java.io.File(filesDir, ROOT_NAME);
            java.io.File setting = new java.io.File(root, "setting");
            java.io.File palette = new java.io.File(root, "palette");
            java.io.File hdr = new java.io.File(palette, "HDR");
            java.io.File sdr = new java.io.File(palette, "SDR");
            java.io.File odt = new java.io.File(root, "odt");
            java.io.File lmt = new java.io.File(root, "lmt");
            for (java.io.File d : new java.io.File[]{root, setting, palette, hdr, sdr, odt, lmt}) {
                if (!d.exists() && !d.mkdirs()) {
                    XLog.w("PathInject: mkdirs fail " + d);
                }
            }
            sSettingPath = setting.getAbsolutePath();
            sPalettePath = palette.getAbsolutePath();
            sOdtPath = odt.getAbsolutePath();
            sLmtPath = lmt.getAbsolutePath();

            // ---- 1. 先把 /odm 的 setting 与 odt 复制过来（相机自己可读）----
            int sys1 = copyTree(new java.io.File(ODM, "setting"), setting);
            int sys2 = copyTree(new java.io.File(ODM, "odt"), odt);

            // ---- 2. 再从模块 APK 的 assets 解出 palette LUT 与 ini（后写，确保 palette 配置以官方 LUT 包为准）----
            java.util.zip.ZipFile modZip = openModuleZip(module);
            int copied = 0;
            if (modZip != null) {
                try {
                    for (String name : LUTS) {
                        byte[] data = readEntry(modZip, "assets/palette/" + name);
                        if (data == null || data.length != 49152) {
                            XLog.w("PathInject: asset " + name + " 尺寸异常 "
                                    + (data == null ? -1 : data.length) + "，应 49152");
                            continue;
                        }
                        writeIfAbsent(new java.io.File(hdr, name), data);
                        writeIfAbsent(new java.io.File(sdr, name), data);
                        copied += 2;
                    }
                    for (String name : INIS) {
                        byte[] data = readEntry(modZip, "assets/palette_setting/" + name);
                        if (data == null) {
                            XLog.w("PathInject: asset ini 缺失 " + name);
                            continue;
                        }
                        writeIfAbsent(new java.io.File(setting, name), data);
                        copied++;
                    }
                    // ---- 2a. LMT（影调）LUT 从 assets 解出 ----
                    for (String name : LMTS) {
                        byte[] data = readEntry(modZip, "assets/lmt/" + name);
                        if (data == null || data.length < 1024) {
                            XLog.w("PathInject: asset lmt 缺失/异常 " + name
                                    + " (" + (data == null ? -1 : data.length) + ")");
                            continue;
                        }
                        try {
                            java.io.FileOutputStream fos =
                                    new java.io.FileOutputStream(new java.io.File(lmt, name), false);
                            fos.write(data);
                            fos.close();
                            copied++;
                        } catch (Throwable tw) {
                            XLog.w("PathInject: 写 lmt " + name + " 失败", tw);
                        }
                    }
                    // ---- 2b. ODT LUT 也从 assets 解出（/odm 的 odt 在 app 进程里 listFiles 可能为 null）----
                    for (String name : ODTS) {
                        byte[] data = readEntry(modZip, "assets/odt/" + name);
                        if (data == null || data.length < 1024) {
                            XLog.w("PathInject: asset odt 缺失/异常 " + name
                                    + " (" + (data == null ? -1 : data.length) + ")");
                            continue;
                        }
                        try {
                            java.io.FileOutputStream fos =
                                    new java.io.FileOutputStream(new java.io.File(odt, name), false);
                            fos.write(data);
                            fos.close();
                            copied++;
                        } catch (Throwable tw) {
                            XLog.w("PathInject: 写 odt " + name + " 失败", tw);
                        }
                    }
                } finally {
                    try { modZip.close(); } catch (Throwable ignored) {}
                }
            } else {
                XLog.w("PathInject: 打不开模块 APK，无法解出 assets");
            }

            // ---- 3. ODT_Pre 兜底：缺它时用 ODT_Photo 顶替 ----
            java.io.File pre   = new java.io.File(odt, "ODT_Pre");
            java.io.File photo = new java.io.File(odt, "ODT_Photo");
            if (!pre.exists() && photo.exists()) {
                try {
                    copyFile(photo, pre);
                    XLog.i("PathInject: ODT_Pre 由 ODT_Photo 补齐");
                } catch (Throwable t) { XLog.w("clone ODT_Pre fail", t); }
            }

            // ---- 4. 校验 ----
            java.io.File lut8 = new java.io.File(hdr, "SC16Lut8");
            boolean ok = lut8.exists() && lut8.length() == 49152;
            XLog.i("PathInject 部署" + (ok ? "成功" : "失败")
                    + ": assets=" + copied
                    + " 系统setting=" + sys1
                    + " 系统odt=" + sys2
                    + " lut8=" + (lut8.exists() ? lut8.length() : -1)
                    + " settingPath=" + setting);
            sDeployed = ok;
            return ok;
        } catch (Throwable t) {
            XLog.w("PathInject ensureDeployed 异常", t);
            return false;
        }
    }

    /**
     * 改写私有目录下 SimTool_Dolby.ini / SimTool_Master.ini 里的 ODT 值。
     * 与 X8CamUnlock 的 patchOdt 行为一致：逐行扫描，命中 key == "ODT" 则
     * 整行替换为 "ODT  = <n>"。
     */
    private static void patchOdt(int value) {
        for (String fn : new String[]{"SimTool_Dolby.ini", "SimTool_Master.ini"}) {
            java.io.File f = new java.io.File(sSettingPath, fn);
            if (!f.exists()) continue;
            java.io.BufferedReader br = null;
            java.io.FileOutputStream fo = null;
            try {
                StringBuilder sb = new StringBuilder();
                br = new java.io.BufferedReader(new java.io.InputStreamReader(
                        new java.io.FileInputStream(f), "UTF-8"));
                String line;
                boolean has = java.io.File.separatorChar != ' ';
                while ((line = br.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq > 0 && "ODT".equals(line.substring(0, eq).trim())) {
                        sb.append("ODT  = ").append(value).append('\n');
                    } else {
                        sb.append(line).append('\n');
                    }
                }
                br.close();
                br = null;
                fo = new java.io.FileOutputStream(f);
                fo.write(sb.toString().getBytes("UTF-8"));
                fo.close();
                fo = null;
            } catch (Throwable t) {
                XLog.w("patchOdt " + fn + " fail", t);
            } finally {
                if (br != null) try { br.close(); } catch (Throwable ignored) {}
                if (fo != null) try { fo.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 安装路径重定向 hook。 */
    static void install(XposedModule module, ClassLoader cl) {
        sCl = cl;
        sModule = module;
        int n = tryInstall();
        if (n == 0) {
            XLog.w("PathInjectHook: 首次安装失败（SDK 可能尚未加载），"
                    + "将在 createFeature / processImage 触发时重试");
        } else {
            XLog.i("PathInjectHook installed=" + n
                    + " (目标签名 init(String,String,String,int,int,String)->int)");
        }
    }

    private static ClassLoader sCl;
    private static XposedModule sModule;
    private static volatile int sRetries;

    /** 供其它 hook 在关键时机调用，补挂 init。最多重试 20 次。 */
    static void retryInstallIfNeeded() {
        // 关键：只要 SDK 还没挂上就继续重试，不受 Helper 已成功的影响
        if (sSdkHooked || sRetries >= 60 || sCl == null) return;
        sRetries++;
        int n = tryInstall();
        if (n > 0) {
            XLog.i("PathInjectHook 延迟安装成功 installed=" + n
                    + " (第 " + sRetries + " 次尝试)");
        } else if (sRetries <= 3 || sRetries % 5 == 0) {
            XLog.w("PathInjectHook 延迟安装第 " + sRetries + " 次仍失败");
        }
    }

    private static volatile boolean sSdkHooked;   // CombineLutProcessUnitSDK 是否已挂上
    private static volatile boolean sHelperHooked; // CombineLutHelper 是否已挂上
    private static int sHooked;

    private static int tryInstall() {
        int n = 0;
        // SDK 类是重点：它在延迟加载的 dex 里，早期必然失败，必须反复重试
        if (!sSdkHooked) {
            int r = hookInit(sModule, sCl,
                    "com.oplus.ocs.camera.ipusdk.processunit.combinelut.CombineLutProcessUnitSDK");
            if (r > 0) {
                sSdkHooked = true;
                XLog.i("PathInjectHook: CombineLutProcessUnitSDK 挂载成功");
            }
            n += r;
        }
        if (!sHelperHooked) {
            int r = hookInit(sModule, sCl, "com.oplus.ocs.camera.CombineLutHelper");
            if (r > 0) sHelperHooked = true;
            n += r;
        }
        sHooked = (sSdkHooked ? 1 : 0) + (sHelperHooked ? 1 : 0);
        return n;
    }

    private static int hookInit(XposedModule module, ClassLoader cl, String clsName) {
        try {
            Class<?> cls = cl.loadClass(clsName);
            for (final Method m : cls.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!"init".equals(m.getName())) continue;
                if (p.length != 6) continue;
                if (p[0] != String.class || p[1] != String.class || p[2] != String.class
                        || p[3] != int.class || p[4] != int.class || p[5] != String.class) continue;
                if (m.getReturnType() != int.class) continue;
                m.setAccessible(true);
                module.hook(m).intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        sAttempts++;
                        Object a5 = chain.getArg(5);
                        String pal = a5 instanceof String ? (String) a5 : "";
                        boolean looksPalette = pal.contains("palette")
                                || pal.toLowerCase().contains("palette");
                        if (!looksPalette) {
                            // 不是 palette 的初始化，原样放行
                            return chain.proceed();
                        }
                        if (!ensureDeployed(module, cl)) {
                            XLog.w("PathInject: 部署不可用，保留原始路径");
                            return chain.proceed();
                        }
                        Object[] args = new Object[6];
                        for (int i = 0; i < 6; i++) args[i] = chain.getArg(i);
                        if (!sLogged) {
                            sLogged = true;
                            XLog.i("palette init 原始参数: [0]=" + shortName(args[0])
                                    + " [1]=" + shortName(args[1])
                                    + " [2]=" + shortName(args[2])
                                    + " [3]=" + args[3]
                                    + " [4]=" + args[4]
                                    + " [5]=" + shortName(args[5]));
                        }
                        // ---- 若已探明 ODT 值，直接复用 ----
                        if (sOdtMode >= 0 && sInitOk) {
                            Object[] a = new Object[6];
                            for (int i = 0; i < 6; i++) a[i] = chain.getArg(i);
                            a[0] = sLmtPath;
                            a[1] = sOdtPath;
                            a[2] = sSettingPath;
                            a[5] = sPalettePath;
                            patchOdt(sOdtMode);
                            Object r = chain.proceed(a);
                            int code = r instanceof Number ? ((Number) r).intValue() : -999;
                            sLastCode = code;
                            if (sAttempts <= 8) {
                                XLog.i("palette init 复用 odt=" + sOdtMode + " code=" + code);
                            }
                            return r;
                        }

                        // ---- 扫描 ODT 候选值，找 code==0 的组合 ----
                        int bestCode = Integer.MIN_VALUE;
                        for (int odt : ODT_CANDIDATES) {
                            Object[] a = new Object[6];
                            for (int i = 0; i < 6; i++) a[i] = chain.getArg(i);
                            a[0] = sLmtPath;
                            a[1] = sOdtPath;
                            a[2] = sSettingPath;
                            a[5] = sPalettePath;
                            Object r;
                            int code;
                            try {
                                patchOdt(odt);
                                r = chain.proceed(a);
                                code = r instanceof Number ? ((Number) r).intValue() : -999;
                            } catch (Throwable t) {
                                XLog.w("palette init odt=" + odt + " 抛异常", t);
                                continue;
                            }
                            XLog.i("palette init 探测 odt=" + odt + " code=" + code
                                    + " attempt=" + sAttempts);
                            if (code == 0) {
                                sOdtMode = odt;
                                sInitOk = true;
                                sLastCode = 0;
                                XLog.i("palette init 成功！odt=" + odt
                                        + "  setting=" + sSettingPath);
                                return r;
                            }
                            if (code > bestCode) {
                                bestCode = code;
                                sLastCode = code;
                            }
                        }
                        // ---- 全部失败：仍用私有路径，ODT 取 0 ----
                        sInitOk = false;
                        XLog.w("palette init 全部 ODT 尝试失败 bestCode=" + bestCode
                                + "，回落到 odt=0 继续");
                        Object[] fallback = new Object[6];
                        for (int i = 0; i < 6; i++) fallback[i] = chain.getArg(i);
                        fallback[1] = sOdtPath;
                        fallback[2] = sSettingPath;
                        fallback[5] = sPalettePath;
                        patchOdt(0);
                        sOdtMode = 0;
                        return chain.proceed(fallback);
                    }
                });
                XLog.i("hooked " + clsName + ".init(String,String,String,int,int,String)");
                return 1;
            }
        } catch (Throwable t) {
            XLog.w("PathInject hookInit(" + clsName + ") 跳过: " + t);
        }
        return 0;
    }

    // ---------------- 工具方法 ----------------

    private static String shortName(Object o) {
        String s = String.valueOf(o);
        int i = s.lastIndexOf('/');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    private static java.util.zip.ZipFile openModuleZip(XposedModule module) {
        try {
            Object app = currentApplication();
            if (app == null) return null;
            Object pm = app.getClass().getMethod("getPackageManager").invoke(app);
            Object ai = pm.getClass().getMethod("getApplicationInfo", String.class, int.class)
                    .invoke(pm, Prefs.PKG, 0);
            String src = (String) ai.getClass().getField("sourceDir").get(ai);
            if (src == null) return null;
            return new java.util.zip.ZipFile(src);
        } catch (Throwable t) {
            XLog.w("openModuleZip fail", t);
            return null;
        }
    }

    private static byte[] readEntry(java.util.zip.ZipFile zf, String name) {
        java.io.InputStream in = null;
        try {
            java.util.zip.ZipEntry e = zf.getEntry(name);
            if (e == null) return null;
            in = zf.getInputStream(e);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream((int) e.getSize());
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static void writeIfAbsent(java.io.File dst, byte[] data) {
        try {
            if (dst.exists() && dst.length() == data.length) return;
            java.io.File tmp = new java.io.File(dst.getParentFile(), dst.getName() + ".tmp");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
            try { fo.write(data); } finally { fo.close(); }
            if (dst.exists()) dst.delete();
            tmp.renameTo(dst);
        } catch (Throwable t) {
            XLog.w("writeIfAbsent " + dst.getName() + " fail", t);
        }
    }

    private static int copyTree(java.io.File srcDir, java.io.File dstDir) {
        int n = 0;
        try {
            java.io.File[] fs = srcDir.listFiles();
            if (fs == null) {
                XLog.i("PathInject: " + srcDir + " 不可列，跳过");
                return 0;
            }
            for (java.io.File f : fs) {
                java.io.File dst = new java.io.File(dstDir, f.getName());
                if (f.isDirectory()) { n += copyTree(f, dst); continue; }
                if (dst.exists() && dst.length() == f.length()) { n++; continue; }
                try { copyFile(f, dst); n++; } catch (Throwable t) { /* 单个失败不影响整体 */ }
            }
        } catch (Throwable t) {
            XLog.w("copyTree " + srcDir + " fail", t);
        }
        return n;
    }

    private static void copyFile(java.io.File src, java.io.File dst) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            try {
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            } finally { out.close(); }
        } finally { in.close(); }
    }

    /**
     * 拿到当前进程的 Application（三级回退）。
     * 第三级用 AppGlobals.getInitialApplication()，在极早期时机更可靠。
     */
    /** 取当前进程名，用于确认部署位置是否正确。 */
    private static String currentProcessName() {
        try {
            // API 28+: Application.getProcessName()
            Object app = currentApplication();
            if (app != null) {
                try {
                    Object n = app.getClass().getMethod("getProcessName").invoke(app);
                    if (n instanceof String) return (String) n;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            // 兜底：读 /proc/self/cmdline
            java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline");
            try {
                byte[] b = new byte[128];
                int r = in.read(b);
                if (r > 0) {
                    int end = 0;
                    while (end < r && b[end] != 0) end++;
                    return new String(b, 0, end, "UTF-8");
                }
            } finally { in.close(); }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object currentApplication() {
        // 1) ActivityThread.currentApplication()
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            try {
                Object app = at.getMethod("currentApplication").invoke(null);
                if (app != null) return app;
            } catch (Throwable ignored) {}
            // 2) ActivityThread.currentActivityThread().getApplication()
            try {
                Object th = at.getMethod("currentActivityThread").invoke(null);
                if (th != null) {
                    Object app = at.getMethod("getApplication").invoke(th);
                    if (app != null) return app;
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        // 3) AppGlobals.getInitialApplication()
        try {
            Class<?> ag = Class.forName("android.app.AppGlobals");
            Object app = ag.getMethod("getInitialApplication").invoke(null);
            if (app != null) return app;
        } catch (Throwable ignored) {}
        // 4) ActivityThread 的 mInitialApplication 字段
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object th = at.getMethod("currentActivityThread").invoke(null);
            if (th != null) {
                java.lang.reflect.Field f = at.getDeclaredField("mInitialApplication");
                f.setAccessible(true);
                Object app = f.get(th);
                if (app != null) return app;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
