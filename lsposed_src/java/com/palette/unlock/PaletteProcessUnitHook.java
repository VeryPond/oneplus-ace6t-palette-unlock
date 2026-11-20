package com.palette.unlock;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 第九层：PaletteProcessUnit 全链路监控（只观测，不篡改）。
 *
 * ===== 为什么需要这一层 =====
 * 前八层解决的是"调色盘 UI 能不能打开、ProcessUnit 能不能创建、LUT 纹理能不能建"。
 * 但调色是否真的生效，取决于 PaletteProcessUnit 内部这条链：
 *
 *     processLut(I,I,F,F,F,Z)[F
 *       ├─ if (!ensureLutInited())  ->  log "[palette] processLut, init failed"  -> 直接 return
 *       └─ CombineLutProcessUnitSDK.process(...)
 *            └─ log "[palette] processLut X, tone: ..., saturation: ..., isHdr: ..., lut: ..."
 *
 * 所以真机上只需要看两条日志：
 *   - 出现 "[palette] processLut, init failed"  -> 卡在 LUT 初始化
 *   - 出现 "[palette] processLut X, tone: ..."  -> palette 已在跑
 *
 * 本层把这两个方法的实际返回值/参数打到我们自己的日志里，
 * 这样即使 IPULog 被关掉也能判断。
 *
 * ===== 字节码取证（classes13.dex，签名已核对）=====
 *   ensureLutInited()                              -> boolean
 *   resolveEffectiveHdr(PaletteProcessParameter)   -> boolean
 *   processLut(int,int,float,float,float,boolean)  -> float[]
 *   setCommonProcessParams(PaletteProcessParameter,int) -> void
 *
 * 注意 ensureLutInited 是 package-private（direct method），必须 setAccessible。
 */
final class PaletteProcessUnitHook {

    private static final String CLS =
            "com.oplus.ocs.camera.ipuapi.process.palette.PaletteProcessUnit";

    private static volatile boolean sInited;
    private static int sProcessLutCalls;
    private static int sEnsureCalls;
    private static final java.util.Map<String, Integer> sWatch = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger sWatchCount = new java.util.concurrent.atomic.AtomicInteger();
    private static void watch(String name, int argc) {
        int c = sWatchCount.incrementAndGet();
        if (c <= 60) {
            XLog.i("PPU 调用 " + name + "/" + argc);
        }
    }
    private static int sInitCalls;
    private static int sInitParamCalls;
    private static int sK0Calls;
    private static int sDispCalls;
    private static int sCtorDump;
    private static int sParamCalls;
    private static int sF0Calls;
    private static int sInK0;
    private static int sF0Trace;
    private static int sL2Calls;
    private static int sCLHCalls;
    static Object sLdmInstance;
    static ClassLoader sLdmClass;
    static float[] sLastLut;
    static int sRenderCount;
    static int sG0Count;
    private static int sFixCount;
    private static int sKFixCount;
    private static int sGCount;
    static Object sGlInstance;
    private static int sL0Count;
    private static int sGlsCount;
    private static int sForceVCount;

    private PaletteProcessUnitHook() {}

    static void install(XposedModule module, ClassLoader cl) {
        try {
            Class<?> cls = cl.loadClass(CLS);
            int n = 0;

            for (final Method m : cls.getDeclaredMethods()) {
                String nm = m.getName();
                Class<?>[] p = m.getParameterTypes();

                // ---- ensureLutInited() -> boolean ----
                if ("ensureLutInited".equals(nm) && p.length == 0
                        && m.getReturnType() == boolean.class) {
                    try {
                        m.setAccessible(true);
                        module.hook(m).intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain)
                                    throws Throwable {
                                // 这里说明 SDK 已加载，补挂可能失败的 PathInject
                                PathInjectHook.retryInstallIfNeeded();
                                Object r = chain.proceed();
                                boolean ok = Boolean.TRUE.equals(r);
                                sEnsureCalls++;
                                if (sEnsureCalls <= 5 || !ok) {
                                    XLog.i("ensureLutInited() = " + ok
                                            + "  (第 " + sEnsureCalls + " 次)"
                                            + (ok ? "" : "   <<< LUT 初始化失败，palette 不会跑"));
                                }
                                if (ok) sInited = true;
                                return r;
                            }
                        });
                        n++;
                        XLog.i("hooked PaletteProcessUnit.ensureLutInited() -> boolean");
                    } catch (Throwable t) { XLog.w("hook ensureLutInited fail", t); }
                    continue;
                }

                // ---- init(...) / initWithoutAPS(...) -> LUT 加载真正入口 ----
                if (("init".equals(nm) || "initWithoutAPS".equals(nm))
                        && p.length > 0) {
                    try {
                        m.setAccessible(true);
                        final String invokeName = nm;
                        module.hook(m).intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain)
                                    throws Throwable {
                                PathInjectHook.retryInstallIfNeeded();
                                Object r = chain.proceed();
                                sInitCalls++;
                                if (sInitCalls <= 6) {
                                    XLog.i("PaletteProcessUnit." + invokeName
                                            + "(...) -> " + r);
                                }
                                return r;
                            }
                        });
                        n++;
                        XLog.i("hooked PaletteProcessUnit." + nm + "(...) [LUT 入口]");
                    } catch (Throwable t) { XLog.w("hook " + nm + " fail", t); }
                    continue;
                }

                // ---- setInitParameter(...) -> 设置 LUT 路径 ----
                if ("setInitParameter".equals(nm) && p.length > 0) {
                    try {
                        m.setAccessible(true);
                        module.hook(m).intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain)
                                    throws Throwable {
                                if (sInitParamCalls < 6) {
                                    StringBuilder sb = new StringBuilder();
                                    for (int i = 0; i < 8; i++) {
                                        try {
                                            Object v = chain.getArg(i);
                                            sb.append(" [").append(i).append("]=").append(v);
                                        } catch (Throwable ignore) { break; }
                                    }
                                    XLog.i("setInitParameter" + sb);
                                }
                                sInitParamCalls++;
                                return chain.proceed();
                            }
                        });
                        n++;
                        XLog.i("hooked PaletteProcessUnit.setInitParameter(...)");
                    } catch (Throwable t) { XLog.w("hook setInitParameter fail", t); }
                    continue;
                }

                // ---- processLut(int,int,float,float,float,boolean) -> float[] ----
                if ("processLut".equals(nm) && p.length == 6
                        && p[0] == int.class && p[1] == int.class
                        && p[2] == float.class && p[3] == float.class
                        && p[4] == float.class && p[5] == boolean.class) {
                    try {
                        m.setAccessible(true);
                        module.hook(m).intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain)
                                    throws Throwable {
                                Object r = chain.proceed();
                                sProcessLutCalls++;
                                int len = (r instanceof float[]) ? ((float[]) r).length : -1;
                                if (sProcessLutCalls <= 5) {
                                    try {
                                        Object a2 = chain.getArg(2);
                                        Object a3 = chain.getArg(3);
                                        Object a4 = chain.getArg(4);
                                        Object a5 = chain.getArg(5);
                                        XLog.i("processLut #" + sProcessLutCalls
                                                + " tone=" + a2
                                                + " sat=" + a3
                                                + " f3=" + a4
                                                + " isHdr=" + a5
                                                + " -> float[" + len + "]"
                                                + (len <= 0 ? "   <<< 返回空，调色未产生数据" : ""));
                                    } catch (Throwable ignored) {
                                        XLog.i("processLut #" + sProcessLutCalls
                                                + " -> float[" + len + "]");
                                    }
                                }
                                return r;
                            }
                        });
                        n++;
                        XLog.i("hooked PaletteProcessUnit.processLut(int,int,float,float,float,boolean)");
                    } catch (Throwable t) { XLog.w("hook processLut fail", t); }
                    continue;
                }

                // ---- resolveEffectiveHdr(PaletteProcessParameter) -> boolean ----
                if ("resolveEffectiveHdr".equals(nm) && p.length == 1
                        && m.getReturnType() == boolean.class) {
                    try {
                        m.setAccessible(true);
                        module.hook(m).intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain)
                                    throws Throwable {
                                Object r = chain.proceed();
                                XLog.i("resolveEffectiveHdr() = " + r
                                        + (Boolean.TRUE.equals(r) ? "  (HDR)" : "  (SDR 降级)"));
                                return r;
                            }
                        });
                        n++;
                        XLog.i("hooked PaletteProcessUnit.resolveEffectiveHdr(...)");
                    } catch (Throwable t) { XLog.w("hook resolveEffectiveHdr fail", t); }
                }
            }

            // ---- 关键：调色盘回调 com.oplus.camera.feature.filter.view.k0.onToneSaturationChanged ----
            try {
                Class<?> k0 = cl.loadClass("com.oplus.camera.feature.filter.view.k0");
                for (final Method km : k0.getDeclaredMethods()) {
                    if (!"onToneSaturationChanged".equals(km.getName())) continue;
                    km.setAccessible(true);
                    final int kargc = km.getParameterTypes().length;
                    module.hook(km).intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (sK0Calls < 30) {
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < kargc; i++) {
                                    try { sb.append(" [").append(i).append("]=").append(chain.getArg(i)); }
                                    catch (Throwable ignore) { break; }
                                }
                                XLog.i("k0.onToneSaturationChanged" + sb);
                            }
                            sInK0++;
                            // 注：libxposed API 102 的 Chain 没有 getThis()，无法读取被 hook 实例的字段
                            // 这里只记录参数，字段分析改用别的途径
                            sK0Calls++;
                            Object r3 = chain.proceed();
                            sInK0--;
                            return r3;
                        }
                    });
                    n++;
                    XLog.i("hooked k0.onToneSaturationChanged(...) [调色盘回调]");
                }
            } catch (Throwable tk) { XLog.w("hook k0 fail: " + tk); }

            // ---- 追踪 dispatchToneSaturationFromPosition（PalettePanelView 内部分发） ----
            try {
                Class<?> ppv = cl.loadClass("com.oplus.ocs.camera.ipuapi.process.filter.palette.PalettePanelView");
                for (final Method dm2 : ppv.getDeclaredMethods()) {
                    if (!dm2.getName().contains("dispatchToneSaturation")) continue;
                    dm2.setAccessible(true);
                    final String dn = dm2.getName();
                    final int dargc = dm2.getParameterTypes().length;
                    final StringBuilder sig = new StringBuilder();
                    final Class<?> declCls = dm2.getDeclaringClass();
                    for (Class<?> pt : dm2.getParameterTypes()) sig.append(pt.getSimpleName()).append(",");
                    XLog.i("发现方法 " + dn + "(" + sig + ") argc=" + dargc);
                    module.hook(dm2).intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (sDispCalls <= 1) {
                                // 第一次 dispatch 时 dump PalettePanelView 的静态字段
                                try {
                                    Class<?> ppvx = declCls;
                                    StringBuilder sb = new StringBuilder("PPV 静态字段(非null):");
                                    int shown2 = 0;
                                    for (java.lang.reflect.Field f : ppvx.getDeclaredFields()) {
                                        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                                        try {
                                            f.setAccessible(true);
                                            Object v = f.get(null);
                                            if (v == null) continue;
                                            String cn4 = v.getClass().getName();
                                            if (cn4.startsWith("java.lang.") || cn4.startsWith("[") 
                                                || cn4.startsWith("android.graphics") || cn4.startsWith("android.view.animation")) continue;
                                            sb.append("\n    ").append(f.getName()).append(" = ").append(cn4);
                                            shown2++;
                                            if (shown2 >= 40) break;
                                        } catch (Throwable ignore) {}
                                    }
                                    XLog.i(sb.toString());
                                } catch (Throwable ignore) {}
                            }
                            sDispCalls++;
                            return chain.proceed();
                        }
                    });
                    n++;
                }
            } catch (Throwable td) { XLog.w("hook dispatch fail: " + td); }

            // ---- 读 PalettePanelView 构造期字段，找 listener 持有者 ----
            try {
                Class<?> ppv2 = cl.loadClass("com.oplus.ocs.camera.ipuapi.process.filter.palette.PalettePanelView");
                for (final java.lang.reflect.Constructor<?> ctor : ppv2.getDeclaredConstructors()) {
                    ctor.setAccessible(true);
                    final StringBuilder csig = new StringBuilder();
                    for (Class<?> pt : ctor.getParameterTypes()) csig.append(pt.getSimpleName()).append(",");
                    XLog.i("PalettePanelView 构造器(" + csig + ")");
                    module.hook(ctor).intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            XLog.i("ctor done: r=" + (r == null ? "null" : r.getClass().getSimpleName()) + " count=" + sCtorDump);
                            if (sCtorDump < 5 && r != null) {
                                sCtorDump++;
                                StringBuilder sb = new StringBuilder("PalettePanelView 实例字段:");
                                int shown = 0;
                                try {
                                  java.lang.reflect.Field[] fs2 = ppv2.getDeclaredFields();
                                  for (java.lang.reflect.Field f : fs2) {
                                    if (shown >= 120) break;
                                    try {
                                        f.setAccessible(true);
                                        Object v = f.get(r);
                                        if (v == null) { continue; }
                                        String cn3 = v.getClass().getName();
                                        // 跳过基本类型包装和数组
                                        if (cn3.startsWith("java.lang.") || cn3.startsWith("[") || cn3.startsWith("android.graphics")) continue;
                                        sb.append("\n    ").append(f.getName()).append(" = ").append(cn3);
                                        shown++;
                                    } catch (Throwable ignore) {}
                                  }
                                } catch (Throwable ee) { XLog.w("dump fields err: " + ee); }
                                XLog.i(sb.toString());
                            }
                            return r;
                        }
                    });
                    n++;
                }
            } catch (Throwable tc2) { XLog.w("hook PalettePanelView ctor fail: " + tc2); }

            // ---- 监控 PaletteProcessParameter / PaletteInitParameter 的构造与写入 ----
            String[] paramCls = new String[] {
                "com.oplus.ocs.camera.ipuapi.process.palette.entity.PaletteProcessParameter",
                "com.oplus.ocs.camera.ipuapi.process.palette.entity.PaletteProcessParameter$Builder",
                "com.oplus.ocs.camera.ipuapi.process.palette.entity.PaletteInitParameter",
                "com.oplus.ocs.camera.ipuapi.process.palette.key.PaletteProcessKey",
                "com.oplus.ocs.camera.ipuapi.process.palette.key.PaletteInitKey"
            };
            for (final String pc : paramCls) {
                try {
                    Class<?> kc = cl.loadClass(pc);
                    String simple = pc.substring(pc.lastIndexOf('.') + 1);
                    int hooked = 0;
                    for (final Method pm : kc.getDeclaredMethods()) {
                        if (pm.getParameterTypes().length == 0) continue;
                        try {
                            pm.setAccessible(true);
                            final String pn2 = pm.getName();
                            module.hook(pm).intercept(new XposedInterface.Hooker() {
                                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    int na = chain.getArg(0) == null ? 0 : 0;
                                    if (sParamCalls < 40) {
                                        StringBuilder sb = new StringBuilder("PARAM " + simple + "." + pn2 + "(");
                                        for (int i = 0; i < 4; i++) {
                                            try { Object a = chain.getArg(i); if (a == null && i > 0) break; sb.append(i == 0 ? "" : ",").append(a); }
                                            catch (Throwable brk) { break; }
                                        }
                                        sb.append(")");
                                        XLog.i(sb.toString());
                                    }
                                    sParamCalls++;
                                    return chain.proceed();
                                }
                            });
                            hooked++;
                        } catch (Throwable ignore) {}
                    }
                    if (hooked > 0) { n++; XLog.i("监控 " + simple + " 的 " + hooked + " 个方法"); }
                } catch (Throwable ignore) {}
            }

            // ---- 监控 setOnToneSaturationChangeListener：谁注册了调色回调 ----
            try {
                Class<?> ppv3 = cl.loadClass("com.oplus.ocs.camera.ipuapi.process.filter.palette.PalettePanelView");
                for (final Method sm2 : ppv3.getDeclaredMethods()) {
                    if (!sm2.getName().contains("setOnToneSaturationChangeListener")) continue;
                    sm2.setAccessible(true);
                    final int sargc = sm2.getParameterTypes().length;
                    module.hook(sm2).intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            try {
                                Object arg0 = sargc > 0 ? chain.getArg(0) : null;
                                String an = arg0 == null ? "null" : arg0.getClass().getName();
                                StringBuilder st2 = new StringBuilder("setOnToneSaturationChangeListener 注册者=" + an);
                                StackTraceElement[] se3 = new Throwable().getStackTrace();
                                for (int i = 0; i < se3.length && i < 8; i++) {
                                    st2.append("\n    ").append(se3[i].getClassName())
                                       .append(".").append(se3[i].getMethodName());
                                }
                                XLog.i(st2.toString());
                            } catch (Throwable ignore) {}
                            return chain.proceed();
                        }
                    });
                    n++;
                    XLog.i("hooked setOnToneSaturationChangeListener [注册入口]");
                }
            } catch (Throwable ts2) { XLog.w("hook setOnTone fail: " + ts2); }

            // ---- 决定性验证：hook k0 构造器，看谁创建了它、构造后字段是什么 ----
            try {
                Class<?> k0cls = cl.loadClass("com.oplus.camera.feature.filter.view.k0");
                for (final java.lang.reflect.Constructor<?> kc2 : k0cls.getDeclaredConstructors()) {
                    kc2.setAccessible(true);
                    module.hook(kc2).intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            StringBuilder st3 = new StringBuilder("k0 构造被调用, 调用者栈:");
                            StackTraceElement[] se4 = new Throwable().getStackTrace();
                            for (int i = 0; i < se4.length && i < 10; i++) {
                                st3.append("\n    ").append(se4[i].getClassName())
                                   .append(".").append(se4[i].getMethodName());
                            }
                            XLog.i(st3.toString());
                            Object inst2 = chain.proceed();
                            // 构造后立刻读字段
                            try {
                                if (inst2 != null) {
                                    StringBuilder fz = new StringBuilder("k0 实例字段:");
                                    for (java.lang.reflect.Field ff : k0cls.getDeclaredFields()) {
                                        try {
                                            ff.setAccessible(true);
                                            Object fv = ff.get(inst2);
                                            String fvn = fv == null ? "null" : fv.getClass().getName();
                                            fz.append("\n    ").append(ff.getName()).append(" = ").append(fvn);
                                        } catch (Throwable ignore) {}
                                    }
                                    XLog.i(fz.toString());
                                }
                            } catch (Throwable ignore) {}
                            return inst2;
                        }
                    });
                    n++;
                    XLog.i("hooked k0 构造器 [决定性验证]");
                }
            } catch (Throwable tk2) { XLog.w("hook k0 ctor fail: " + tk2); }

            // ---- 追踪 f0（真正的 Palette 控制器）里处理 tone/saturation 的方法 ----
            try {
                Class<?> f0cls = cl.loadClass("com.oplus.camera.feature.filter.view.f0");
                int f0n = 0;
                for (final Method fm2 : f0cls.getDeclaredMethods()) {
                    String fn = fm2.getName();
                    // 关注接收 (int,int) 或 (int,int,int) 的方法，以及含 Palette 语义的
                    Class<?>[] fp = fm2.getParameterTypes();
                    boolean isCandidate = (fp.length == 2 && fp[0] == int.class && fp[1] == int.class)
                            || (fp.length == 3 && fp[0] == int.class && fp[1] == int.class && fp[2] == int.class)
                            || (fp.length == 1 && fp[0] == int.class);
                    if (!isCandidate) continue;
                    try {
                        fm2.setAccessible(true);
                        final String fname = fn;
                        final int fargc = fp.length;
                        module.hook(fm2).intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                if (sF0Calls < 30) {
                                    StringBuilder sb = new StringBuilder("f0." + fname + "(");
                                    for (int i = 0; i < fargc; i++) {
                                        try { sb.append(i == 0 ? "" : ",").append(chain.getArg(i)); }
                                        catch (Throwable brk) { break; }
                                    }
                                    sb.append(")");
                                    XLog.i(sb.toString());
                                }
                                sF0Calls++;
                                return chain.proceed();
                            }
                        });
                        f0n++;
                    } catch (Throwable ignore) {}
                }
                if (f0n > 0) { n++; XLog.i("监控 f0 的 " + f0n + " 个 int 参数方法"); }
            } catch (Throwable tf0) { XLog.w("hook f0 fail: " + tf0); }

            // ---- 追踪 f0 所有方法的调用（k0 回调期间） ----
            try {
                Class<?> f0b = cl.loadClass("com.oplus.camera.feature.filter.view.f0");
                int f0all = 0;
                for (final Method fm3 : f0b.getDeclaredMethods()) {
                    try {
                        fm3.setAccessible(true);
                        final String fname3 = fm3.getName();
                        module.hook(fm3).intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                if (sInK0 > 0 && sF0Trace < 40) {
                                    StringBuilder sb = new StringBuilder("  [in k0] f0." + fname3 + "(");
                                    for (int i = 0; i < 3; i++) {
                                        try { sb.append(i == 0 ? "" : ",").append(chain.getArg(i)); }
                                        catch (Throwable brk) { break; }
                                    }
                                    sb.append(")");
                                    XLog.i(sb.toString());
                                    sF0Trace++;
                                }
                                return chain.proceed();
                            }
                        });
                        f0all++;
                    } catch (Throwable ignore) {}
                }
                if (f0all > 0) { n++; XLog.i("追踪 f0 全部 " + f0all + " 个方法"); }
            } catch (Throwable tf2) { XLog.w("hook f0 all fail: " + tf2); }

            // ---- 深度追踪 f0.l2(tone)：打印调用栈 + 进入时的 f0 字段状态 ----
            try {
                Class<?> f0c = cl.loadClass("com.oplus.camera.feature.filter.view.f0");
                for (final Method lm : f0c.getDeclaredMethods()) {
                    if (!"l2".equals(lm.getName())) continue;
                    lm.setAccessible(true);
                    final int largc = lm.getParameterTypes().length;
                    module.hook(lm).intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (sL2Calls < 5) {
                                StringBuilder sb = new StringBuilder("f0.l2(");
                                for (int i = 0; i < largc; i++) {
                                    try { sb.append(i == 0 ? "" : ",").append(chain.getArg(i)); }
                                    catch (Throwable brk) { break; }
                                }
                                sb.append(") 调用栈:");
                                StackTraceElement[] se5 = new Throwable().getStackTrace();
                                for (int i = 0; i < se5.length && i < 10; i++) {
                                    sb.append("\n    ").append(se5[i].getClassName())
                                      .append(".").append(se5[i].getMethodName())
                                      .append(":").append(se5[i].getLineNumber());
                                }
                                XLog.i(sb.toString());
                            }
                            sL2Calls++;
                            return chain.proceed();
                        }
                    });
                    n++;
                    XLog.i("hooked f0.l2 [tone处理入口]");
                }
            } catch (Throwable tl2) { XLog.w("hook l2 fail: " + tl2); }

            // ---- 监控 CombineLutHelper：native 处理入口 ----
            try {
                Class<?> clh = cl.loadClass("com.oplus.ocs.camera.CombineLutHelper");
                int clhn = 0;
                for (final Method cm : clh.getDeclaredMethods()) {
                    try {
                        cm.setAccessible(true);
                        final String cname = cm.getName();
                        final int cargc = cm.getParameterTypes().length;
                        module.hook(cm).intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object ret = chain.proceed();
                                if (sCLHCalls < 20 && "process".equals(cname)) {
                                    StringBuilder sb = new StringBuilder("CLH.process(");
                                    for (int i = 0; i < Math.min(cargc, 4); i++) {
                                        try { sb.append(i==0?"":",").append(chain.getArg(i)); } catch (Throwable b) { break; }
                                    }
                                    sb.append(")");
                                    if (ret instanceof float[]) {
                                        float[] fa = (float[]) ret;
                                        float sum = 0, sumsq = 0;
                                        for (int j = 0; j < fa.length; j++) { sum += fa[j]; sumsq += fa[j]*fa[j]; }
                                        float mean = sum / fa.length;
                                        float sd = (float) Math.sqrt(Math.max(0, sumsq/fa.length - mean*mean));
                                        sb.append(" -> [").append(fa.length).append(" floats]")
                                          .append(" mean=").append(String.format("%.4f", mean))
                                          .append(" sd=").append(String.format("%.4f", sd));
                                    }
                                    XLog.i(sb.toString());
                                    if (sCLHCalls <= 2) {
                                        StringBuilder st = new StringBuilder("process 调用栈:");
                                        StackTraceElement[] se = new Throwable().getStackTrace();
                                        for (int i = 0; i < se.length && i < 10; i++) {
                                            st.append("\n    ").append(se[i].getClassName()).append(".").append(se[i].getMethodName());
                                        }
                                        XLog.i(st.toString());
                                    }
                                }
                                if (ret instanceof float[]) sLastLut = (float[]) ret;
                                sCLHCalls++;
                                return ret;
                            }
                        });
                        clhn++;
                    } catch (Throwable ignore) {}
                }
                if (clhn > 0) { n++; XLog.i("监控 CombineLutHelper 的 " + clhn + " 个方法"); }
            } catch (Throwable tclh) { XLog.w("hook CombineLutHelper fail: " + tclh); }

            // ---- 最终修复：k 字段只在 beginPresetSwitch 时为 true，调色盘调整时不触发 ----
            // 方案：hook Lut3dDataManager.p()，在 p() 执行后强制设 k=true，让渲染器应用 LUT
            try {
                Class<?> ldm2 = cl.loadClass("com.oplus.camera.common.utils.Lut3dDataManager");
                for (final Method pm2 : ldm2.getDeclaredMethods()) {
                    if (!"p".equals(pm2.getName()) || pm2.getParameterTypes().length != 0) continue;
                    pm2.setAccessible(true);
                    module.hook(pm2).intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            // 强制设 k=true，让渲染器应用 LUT
                            if (sLdmInstance != null) {
                                try {
                                    java.lang.reflect.Field kf = ldm2.getDeclaredField("k");
                                    kf.setAccessible(true);
                                    kf.setBoolean(sLdmInstance, true);
                                    java.lang.reflect.Field ifield = ldm2.getDeclaredField("i");
                                    ifield.setAccessible(true);
                                    ifield.setBoolean(sLdmInstance, true);
                                    java.lang.reflect.Field cf = ldm2.getDeclaredField("c");
                                    cf.setAccessible(true);
                                    Object cval = cf.get(sLdmInstance);
                                    // 直接找 t0 实例并强制应用 LUT
                                    XLog.i("进入强制 t0.G 块");
                                    try {
                                        XLog.i("尝试加载 gl.s 类...");
                                        Class<?> gls = cl.loadClass("com.oplus.camera.common.gl.s");
                                        XLog.i("gl.s 类加载成功");
                                        java.lang.reflect.Field of = gls.getDeclaredField("o");
                                        of.setAccessible(true);
                                        // s 是单例？找静态实例
                                        Object glInstance = sGlInstance;
                                        if (glInstance == null) {
                                            XLog.i("gl.s 实例未保存");
                                        } else {
                                            Object t0inst = of.get(glInstance);
                                            if (glInstance != null && cval != null) {
                                                // 调用 gl.s.v(float[], int, boolean) 上传 LUT
                                                Method vMethod = glInstance.getClass().getMethod("v", float[].class, int.class, boolean.class);
                                                vMethod.invoke(glInstance, cval, 64, true);
                                                XLog.i("★★ 强制调用 gl.s.v(LUT,64,true) 成功! len=" + ((float[])cval).length);
                                            } else {
                                                XLog.i("gl=" + (glInstance == null ? "null" : "ok") + " c=" + (cval == null ? "null" : "ok"));
                                            }
                                        }
                                    } catch (Throwable forceErr) { XLog.w("强制 t0.G 失败: " + forceErr); }
                                    if (sKFixCount < 10) {
                                        XLog.i("p() 后强制 k=true, c=" + (cval == null ? "null" : ((float[])cval).length + " floats"));
                                        sKFixCount++;
                                    }
                                } catch (Throwable ignore) {}
                            }
                            return r;
                        }
                    });
                    n++;
                    XLog.i("hooked Lut3dDataManager.p() [k=true 修复]");
                }
            } catch (Throwable tp2) { XLog.w("hook p fail: " + tp2); }

            // ---- hook t0.G(float[]) 检查 LUT 是否被应用到渲染器 ----
            try {
                Class<?> t0cls = cl.loadClass("com.oplus.camera.common.gl.t0");
                for (final Method gm : t0cls.getDeclaredMethods()) {
                    if (!"G".equals(gm.getName()) || gm.getParameterTypes().length != 1) continue;
                    if (gm.getParameterTypes()[0] != float[].class) continue;
                    gm.setAccessible(true);
                    module.hook(gm).intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (sGCount < 5) {
                                float[] arg = (float[]) chain.getArg(0);
                                XLog.i("t0.G([F) 被调用! len=" + (arg == null ? "null" : arg.length));
                                sGCount++;
                            }
                            return chain.proceed();
                        }
                    });
                    n++;
                    XLog.i("hooked t0.G([F)V [LUT应用]");
                }
            } catch (Throwable tg) { XLog.w("hook t0.G fail: " + tg); }

            // ---- hook gl.s 构造器保存渲染器实例 ----
            try {
                Class<?> gls2 = cl.loadClass("com.oplus.camera.common.gl.s");
                for (final java.lang.reflect.Constructor<?> gctor : gls2.getDeclaredConstructors()) {
                    gctor.setAccessible(true);
                    module.hook(gctor).intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object inst = chain.proceed();
                            sGlInstance = inst;
                            XLog.i("gl.s 实例已保存");
                            return inst;
                        }
                    });
                    n++;
                    XLog.i("hooked gl.s 构造器 [保存渲染器实例]");
                }
            } catch (Throwable tg2) { XLog.w("hook gl.s ctor fail: " + tg2); }

            // ---- 监控 gl.s 的 e0/f0/o 字段和 l0 方法 ----
            try {
                Class<?> gls3 = cl.loadClass("com.oplus.camera.common.gl.s");
                // hook l0 方法
                for (final Method lm2 : gls3.getDeclaredMethods()) {
                    if (!"l0".equals(lm2.getName())) continue;
                    lm2.setAccessible(true);
                    final String lsig = lm2.getName();
                    module.hook(lm2).intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (sL0Count < 5) {
                                XLog.i("gl.s.l0 被调用, args=" + chain.getArg(0) + "," + chain.getArg(1));
                                sL0Count++;
                            }
                            return chain.proceed();
                        }
                    });
                    n++;
                }
                // hook 所有含 float[] 参数的 GL 方法
                for (final Method gm2 : gls3.getDeclaredMethods()) {
                    Class<?>[] gp = gm2.getParameterTypes();
                    if (gp.length == 0) continue;
                    boolean hasFloatArray = false;
                    for (Class<?> pc : gp) { if (pc == float[].class) { hasFloatArray = true; break; } }
                    if (!hasFloatArray) continue;
                    gm2.setAccessible(true);
                    final String gname = gm2.getName();
                    module.hook(gm2).intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (sGlsCount < 10) {
                                StringBuilder sb = new StringBuilder("gl.s." + gname + "(");
                                for (int i = 0; i < 3; i++) {
                                    try {
                                        Object a = chain.getArg(i);
                                        if (a instanceof float[]) sb.append("float[").append(((float[])a).length).append("]");
                                        else sb.append(a);
                                    } catch (Throwable b) { break; }
                                    if (i < 2) sb.append(",");
                                }
                                sb.append(")");
                                // 读取 e0/f0/o 字段
                                try {
                                    if (sGlInstance != null) {
                                        java.lang.reflect.Field e0f = gls3.getDeclaredField("e0");
                                        e0f.setAccessible(true);
                                        Object e0v = e0f.get(sGlInstance);
                                        java.lang.reflect.Field f0f = gls3.getDeclaredField("f0");
                                        f0f.setAccessible(true);
                                        Object f0v = f0f.get(sGlInstance);
                                        java.lang.reflect.Field of3 = gls3.getDeclaredField("o");
                                        of3.setAccessible(true);
                                        Object ov = of3.get(sGlInstance);
                                        sb.append(" e0=").append(e0v!=null).append(" f0=").append(f0v!=null).append(" o=").append(ov!=null);
                                    }
                                } catch (Throwable fe2) {}
                                XLog.i(sb.toString());
                                sGlsCount++;
                            }
                            return chain.proceed();
                        }
                    });
                    n++;
                }
                XLog.i("gl.s 监控: l0 + float[] 方法已 hook");
            } catch (Throwable tg3) { XLog.w("hook gl.s methods fail: " + tg3); }

            // ---- hook gl.s 渲染方法，检查 e0/f0 条件 ----
            try {
                Class<?> gls4 = cl.loadClass("com.oplus.camera.common.gl.s");
                // 找含 ExtTexture 参数的方法（渲染方法）
                for (final Method rm : gls4.getDeclaredMethods()) {
                    Class<?>[] rp = rm.getParameterTypes();
                    boolean hasTex = false;
                    for (Class<?> pc : rp) {
                        if (pc.getName().contains("Texture") || pc.getName().contains("SurfaceTexture")) { hasTex = true; break; }
                    }
                    if (!hasTex) continue;
                    rm.setAccessible(true);
                    final String rname = rm.getName();
                    module.hook(rm).intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (sRenderCount < 5) {
                                // 读取 e0, f0, o 字段
                                try {
                                    Object self = null;
                                    // 通过参数找 this（第一个参数是 ExtTexture，不是 this）
                                    // 用 gl.s 的构造器保存的实例
                                    Object gi = sGlInstance;
                                    if (gi != null) {
                                        java.lang.reflect.Field e0f = gls4.getDeclaredField("e0");
                                        e0f.setAccessible(true);
                                        Object e0val = e0f.get(gi);
                                        java.lang.reflect.Field f0f = gls4.getDeclaredField("f0");
                                        f0f.setAccessible(true);
                                        Object f0val = f0f.get(gi);
                                        java.lang.reflect.Field of2 = gls4.getDeclaredField("o");
                                        of2.setAccessible(true);
                                        Object oval = of2.get(gi);
                                        XLog.i("gl.s." + rname + " e0=" + (e0val!=null) + " f0=" + (f0val!=null) + " o=" + (oval!=null));
                                    }
                                } catch (Throwable fe) { XLog.w("读字段失败: " + fe); }
                                sRenderCount++;
                            }
                            return chain.proceed();
                        }
                    });
                    n++;
                }
                XLog.i("gl.s 渲染方法已 hook");
            } catch (Throwable tr) { XLog.w("hook render fail: " + tr); }

            // ---- 修复根因：强制 Ia.o0.g0() 返回 true，让 e0（调色盘 shader）被创建 ----
            try {
                Class<?> o0cls = cl.loadClass("ia.o0");
                for (final Method gm3 : o0cls.getDeclaredMethods()) {
                    String gn = gm3.getName();
                    if (!"g0".equals(gn) && !"V".equals(gn) && !"R".equals(gn) && !"p0".equals(gn)) continue;
                    if (gm3.getParameterTypes().length != 0) continue;
                    if (gm3.getReturnType() != boolean.class) continue;
                    gm3.setAccessible(true);
                    final String gname3 = gn;
                    module.hook(gm3).intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if ("g0".equals(gname3)) {
                                if (sG0Count < 3) {
                                    XLog.i("o0.g0() 原始=" + chain.proceed() + " -> 强制 true");
                                    sG0Count++;
                                }
                                return Boolean.TRUE;
                            }
                            return chain.proceed();
                        }
                    });
                    n++;
                    XLog.i("hooked o0." + gname3 + "() [e0 创建条件]");
                }
            } catch (Throwable tg0) { XLog.w("hook o0 fail: " + tg0); }

            XLog.i("PaletteProcessUnitHook installed=" + n + "/3");
        } catch (Throwable t) {
            XLog.w("PaletteProcessUnitHook skipped: " + t);
        }
    }

    /** 供其它层查询：palette 的 LUT 是否初始化成功过。 */
    static boolean lutInited() { return sInited; }

    /** 供其它层查询：processLut 被调用过几次。 */
    static int processLutCalls() { return sProcessLutCalls; }
}
