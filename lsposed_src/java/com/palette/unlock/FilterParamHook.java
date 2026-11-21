package com.palette.unlock;

import java.lang.reflect.Method;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 第十层（实验）：FilterProcessUnitSDK.processImage 参数篡改。
 *
 * 动机：日志显示切滤镜后
 *   IPU_FilterProcessUnitSDK: processImage begin params, [... filter_type, 800t.bin, ... filter_open, false ...]
 *   然后预览全黑，但 HAL 仍在出帧、crash=0。
 *
 * 三个对照实验（由 debug.palette.filterparam 控制）：
 *   0 = 不动（基线）
 *   1 = 仅日志观测（看 Map 结构）
 *   2 = filter_type 强制置空
 *   3 = filter_open 强制 true
 *
 * 只观测+最小篡改，不改渲染链其它部分。
 */
final class FilterParamHook {

    private static final String SDK =
            "com.oplus.ocs.camera.ipusdk.processunit.filter.IPU_FilterProcessUnitSDK";
    private static final String SDK_ALT =
            "com.oplus.ocs.camera.ipusdk.processunit.filter.FilterProcessUnitSDK";

    private static int sCalls;
    private static int sMode = -1;

    private FilterParamHook() {}

    private static int mode() {
        if (sMode < 0) {
            String v = prop("debug.palette.filterparam", "0");
            try { sMode = Integer.parseInt(v.trim()); } catch (Throwable t) { sMode = 0; }
        }
        return sMode;
    }

    private static String prop(String k, String d) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method g = sp.getMethod("get", String.class, String.class);
            Object v = g.invoke(null, k, d);
            return v == null ? d : String.valueOf(v);
        } catch (Throwable t) {
            return d;
        }
    }

    private static String describeOne(Object a) {
        if (a == null) return "null";
        if (a instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) a;
            StringBuilder sb = new StringBuilder();
            sb.append("Map{size=").append(m.size());
            int k = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (k++ >= 8) { sb.append(" ..."); break; }
                sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
            }
            sb.append('}');
            return sb.toString();
        }
        if (a instanceof Object[]) {
            return "Array[len=" + ((Object[]) a).length + "]";
        }
        String s = String.valueOf(a);
        if (s.length() > 60) s = s.substring(0, 60) + "...";
        return s;
    }

    static void install(XposedModule module, ClassLoader cl) {
        int n = 0;
        Class<?> c = null;
        String used = null;
        for (String cn : new String[]{SDK, SDK_ALT}) {
            try { c = cl.loadClass(cn); used = cn; break; }
            catch (Throwable ignored) {}
        }
        if (c == null) {
            XLog.w("FilterParamHook: 未找到 SDK 类，已跳过");
            return;
        }
        XLog.i("FilterParamHook: SDK class = " + c.getName());
        // 找 setParams / setParam / setOption 之类的写入方法，hook 它做篡改
        for (final Method m : c.getDeclaredMethods()) {
            String nm = m.getName();
            Class<?>[] pts = m.getParameterTypes();
            boolean isSetter = nm.startsWith("set") || nm.contains("Param") || nm.contains("Option")
                    || nm.contains("Config") || nm.contains("Update");
            if (!isSetter) continue;
            StringBuilder pb = new StringBuilder();
            for (Class<?> pt : pts) pb.append(pt.getName()).append(',');
            XLog.i("FilterParamHook: hook " + nm + "(" + pb + ") ret=" + m.getReturnType().getName());
            try {
                m.setAccessible(true);
                module.hook(m).intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        int mode = mode();
                        sCalls++;
                        StringBuilder sb = new StringBuilder();
                        Object[] captured = new Object[8];
                        int maxIdx = -1;
                        for (int i = 0; i < 8; i++) {
                            Object a = null;
                            try { a = chain.getArg(i); } catch (Throwable t) { break; }
                            captured[i] = a;
                            maxIdx = i;
                            sb.append('[').append(i).append("]=").append(describeOne(a)).append(' ');
                        }
                        if (sCalls <= 30 || sCalls % 200 == 0) {
                            XLog.i("FilterParamHook#" + sCalls + " " + nm + " mode=" + mode
                                    + " argc~" + (maxIdx + 1) + " " + sb);
                        }
                        if (mode >= 2) {
                            for (int i = 0; i <= maxIdx; i++) {
                                Object a = captured[i];
                                if (!(a instanceof Map)) continue;
                                Map<?, ?> m2 = (Map<?, ?>) a;
                                if (!m2.containsKey("filter_type") && !m2.containsKey("filter_open")) continue;
                                String before = String.valueOf(m2.get("filter_type")) + "/"
                                        + String.valueOf(m2.get("filter_open"));
                                try {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> raw = (Map<String, Object>) a;
                                    if (mode == 2) {
                                        raw.put("filter_type", "");
                                    } else if (mode == 3) {
                                        raw.put("filter_open", Boolean.TRUE);
                                    }
                                } catch (Throwable ignored) {}
                                if (sCalls <= 30) {
                                    XLog.i("FilterParamHook#" + sCalls + " MUTATED args[" + i + "]"
                                            + " before=" + before + " after="
                                            + String.valueOf(m2.get("filter_type")) + "/"
                                            + String.valueOf(m2.get("filter_open")));
                                }
                            }
                        }
                        return chain.proceed();
                    }
                });
                n++;
            } catch (Throwable t) {
                XLog.w("FilterParamHook hook " + nm + " fail", t);
            }
        }
        XLog.i("FilterParamHook installed=" + n + " on " + used
                + " mode=" + mode());
    }
}
