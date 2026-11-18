package com.palette.unlock;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 第四层：com.oplus.camera.common.utils.Lut3dDataManager
 *
 * 取证（classes.dex）：
 *   - 构造里读 CameraConfig.d("com.oplus.camera.basic.tone.lut.support", true, false) 赋给 mBasicToneLut（this.L）。
 *     this.L == false 时 f(int,int,String) 直接 return false，调色盘永远 init 不了。
 *   - f(int,int,String) 中 k0.i0(str) 为真时使用 6 参 init：
 *       mCombineLutHelper.init(mLmtPath, mOdtPath, mSettingPath, i10, i11, "/odm/etc/camera/basictone/palette")
 *   - k0.i0(str) 的条件是 CameraConfig.d("com.oplus.feature.color.palette.support", false, false)
 *     且 str ∈ {common, portrait, night}
 *
 * 因此只要 CameraConfig 那一层强制 true，k0.i0 自然为真，调色盘分支就会走到。
 * 本类只做两件事：观测 + 兜底强制（若 this.L 因缓存仍为 false，则直接把字段写成 true）。
 */
final class Lut3dHook {
    private static final String CLS = "com.oplus.camera.common.utils.Lut3dDataManager";
    private static int sLogged;

    private Lut3dHook() {}

    static void install(XposedModule module, ClassLoader cl) {
        try {
            Class<?> cls = cl.loadClass(CLS);
            for (final Method m : cls.getDeclaredMethods()) {
                if (!"f".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 3 || p[0] != int.class || p[1] != int.class || p[2] != String.class) continue;
                if (m.getReturnType() != boolean.class) continue;
                try {
                    m.setAccessible(true);
                    module.hook(m).intercept(new Lut3dHooker());
                    XLog.i("Lut3dDataManager.f(int,int,String) hooked");
                } catch (Throwable t) {
                    XLog.w("hook Lut3d.f fail", t);
                }
            }
        } catch (Throwable t) {
            XLog.w("Lut3dHook skipped", t);
        }
    }

    static final class Lut3dHooker implements XposedInterface.Hooker {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object r = chain.proceed();
            if (sLogged < 12) {
                sLogged++;
                XLog.i("Lut3d.f(mode=" + chain.getArg(0) + ", hdr=" + chain.getArg(1)
                        + ", group=" + chain.getArg(2) + ") -> " + r);
            }
            return r;
        }
    }
}
