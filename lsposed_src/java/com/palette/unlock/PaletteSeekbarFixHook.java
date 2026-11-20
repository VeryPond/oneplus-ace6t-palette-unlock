package com.palette.unlock;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 第六层：调色盘滑块取色的"缺失类"兜底。
 *
 * ===== 真机崩溃取证（一加 6T / PLR110 / ColorOS 17）=====
 *
 * InflateException: layout/palette_filter_root line #38
 *   Error inflating class CameraPaletteFilterCOUISeekbar
 * Caused by: NoClassDefFoundError:
 *   com.oplus.ocs.camera.ipusdk.processunit.filter.palette.PaletteSeekbarColorTable
 *
 * 字节码核查结论：
 *   - classes13.dex 里 PaletteSeekbarColors 引用了 PaletteSeekbarColorTable
 *   - 但该 Table 类在整个 APK 的 class_defs 里【没有被定义】
 *     （type_ids 里有，class_defined=False）
 *   → 说明它是 OCS ipusdk 的可选组件，本机型未随包下发。
 *
 * 被引用的方法签名（classes13.dex）：
 *   static PaletteSeekbarColorRecipe get(String)              <- 崩溃点
 *   static boolean hasSeekbarColors(String)
 *
 * 解法（与 X8CamUnlock 的 PaletteSeekbarFix 同思路）：
 *   直接替换这两个静态方法，不调用原方法，返回自造的空 Recipe 桩，
 *   绕过对缺失 Table 类的依赖。滑块颜色退化为内置默认色，但不再崩溃。
 *
 * PaletteSeekbarColorRecipe 的构造函数只有一个：(int[]) -> V
 */
final class PaletteSeekbarFixHook {
    private static final String CLS = "com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteSeekbarColors";
    private static final String RECIPE = "com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteSeekbarColorRecipe";
    private static final String CLS2 = "com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteColors";
    private static final String RECIPE2 = "com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteColorRecipe";

    /** 默认三档色（与 X8CamUnlock 内置的一致，ARGB） */
    private static final int[] DEFAULT_COLORS = { -12947803, -8355712, -1522550 };

    private static int sLogged;

    private PaletteSeekbarFixHook() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (!Prefs.seekbarStub()) {
            XLog.i("PaletteSeekbarFix disabled (seekbar_stub=false)");
            return;
        }
        // 先尝试加载 X10 补丁类（真实色表）
        PatchDexLoader.ensureLoaded(cl, module);
        final boolean patchOk = PatchDexLoader.available();
        XLog.i("补丁色表可用: " + patchOk);
        try {
            final Class<?> recipeCls = cl.loadClass(RECIPE);
            Class<?> cls = cl.loadClass(CLS);
            int n = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || p[0] != String.class) continue;
                String name = m.getName();
                if ("get".equals(name) && recipeCls.isAssignableFrom(m.getReturnType())) {
                    final Constructor recipeCtor = recipeCls.getConstructor(int[].class);
                    m.setAccessible(true);
                    module.hook(m).intercept(new GetHook(recipeCtor, patchOk));
                    n++;
                    XLog.i("hooked PaletteSeekbarColors.get(String) patch=" + patchOk);
                } else if ("hasSeekbarColors".equals(name) && m.getReturnType() == boolean.class) {
                    m.setAccessible(true);
                    module.hook(m).intercept(new HasHook());
                    n++;
                    XLog.i("hooked PaletteSeekbarColors.hasSeekbarColors(String) -> true");
                }
            }
            XLog.i("PaletteSeekbarFixHook installed=" + n);
        } catch (Throwable t) {
            XLog.w("PaletteSeekbarFixHook skipped", t);
        }

        // 第二组：PaletteColors（依赖缺失的 PaletteColorTable）
        try {
            final Class<?> recipe2 = cl.loadClass(RECIPE2);
            Class<?> cls2 = cl.loadClass(CLS2);
            int n2 = 0;
            for (Method m : cls2.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || p[0] != String.class) continue;
                String name = m.getName();
                if ("get".equals(name) && recipe2.isAssignableFrom(m.getReturnType())) {
                    final boolean pOk = patchOk;
                    // 真机取证：PaletteColorRecipe 的可用构造是
                    //   (int[], float[], int[], float[])  —— 主色 + 位置，次色 + 位置
                    // 没有 (int[], float[]) 这个两参构造，必须用四参。
                    // PaletteColors.get 用的是 12 参数构造：
                    //   (int[] sec, float[] secPos, int secDir,
                    //    int[] emp, float[] empPos, int empDir,
                    //    int[] emp1, float[] emp1Pos, int emp1Dir,
                    //    int[] emp2, float[] emp2Pos, int emp2Dir)
                    final Constructor ctor = recipe2.getConstructor(
                            int[].class, float[].class, int.class,
                            int[].class, float[].class, int.class,
                            int[].class, float[].class, int.class,
                            int[].class, float[].class, int.class);
                    m.setAccessible(true);
                    module.hook(m).intercept(new ColorGetHook(ctor, patchOk));
                    n2++;
                    XLog.i("hooked PaletteColors.get(String) -> stub recipe");
                } else if ("hasPalette".equals(name) && m.getReturnType() == boolean.class) {
                    m.setAccessible(true);
                    module.hook(m).intercept(new HasHook());
                    n2++;
                    XLog.i("hooked PaletteColors.hasPalette(String) -> true");
                }
            }
            XLog.i("PaletteColorsFix installed=" + n2);
        } catch (Throwable t) {
            XLog.w("PaletteColorsFix skipped", t);
        }
    }

    /** PaletteColorRecipe 的默认色与位置（6 档） */
    private static final int[] COLOR_DEFAULTS = { -1754827, -291840, -141259, -12345273, -14776091, -7461718 };
    private static final float[] COLOR_POSITIONS = { 0.0f, 0.2f, 0.4f, 0.6f, 0.8f, 1.0f };

    /** PaletteColorTable 的真实类（补丁加载成功时非 null）。 */
    private static Method sRealColorTableGet;
    private static boolean sRealColorTableTried;

    /** 解析出真实的 PaletteColorTable.get(String)。 */
    private static synchronized Method realColorTableGet() {
        if (sRealColorTableTried) return sRealColorTableGet;
        sRealColorTableTried = true;
        try {
            Class<?> pct = PatchDexLoader.get("com.oplus.ocs.camera.ipusdk.processunit.filter.palette.PaletteColorTable");
            if (pct != null) {
                sRealColorTableGet = pct.getMethod("get", String.class);
                XLog.i("真实 PaletteColorTable.get 已就绪");
            }
        } catch (Throwable t) {
            XLog.w("解析真实 PaletteColorTable 失败", t);
        }
        return sRealColorTableGet;
    }

    /** 按颜色数量生成均匀分布的位置。 */
    private static float[] defaultPositions(int n) {
        float[] p = new float[n];
        for (int i = 0; i < n; i++) p[i] = n <= 1 ? 0.5f : (float) i / (n - 1);
        return p;
    }

    /** 调用 PaletteColorRow 的公开方法。 */
    private static Object callMethod(Object row, String name) {
        try {
            Method m = row.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(row);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从 PaletteColorRow 反射读取字段。 */
    private static Object rowField(Object row, String name) {
        try {
            java.lang.reflect.Field f = row.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(row);
        } catch (Throwable t) {
            return null;
        }
    }

    static final class ColorGetHook implements XposedInterface.Hooker {
        private final Constructor ctor;
        private final boolean usePatch;
        ColorGetHook(Constructor c, boolean patch) { this.ctor = c; this.usePatch = patch; }
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object arg = chain.getArg(0);
            String key = arg == null ? "null" : String.valueOf(arg);
            if (usePatch) {
                Method get = realColorTableGet();
                if (get != null) {
                    try {
                        Object row = get.invoke(null, key);
                        if (row != null) {
                            // 12 参数：4 层配色，每层 (colors, positions, direction)
                            int[] sec   = (int[])   callMethod(row, "getSecondary");
                            float[] secP= (float[]) callMethod(row, "getSecondaryPositions");
                            int secD    = intOf(callMethod(row, "getSecondaryDirection"));
                            int[] emp   = (int[])   callMethod(row, "getEmphasis");
                            float[] empP= (float[]) callMethod(row, "getEmphasisPositions");
                            int empD    = intOf(callMethod(row, "getEmphasisDirection"));
                            int[] emp1  = (int[])   callMethod(row, "getEmphasisOne");
                            float[] emp1P=(float[]) callMethod(row, "getEmphasisOnePositions");
                            int emp1D   = intOf(callMethod(row, "getEmphasisOneDirection"));
                            int[] emp2  = (int[])   callMethod(row, "getEmphasisTwo");
                            float[] emp2P=(float[]) callMethod(row, "getEmphasisTwoPositions");
                            int emp2D   = intOf(callMethod(row, "getEmphasisTwoDirection"));
                            if ((sec == null || sec.length == 0) && (emp == null || emp.length == 0)) {
                                if (sLogged < 35) { sLogged++; XLog.i("真实色表(" + key + ") 无颜色"); }
                            } else {
                                if (sLogged < 30) {
                                    sLogged++;
                                    XLog.i("真实 PaletteColors.get(" + key + ")"
                                        + " sec=" + arrStr(sec) + "/" + arrStr(secP) + "/d" + secD
                                        + " emp=" + arrStr(emp) + "/" + arrStr(empP) + "/d" + empD
                                        + " e1=" + arrStr(emp1) + "/" + arrStr(emp1P) + "/d" + emp1D
                                        + " e2=" + arrStr(emp2) + "/" + arrStr(emp2P) + "/d" + emp2D);
                                }
                                // positions 缺失时按颜色数补默认值
                                if (sec != null && sec.length > 0 && (secP == null || secP.length != sec.length))
                                    secP = defaultPositions(sec.length);
                                if (emp != null && emp.length > 0 && (empP == null || empP.length != emp.length))
                                    empP = defaultPositions(emp.length);
                                if (emp1 != null && emp1.length > 0 && (emp1P == null || emp1P.length != emp1.length))
                                    emp1P = defaultPositions(emp1.length);
                                if (emp2 != null && emp2.length > 0 && (emp2P == null || emp2P.length != emp2.length))
                                    emp2P = defaultPositions(emp2.length);
                                return ctor.newInstance(
                                    sec == null ? new int[0] : sec,
                                    secP == null ? new float[0] : secP, secD,
                                    emp == null ? new int[0] : emp,
                                    empP == null ? new float[0] : empP, empD,
                                    emp1 == null ? new int[0] : emp1,
                                    emp1P == null ? new float[0] : emp1P, emp1D,
                                    emp2 == null ? new int[0] : emp2,
                                    emp2P == null ? new float[0] : emp2P, emp2D);
                            }
                        }
                    } catch (Throwable t) {
                        if (sLogged < 40) { sLogged++; XLog.w("真实色表失败(" + key + ")", t); }
                    }
                }
            }
            if (sLogged < 50) {
                sLogged++;
                XLog.i("stub PaletteColors.get(" + key + ") -> recipe(6colors)");
            }
            return ctor.newInstance(
                COLOR_DEFAULTS.clone(), COLOR_POSITIONS.clone(), 0,
                COLOR_DEFAULTS.clone(), COLOR_POSITIONS.clone(), 0,
                new int[0], new float[0], 0,
                new int[0], new float[0], 0);
        }
    }

    /** int 取值。 */
    private static int intOf(Object o) {
        return o instanceof Integer ? ((Integer) o).intValue() : 0;
    }

    /** 数组转字符串（截断）。 */
    private static String arrStr(Object o) {
        if (o == null) return "null";
        if (o instanceof int[]) return java.util.Arrays.toString((int[]) o);
        if (o instanceof float[]) return java.util.Arrays.toString((float[]) o);
        return String.valueOf(o);
    }

    /** PaletteSeekbarColorTable 的真实类。 */
    private static Method sRealSeekbarTableGet;
    private static boolean sRealSeekbarTried;

    private static synchronized Method realSeekbarTableGet() {
        if (sRealSeekbarTried) return sRealSeekbarTableGet;
        sRealSeekbarTried = true;
        try {
            Class<?> pst = PatchDexLoader.get("com.oplus.ocs.camera.ipusdk.processunit.filter.palette.PaletteSeekbarColorTable");
            if (pst != null) {
                sRealSeekbarTableGet = pst.getMethod("get", String.class);
                XLog.i("真实 PaletteSeekbarColorTable.get 已就绪");
            }
        } catch (Throwable t) {
            XLog.w("解析真实 PaletteSeekbarColorTable 失败", t);
        }
        return sRealSeekbarTableGet;
    }

    static final class GetHook implements XposedInterface.Hooker {
        private final java.lang.reflect.Constructor ctor;
        private final boolean usePatch;
        GetHook(java.lang.reflect.Constructor c, boolean patch) { this.ctor = c; this.usePatch = patch; }
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object arg = chain.getArg(0);
            String key = arg == null ? "null" : String.valueOf(arg);
            if (usePatch) {
                Method get = realSeekbarTableGet();
                if (get != null) {
                    try {
                        Object row = get.invoke(null, key);
                        if (row != null) {
                            // 构造 PaletteSeekbarColorRecipe：用同一个 ctor 但传真实 row?
                            // 6T 的 Recipe ctor 是 (int[])——从 row 的字段拼出颜色数组
                            Object sec = rowField(row, "mSecondary");
                            if (sec instanceof int[]) {
                                if (sLogged < 30) {
                                    sLogged++;
                                    XLog.i("真实 SeekbarColors.get(" + key + ") -> colors["
                                            + ((int[]) sec).length + "]");
                                }
                                return ctor.newInstance((Object) ((int[]) sec).clone());
                            }
                        }
                    } catch (Throwable t) {
                        if (sLogged < 40) {
                            sLogged++;
                            XLog.w("真实 seekbar 色表失败(" + key + ")，退回 stub", t);
                        }
                    }
                }
            }
            if (sLogged < 50) {
                sLogged++;
                XLog.i("stub PaletteSeekbarColors.get(" + key + ") -> recipe(3colors)");
            }
            return ctor.newInstance((Object) DEFAULT_COLORS.clone());
        }
    }

    static final class HasHook implements XposedInterface.Hooker {
        @Override public Object intercept(XposedInterface.Chain chain) {
            return Boolean.TRUE;
        }
    }
}
