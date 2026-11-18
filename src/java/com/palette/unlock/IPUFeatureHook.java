package com.palette.unlock;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 第八层：IPUClient 相关强制。
 *
 * ===== 字节码取证（classes23.dex，签名已核对）=====
 *   IPUClient 只有三个方法：
 *     <init>() 
 *     IProcessUnit createFeature(Context, Class)
 *     boolean isFeatureSupport(Context, IPUClient$IPUFeature)
 *
 *   注意：早期版本误以为 isFeatureSupport 的参数是 Class，实际是
 *   (Context, IPUClient$IPUFeature) 两个参数；而 createFeature 才是 (Context, Class)。
 *
 *   IPUClient$IPUFeature 枚举【确实存在】，共 9 个静态字段，其中包含 COLOR_PALETTE。
 *   所以问题不是"枚举缺失"，而是该枚举在真机上的实际取值/判定结果。
 *
 * ===== 本 hook 做两件事 =====
 *   1) hook createFeature(Context, Class)：若 Class 是 PaletteProcessUnit 且原方法
 *      抛异常或返回 null，记录日志（便于定位）
 *   2) hook isFeatureSupport(Context, IPUFeature)：若枚举名为 COLOR_PALETTE 则强制 true
 */
final class IPUFeatureHook {

    private static final String IPU_CLIENT = "com.oplus.ocs.camera.ipuapi.IPUClient";
    private static final String IPU_FEATURE = "com.oplus.ocs.camera.ipuapi.IPUClient$IPUFeature";
    private static int sHits;

    private IPUFeatureHook() {}

    static void install(XposedModule module, ClassLoader cl) {
        int n = 0;
        try {
            Class<?> c = cl.loadClass(IPU_CLIENT);
            Class<?> featEnum = null;
            try { featEnum = cl.loadClass(IPU_FEATURE); } catch (Throwable ignored) {}
            final Class<?> feat = featEnum;

            for (final Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                Class<?>[] p = m.getParameterTypes();

                // --- createFeature(Context, Class) ---
                if ("createFeature".equals(name) && p.length == 2
                        && p[1] == Class.class) {
                    try {
                        m.setAccessible(true);
                        module.hook(m).intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object a1 = chain.getArg(1);
                                String cn = a1 instanceof Class ? ((Class<?>) a1).getName() : "";
                                boolean isPalette = cn.contains("PaletteProcessUnit");
                                if (isPalette) {
                                    XLog.i("createFeature(" + cn.substring(cn.lastIndexOf('.') + 1) + ") 被调用");
                                }
                                try {
                                    Object r = chain.proceed();
                                    if (isPalette) {
                                        XLog.i("createFeature Palette -> " + (r == null ? "null" : "ok"));
                                    }
                                    return r;
                                } catch (Throwable t) {
                                    if (isPalette) {
                                        XLog.w("createFeature Palette 抛异常", t);
                                    }
                                    throw t;
                                }
                            }
                        });
                        n++;
                        XLog.i("hooked IPUClient.createFeature(Context, Class)");
                    } catch (Throwable t) {
                        XLog.w("hook createFeature fail", t);
                    }
                    continue;
                }

                // --- isFeatureSupport(Context, IPUFeature) ---
                if ("isFeatureSupport".equals(name) && p.length == 2
                        && feat != null && p[1] == feat) {
                    try {
                        m.setAccessible(true);
                        module.hook(m).intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object a1 = chain.getArg(1);
                                String en = "";
                                try {
                                    en = String.valueOf(a1 == null ? "" : ((Enum<?>) a1).name());
                                } catch (Throwable ignored) {}
                                Object real;
                                try {
                                    real = chain.proceed();
                                } catch (Throwable t) {
                                    real = Boolean.FALSE;
                                }
                                boolean raw = real instanceof Boolean && (Boolean) real;
                                if (en.contains("PALETTE")) {
                                    sHits++;
                                    if (sHits <= 20) {
                                        XLog.i("isFeatureSupport(" + en + ") 原始=" + raw + " -> 强制 true");
                                    }
                                    return Boolean.TRUE;
                                }
                                return raw ? Boolean.TRUE : Boolean.FALSE;
                            }
                        });
                        n++;
                        XLog.i("hooked IPUClient.isFeatureSupport(Context, IPUFeature)");
                    } catch (Throwable t) {
                        XLog.w("hook isFeatureSupport fail", t);
                    }
                }
            }
        } catch (Throwable t) {
            XLog.w("IPUFeatureHook skipped: " + t);
        }
        XLog.i("IPUFeatureHook installed=" + n);
    }
}
