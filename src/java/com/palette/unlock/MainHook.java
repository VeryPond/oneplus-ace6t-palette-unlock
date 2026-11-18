package com.palette.unlock;

import android.app.Application;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 调色盘强开模块入口。
 *
 * 取证依据（对 GT8 Pro 的 OplusCamera.apk / 36 个 dex 逐类反编译）：
 *   1. 资源侧：/odm/etc/camera/basictone/palette/{HDR,SDR}/SC16Lut0..8 已在 ROM 中
 *   2. 代码侧：PaletteProcessUnit / PalettePanelView / CameraPaletteFilterCOUISeekbar 全套齐全
 *   3. 唯一缺的是**开关**：
 *        CameraConfig.d("com.oplus.feature.color.palette.support", false, false)
 *      → ja.k0.i0(String) 恒 false
 *      → Lut3dDataManager.f() 永远走非 palette 的 init 分支
 *      → getSupportFunction("key_support_palette") 恒 false，UI 入口不生成
 *
 * ULT 因此本模块以「强制能力位」为主，路径重定向为辅。
 */
public class MainHook extends XposedModule {
    public static final String TARGET = "com.oplus.camera";

    private static String sModuleApk;

    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        XLog.i("onPackageLoaded pkg=" + pkg);
        if (!TARGET.equals(pkg)) return;

        ClassLoader cl = param.getDefaultClassLoader();
        Prefs.bind(this);

        try {
            Application app = currentApplication();
            if (app != null) {
                sModuleApk = app.getPackageManager()
                        .getApplicationInfo(Prefs.PKG, 0).sourceDir;
            }
        } catch (Throwable t) {
            XLog.w("resolve module apk fail", t);
        }
        PathRedirectHook.setModuleApk(sModuleApk);
        XLog.i("module apk = " + sModuleApk);

        tryInstall("CameraConfig", cl, 0);
        tryInstall("SupportFunction", cl, 1);
        tryInstall("CameraParameter", cl, 2);
        tryInstall("Lut3d", cl, 3);
        tryInstall("PathRedirect", cl, 4);
        tryInstall("PaletteSeekbarFix", cl, 5);
        tryInstall("FilterSdk", cl, 6);
        tryInstall("IPUFeature", cl, 7);
        tryInstall("PaletteProcessUnit", cl, 8);

        XLog.i("all hooks installed");
    }

    private void tryInstall(String name, ClassLoader cl, int which) {
        try {
            if (which == 0) {
                CameraConfigHook.install(this, cl);
            } else if (which == 1) {
                SupportFunctionHook.install(this, cl);
            } else if (which == 2) {
                CameraParameterHook.install(this, cl);
            } else if (which == 3) {
                Lut3dHook.install(this, cl);
            } else if (which == 4) {
                PathRedirectHook.install(this, cl);
            } else if (which == 5) {
                PaletteSeekbarFixHook.install(this, cl);
            } else if (which == 6) {
                FilterSdkHook.install(this, cl);
            } else if (which == 7) {
                IPUFeatureHook.install(this, cl);
            } else if (which == 8) {
                PaletteProcessUnitHook.install(this, cl);
            }
        } catch (Throwable t) {
            XLog.w(name + " install fail", t);
        }
    }

    private static Application currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Object app = at.getMethod("getApplication").invoke(thread);
            return (Application) app;
        } catch (Throwable t) {
            return null;
        }
    }
}
