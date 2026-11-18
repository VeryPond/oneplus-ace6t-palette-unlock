package com.palette.unlock;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 强制 Filter SDK 的 isInited() 返回 true。
 *
 * 背景：onFilterIntensityChanged 内部会先判 AbstractFilterSdk.isInited()，
 * 为 false 时直接 return，导致底部滤镜强度滑条拖了没反应。
 * 本机型是被强开出来的调色盘，SDK 可能从未真正 init 过。
 *
 * 覆盖三个实现：
 *   - com.oplus.camera.filter.AbstractFilterSdk 的子类（AncFilterSdk / IpuFilterSdk）
 *   - com.oplus.camera.filter.（已废弃的旧类名）
 */
public final class FilterSdkHook {

    /**
     * 真实类名（classes.dex / classes12.dex 实测）：
     *   com.oplus.camera.filter.anc.AncFilterSdk    <- 带 .anc. 子包
     *   com.oplus.camera.filter.ipu.IpuFilterSdk    <- 带 .ipu. 子包
     *   com.oplus.camera.filter.AbstractFilterSdk   <- 抽象基类
     * 早期版本误写成 com.oplus.camera.filter.AncFilterSdk（少一层子包）与
     * （已废弃的旧类名）（此类根本不存在），导致整套 hook 全部跳过。
     */
    private static final String[] CLASSES = {
            // 子包实测（classes7/classes12）
            "com.oplus.camera.filter.anc.AncFilterSdk",
            "com.oplus.camera.filter.ipu.IpuFilterSdk",
            "com.oplus.camera.filter.ipuadapter.FilterIpuAdapter",
            "com.oplus.camera.filter.AbstractFilterSdk",
    };

    private FilterSdkHook() {}

    public static void install(XposedModule mod, ClassLoader cl) {
        int n = 0;
        for (String cn : CLASSES) {
            n += hookClass(mod, cl, cn);
        }
        XLog.i("FilterSdkHook done, installed=" + n);
    }

    private static int hookClass(XposedModule mod, ClassLoader cl, final String cn) {
        int n = 0;
        try {
            Class<?> c = Class.forName(cn, false, cl);
            for (final Method m : c.getDeclaredMethods()) {
                if (!"isInited".equals(m.getName())) continue;
                if (m.getReturnType() != boolean.class) continue;
                if (m.getParameterTypes().length != 0) continue;
                try {
                    m.setAccessible(true);
                    mod.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object real;
                            try {
                                real = chain.proceed();
                            } catch (Throwable t) {
                                real = null;
                            }
                            boolean raw = real instanceof Boolean && (Boolean) real;
                            if (!raw) {
                                XLog.i(cn.substring(cn.lastIndexOf('.') + 1)
                                        + ".isInited() 原始=" + raw + " -> 强制 true");
                            }
                            return Boolean.TRUE;
                        }
                    });
                    n++;
                    XLog.i("hooked " + cn + ".isInited() -> true");
                } catch (Throwable t) {
                    XLog.w("hook " + cn + ".isInited fail", t);
                }
            }
        } catch (Throwable t) {
            // 类不存在属正常（不同机型实现类不同）
        }
        return n;
    }
}
