package com.palette.unlock;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 第十一层：能力位 B3(String) 全域强制。
 *
 * ===== 为什么需要这一层 =====
 * X8CamUnlock 在 Find X8 上只 hook 了一个混淆类 ag.v 的 B3(String)，
 * 让 key_support_palette 返回 true。但在 6T (PLR110) 的 APK 里，
 * B3(String) 这个能力位方法分布在【9 个类】里：
 *
 *   Lag/r
 *   Lcom/oplus/camera/feature/filter/view/f0
 *   Lcom/oplus/camera/module/BaseMode          <- 模式基类，UI 入口必经
 *   Lcom/oplus/camera/ui/CameraUIInterface
 *   Lhl/k0
 *   Lip/c1
 *   Lta/p
 *   Lzg/a
 *   Lzn/a
 *
 * 只 hook 其中一个，其余调用点仍返回原值，所以本层把 9 个全挂上，
 * 且只在入参为 palette 能力位时强制 true，避免影响其它能力位。
 *
 * ===== 与 SupportFunctionHook 的分工 =====
 * SupportFunctionHook 挂的是 getSupportFunction(String)；
 * 本层挂的是 B3(String) —— 两者是不同的能力位查询入口。
 */
final class SupportFlagHook {

    /** 6T APK 里所有定义 B3(String) 的类（字节码扫描得到）。 */
    private static final String[] CLASSES = {
            "ag.r",
            "com.oplus.camera.feature.filter.view.f0",
            "com.oplus.camera.module.BaseMode",
            "com.oplus.camera.ui.CameraUIInterface",
            "hl.k0",
            "ip.c1",
            "ta.p",
            "zg.a",
            "zn.a"
    };

    private static int sInstalled;
    private static int sHits;

    private SupportFlagHook() {}

    static void install(XposedModule module, ClassLoader cl) {
        for (String cn : CLASSES) {
            try {
                Class<?> c = cl.loadClass(cn);
                hookClass(module, c, cn);
            } catch (Throwable ignored) {
                // 类不存在属正常（不同 ROM 版本混淆名不同）
            }
        }
        XLog.i("SupportFlagHook installed=" + sInstalled + " (B3(String) 全域扫描)");
    }

    private static void hookClass(XposedModule module, final Class<?> c, final String cn) {
        try {
            for (final Method m : c.getDeclaredMethods()) {
                if (!"B3".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || p[0] != String.class) continue;
                if (m.getReturnType() != boolean.class) continue;
                try {
                    m.setAccessible(true);
                    module.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain)
                                throws Throwable {
                            Object a0 = chain.getArg(0);
                            if (a0 instanceof String && Keys.forceTrueFunction((String) a0)) {
                                sHits++;
                                if (sHits <= 20) {
                                    XLog.i("B3[" + shortName(cn) + "](" + a0 + ") -> true");
                                }
                                return Boolean.TRUE;
                            }
                            return chain.proceed();
                        }
                    });
                    sInstalled++;
                    XLog.i("hooked B3(String) in " + shortName(cn));
                } catch (Throwable t) {
                    XLog.w("hook B3 in " + cn + " 失败: " + t);
                }
            }
        } catch (Throwable t) {
            XLog.w("SupportFlagHook 扫描 " + cn + " 失败: " + t);
        }
    }

    private static String shortName(String s) {
        int i = Math.max(s.lastIndexOf('.'), s.lastIndexOf('/'));
        return i >= 0 ? s.substring(i + 1) : s;
    }
}
