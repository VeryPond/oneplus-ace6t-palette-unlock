package com.palette.unlock;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 第二层：能力位 getSupportFunction(String)。
 *
 * ===== 关键教训（真机踩坑记录）=====
 * 不同 ROM / 不同相机版本的混淆名不同，绝不能硬编码类名：
 *   GT8 Pro (24831) : fm.m3 / hm.d1 / hm.t0
 *   一加 6T (PLR110): em.g3 / gm.e1 / gm.s0
 *
 * 而且经静态统计，本 APK 里声明了 getSupportFunction(String) 的类多达 66 个
 * （gm.xx、rm.xx、em.xx 及其它子类），只 hook BaseMode 一个很可能被覆盖。
 * 所以本 hook 采用【批量挂载】：把所有已知模式的类一次性全挂上。
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
 */
final class SupportFunctionHook {

    /** 已知承载 getSupportFunction 的类：BaseMode 本体 + 全部 gm/rm/em 子类 */
    private static final String[] KNOWN = {
            "com.oplus.camera.module.BaseMode",
            "com.oplus.camera.module.a",
            // 一加 6T (PLR110)
            "em.g3", "em.g4$b", "em.v3",
            "gm.c0", "gm.d0", "gm.e0", "gm.e1", "gm.f3", "gm.j2", "gm.l0",
            "gm.l", "gm.m3", "gm.n1", "gm.r2", "gm.s0", "gm.u", "gm.v0",
            "gm.c3", "gm.e", "gm.f", "gm.h3", "gm.i3", "gm.i", "gm.j", "gm.k",
            "gm.l1", "gm.m0", "gm.p2", "gm.s1", "gm.u2", "gm.v2", "gm.w",
            "rm.c", "rm.f", "rm.g", "rm.h", "rm.m", "rm.t0", "rm.d", "rm.o", "rm.p", "rm.q",
            "gg.i0", "gg.y1", "hl.k0", "zg.a", "bi.c",
            // GT8 Pro (24831) 的对应类
            "fm.m3", "hm.d1", "hm.t0",
    };

    private static int sInstalled;
    private static int sHits;
    private static final Set<String> sDone = new HashSet<String>();

    private SupportFunctionHook() {}

    static void install(XposedModule module, ClassLoader cl) {
        for (String cn : KNOWN) {
            try {
                Class<?> c = cl.loadClass(cn);
                hookClass(module, c);
            } catch (Throwable ignored) {
                // 类不存在属正常
            }
        }
        if (sInstalled == 0) {
            XLog.w("getSupportFunction 一个都没挂上，palette 能力位可能不生效");
        }
        XLog.i("SupportFunctionHook done, installed=" + sInstalled);
    }

    private static void hookClass(XposedModule module, Class<?> c) {
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (!"getSupportFunction".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || p[0] != String.class) continue;
                if (m.getReturnType() != boolean.class) continue;
                // 去重：同一 Class 的同一方法只挂一次
                String key = c.getName() + "#" + m.toGenericString();
                if (!sDone.add(key)) continue;
                try {
                    m.setAccessible(true);
                    module.hook(m).intercept(new HookerImpl());
                    sInstalled++;
                } catch (Throwable t) {
                    XLog.w("hook " + c.getName() + " fail", t);
                }
            }
        } catch (Throwable t) {
            XLog.w("scan " + c.getName() + " fail", t);
        }
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
