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
