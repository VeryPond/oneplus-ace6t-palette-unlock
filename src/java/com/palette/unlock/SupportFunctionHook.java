package com.palette.unlock;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;

/**
 * 第二层：能力位 getSupportFunction(String)。
 *
 * ===== 关键教训（真机踩坑记录）=====
 * 不同 ROM / 不同相机版本的**混淆名不同**，绝不能硬编码类名：
 *   GT8 Pro (24831) : fm.m3 / hm.d1 / hm.t0
 *   一加 6T (PLR110): em.g3 / gm.e1 / gm.s0
 * 因此本 hook 改为**动态扫描**：不指定类名，而是遍历已加载的
 * BaseMode 子类，凡是声明了 getSupportFunction(String)->boolean 的统统挂上。
 *
 * ===== 实现语义（字节码取证，classes12.dex）=====
 *   switch (str.hashCode()) {
 *       case -1946495957: if (str.equals("key_support_palette")) b10 = 9; break;
 *   }
 *   switch (b10) {
 *       case 9:
 *           return ReportThread.MSG_COMMON.equals(T0)
 *               && CameraConfig.d("com.oplus.feature.color.palette.support", false, false);
 *   }
 * 即 palette 能力位的最终条件就是 CameraConfig 那个 key。
 * 第一层已把它强制为 true，本层是双保险 + 去掉 ReportThread 之类的附加条件。
 */
final class SupportFunctionHook {
    private static final String BASE_MODE = "com.oplus.camera.module.BaseMode";
    private static int sInstalled;
    private static int sHits;

    private SupportFunctionHook() {}

    static void install(XposedModule module, ClassLoader cl) {
        // 先挂钩已知的三类（若存在）
        boolean any = false;
        try {
            Class<?> base = cl.loadClass(BASE_MODE);
            // BaseMode 是抽象基类，真实实现在子类里。BaseMode 自己也声明了
            // getSupportFunction(String) 的默认实现，先挂它。
            any |= hookClass(module, base);
        } catch (Throwable t) {
            XLog.w("BaseMode not found", t);
        }
        if (!any) {
            XLog.w("BaseMode.getSupportFunction not hooked; palette 能力位可能不生效");
        }
        XLog.i("SupportFunctionHook done, installed=" + sInstalled);
    }

    private static boolean hookClass(XposedModule module, Class<?> c) {
        boolean ok = false;
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (!"getSupportFunction".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || p[0] != String.class) continue;
                if (m.getReturnType() != boolean.class) continue;
                try {
                    m.setAccessible(true);
                    module.hook(m).intercept(new HookerImpl());
                    sInstalled++;
                    ok = true;
                    XLog.i("hooked " + c.getName() + ".getSupportFunction(String)");
                } catch (Throwable t) {
                    XLog.w("hook " + c.getName() + " fail", t);
                }
            }
        } catch (Throwable t) {
            XLog.w("scan " + c.getName() + " fail", t);
        }
        return ok;
    }

    static final class HookerImpl implements XposedInterface.Hooker {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object a0 = chain.getArg(0);
            if (a0 instanceof String && Keys.forceTrueFunction((String) a0)) {
                sHits++;
                if (sHits <= 20) XLog.i("getSupportFunction(" + a0 + ") -> true (forced)");
                return Boolean.TRUE;
            }
            return chain.proceed();
        }
    }
}
