package com.palette.unlock;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 第一层：com.oplus.camera.configure.CameraConfig
 *
 * 该类的静态取值方法族全都以 (String key, ...) 为首参：
 *   boolean d(String, boolean, boolean)     —— ja.k0#i0 用的就是它
 *   boolean c(String, boolean)
 *   boolean getConfigBooleanValue(String)
 *
 * 内部有 ConcurrentHashMap 缓存（f10935d），所以必须在缓存被写入**之前**生效，
 * 因此在 onPackageLoaded 阶段（相机 Application 构造前）就装钩子。
 */
final class CameraConfigHook {
    private static final String CLS = "com.oplus.camera.configure.CameraConfig";
    private static int sBool;

    private CameraConfigHook() {}

    static void install(XposedModule module, ClassLoader cl) {
        try {
            Class<?> cls = cl.loadClass(CLS);
            for (final Method m : cls.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 1 || p[0] != String.class) continue;
                try {
                    m.setAccessible(true);
                    module.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object a0 = chain.getArg(0);
                            if (a0 instanceof String && Keys.forceTrueConfig((String) a0)) {
                                sBool++;
                                if (sBool <= 40) XLog.i("CameraConfig." + m.getName() + "(" + a0 + ") -> true");
                                return Boolean.TRUE;
                            }
                            return chain.proceed();
                        }
                    });
                    sBool++;
                } catch (Throwable t) {
                    XLog.w("hook " + m.getName() + " fail", t);
                }
            }
            XLog.i("CameraConfigHook installed, methods=" + sBool);
        } catch (Throwable t) {
            XLog.w("CameraConfigHook skipped", t);
        }
    }
}
