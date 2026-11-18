package com.palette.unlock;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 第五层（兜底）：把调色盘 LUT 部署到相机私有目录，并改写 CombineLutHelper.init 的 palette 路径参数。
 *
 * 为什么需要：
 *   Lut3dDataManager 调的是 CombineLutHelper.init(lmt, odt, setting, i10, i11, "/odm/etc/camera/basictone/palette")
 *   —— 第 6 个参数是写死的 "/odm/etc/camera/basictone/palette"。
 *   如果该机型 /odm 上没有 palette 目录（GT8 Pro 有，6T 没有），init 会失败。
 *
 * 本层做两件事：
 *   1. 把模块 assets 里的 18 个 SC16Lut + 4 个 ini 铺到
 *      /data/data/com.oplus.camera/files/palette_unlock/basictone/{palette/{HDR,SDR},setting}
 *   2. hook CombineLutHelper.init(...6 参...) 与 CombineLutProcessUnitSDK.init，
 *      把第 6 参（palette 路径）改写成上述私有目录。
 *
 * 默认关闭（Prefs.redirectPath=false）。GT8 Pro 资源本来就齐，不需要开；
 * 只有在 objective 机型缺 palette 目录时才需要。
 */
final class PathRedirectHook {
    private static final String HELPER = "com.oplus.ocs.camera.CombineLutHelper";
    private static final String LUTS = "palette/";
    private static final String[] LUT_NAMES = {
            "SC16Lut0", "SC16Lut1", "SC16Lut2", "SC16Lut3", "SC16Lut4",
            "SC16Lut5", "SC16Lut6", "SC16Lut7", "SC16Lut8"
    };
    private static final String[] INI_NAMES = {
            "SimTool_Palette_Photo_Normal.ini",
            "SimTool_Palette_Photo_Portrait.ini",
            "SimTool_Palette_Photo_Night.ini",
            "SimTool_Preview_Palette_Normal.ini"
    };
    private static final int LUT_SIZE = 49152;

    private static volatile boolean sDeployed;
    private static String sPalettePath;
    private static String sSettingPath;

    private PathRedirectHook() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (!Prefs.redirectPath()) {
            XLog.i("PathRedirectHook disabled (redirect_path=false)");
            return;
        }
        try {
            Class<?> cls = cl.loadClass(HELPER);
            for (Method m : cls.getDeclaredMethods()) {
                if (!"init".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 6) continue;
                if (m.getReturnType() != int.class) continue;
                try {
                    m.setAccessible(true);
                    module.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (!ensureDeployed()) return chain.proceed();
                            Object[] args = new Object[6];
                            for (int i = 0; i < 6; i++) args[i] = chain.getArg(i);
                            Object a5 = args[5];
                            if (a5 instanceof String && ((String) a5).contains("palette")) {
                                args[5] = sPalettePath;
                                if (args[2] instanceof String && ((String) args[2]).contains("basictone")) {
                                    args[2] = sSettingPath;
                                }
                                XLog.i("CombineLutHelper.init redirect palette -> " + sPalettePath);
                                return chain.proceed(args);
                            }
                            return chain.proceed();
                        }
                    });
                    XLog.i("PathRedirectHook installed on " + HELPER + ".init(6)");
                } catch (Throwable t) {
                    XLog.w("hook CombineLutHelper.init fail", t);
                }
            }
        } catch (Throwable t) {
            XLog.w("PathRedirectHook skipped", t);
        }
    }

    /** 幂等部署：目标已存在且长度正确则跳过。 */
    static synchronized boolean ensureDeployed() {
        if (sDeployed) return true;
        try {
            File files = new File("/data/data/com.oplus.camera/files");
            File base = new File(files, "palette_unlock/basictone");
            File hdr = new File(base, "palette/HDR");
            File sdr = new File(base, "palette/SDR");
            File setting = new File(base, "setting");
            hdr.mkdirs(); sdr.mkdirs(); setting.mkdirs();

            ZipFile zf = new ZipFile(moduleApk());
            for (String n : LUT_NAMES) {
                File a = new File(hdr, n);
                File b = new File(sdr, n);
                if (a.length() != LUT_SIZE) copyAsset(zf, "palette/" + n, a);
                if (b.length() != LUT_SIZE) copyAsset(zf, "palette/" + n, b);
            }
            for (String n : INI_NAMES) {
                File t = new File(setting, n);
                if (t.length() <= 0) copyAsset(zf, "palette_setting/" + n, t);
            }
            zf.close();

            if (new File(hdr, "SC16Lut8").length() != LUT_SIZE) {
                XLog.w("deploy aborted: SC16Lut8 missing/short");
                return false;
            }
            sPalettePath = new File(base, "palette").getAbsolutePath();
            sSettingPath = setting.getAbsolutePath();
            sDeployed = true;
            XLog.i("deployed -> " + sPalettePath);
            return true;
        } catch (Throwable t) {
            XLog.w("deploy fail", t);
            return false;
        }
    }

    private static String sApk;

    static void setModuleApk(String path) { sApk = path; }

    private static String moduleApk() throws Exception {
        if (sApk != null) return sApk;
        throw new IllegalStateException("module apk path unknown");
    }

    private static void copyAsset(ZipFile zf, String entry, File dst) throws Exception {
        ZipEntry ze = zf.getEntry("assets/" + entry);
        if (ze == null) { XLog.w("asset missing: " + entry); return; }
        InputStream in = zf.getInputStream(ze);
        OutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
            try { out.close(); } catch (Throwable ignored) {}
        }
    }
}
