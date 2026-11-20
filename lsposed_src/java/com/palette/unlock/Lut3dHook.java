package com.palette.unlock;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;

/**
 * 第四层：com.oplus.camera.common.utils.Lut3dDataManager
 *
 * ===== 方法表（classes.dex，proto_ids 实测）=====
 *   e (int, int, String) -> boolean    <-- 调色盘 LUT 初始化入口（真实名是 e，不是 f！）
 *   f (String)           -> boolean    <-- 按 group 名查询
 *   k (String)           -> void
 *   n (String)           -> void
 *   nativeLoadBinaryLutFile (String, boolean) -> float[]
 *   ... 其余见 _filtersdk 审计输出
 *
 * ===== 早期版本的错误 =====
 *   曾假设 f(int,int,String) 是初始化入口，实际签名是 f(String)；
 *   真正的 (int,int,String) 方法是 e。签名不匹配导致 hook 从未生效。
 *
 * ===== 本 hook =====
 *   1) hook e(int,int,String)：调原方法，若返回 false 则强制 true 并打日志
 *   2) hook f(String)：仅观测，打前若干次日志
 */
final class Lut3dHook {
    private static final String CLS = "com.oplus.camera.common.utils.Lut3dDataManager";
    private static int sLogged;
    private static int sForced;

    private Lut3dHook() {}

    static void install(XposedModule module, ClassLoader cl) {
        int n = 0;
        try {
            Class<?> cls = cl.loadClass(CLS);
            for (final Method m : cls.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                String nm = m.getName();
                // 调色盘 init 入口：e(int, int, String) -> boolean
                if ("e".equals(nm) && p.length == 3
                        && p[0] == int.class && p[1] == int.class && p[2] == String.class
                        && m.getReturnType() == boolean.class) {
                    try {
                        m.setAccessible(true);
                        module.hook(m).intercept(new EHook());
                        n++;
                        XLog.i("hooked Lut3dDataManager.e(int,int,String)  <- 调色盘 init 入口");
                    } catch (Throwable t) { XLog.w("hook Lut3d.e fail", t); }
                    continue;
                }
                // 查询入口：f(String) -> boolean
                if ("f".equals(nm) && p.length == 1 && p[0] == String.class
                        && m.getReturnType() == boolean.class) {
                    try {
                        m.setAccessible(true);
                        module.hook(m).intercept(new FHook());
                        n++;
                        XLog.i("hooked Lut3dDataManager.f(String)");
                    } catch (Throwable t) { XLog.w("hook Lut3d.f fail", t); }
                }
            }
        } catch (Throwable t) {
            XLog.w("Lut3dHook skipped: " + t);
        }
        XLog.i("Lut3dHook installed=" + n);
    }

    /** e(int,int,String) — 调色盘 LUT 初始化，false 时强制 true */
    static final class EHook implements XposedInterface.Hooker {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            PathInjectHook.retryInstallIfNeeded();
            Object r = chain.proceed();
            // e() 完成后强制上传 LUT
            try {
                // 从单例获取 Lut3dDataManager
                Object ldmInst = PaletteProcessUnitHook.sLdmInstance;
                if (ldmInst == null) {
                    try {
                        Class<?> holder = PaletteProcessUnitHook.sLdmClass != null
                                ? PaletteProcessUnitHook.sLdmClass.loadClass("com.oplus.camera.common.utils.Lut3dDataManager$b")
                                : Class.forName("com.oplus.camera.common.utils.Lut3dDataManager$b");
                        java.lang.reflect.Field af = holder.getDeclaredField("a");
                        af.setAccessible(true);
                        ldmInst = af.get(null);
                        PaletteProcessUnitHook.sLdmInstance = ldmInst;
                    } catch (Throwable singletonErr) { /* 静默：走 sLastLut 路径 */ }
                }
                XLog.i("e() 后检查: gl=" + (PaletteProcessUnitHook.sGlInstance != null) + " ldm=" + (ldmInst != null));
                if (PaletteProcessUnitHook.sGlInstance != null && ldmInst != null) {
                    java.lang.reflect.Field cf2 = ldmInst.getClass().getDeclaredField("c");
                    cf2.setAccessible(true);
                    Object cval2 = cf2.get(PaletteProcessUnitHook.sLdmInstance);
                    if (cval2 instanceof float[]) {
                        java.lang.reflect.Method vM = PaletteProcessUnitHook.sGlInstance.getClass().getMethod("v", float[].class, int.class, boolean.class);
                        vM.invoke(PaletteProcessUnitHook.sGlInstance, cval2, 64, true);
                        XLog.i("gl.s.v(LUT,64,true) len=" + ((float[])cval2).length);
                    }
                }
            } catch (Throwable fvErr) { XLog.w("gl.s.v fail: " + fvErr); }
            boolean raw = r instanceof Boolean && (Boolean) r;
            if (!raw) {
                sForced++;
                if (sForced <= 20) {
                    XLog.i("Lut3d.e(mode=" + chain.getArg(0) + ", hdr=" + chain.getArg(1)
                            + ", group=" + chain.getArg(2) + ") 原始=false -> 强制 true");
                }
                return Boolean.TRUE;
            }
            if (sLogged < 8) {
                sLogged++;
                XLog.i("Lut3d.e(mode=" + chain.getArg(0) + ", hdr=" + chain.getArg(1)
                        + ", group=" + chain.getArg(2) + ") -> true");
            }
            return r;
        }
    }

    /** f(String) — 仅观测 */
    static final class FHook implements XposedInterface.Hooker {
        private int c;
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object r = chain.proceed();
            if (c++ < 8) XLog.i("Lut3d.f(" + chain.getArg(0) + ") -> " + r);
            return r;
        }
    }
}
