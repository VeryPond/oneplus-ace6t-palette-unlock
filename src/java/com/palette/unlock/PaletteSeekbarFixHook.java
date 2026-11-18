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
                    module.hook(m).intercept(new GetHook(recipeCtor));
                    n++;
                    XLog.i("hooked PaletteSeekbarColors.get(String) -> stub recipe");
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
                    // 真机取证：PaletteColorRecipe 的可用构造是
                    //   (int[], float[], int[], float[])  —— 主色 + 位置，次色 + 位置
                    // 没有 (int[], float[]) 这个两参构造，必须用四参。
                    final Constructor ctor = recipe2.getConstructor(
                            int[].class, float[].class, int[].class, float[].class);
                    m.setAccessible(true);
                    module.hook(m).intercept(new ColorGetHook(ctor));
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

    static final class ColorGetHook implements XposedInterface.Hooker {
        private final Constructor ctor;
        ColorGetHook(Constructor c) { this.ctor = c; }
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object arg = chain.getArg(0);
            if (sLogged < 10) {
                sLogged++;
                XLog.i("stub PaletteColors.get(" + arg + ") -> recipe(6colors)");
            }
            return ctor.newInstance(COLOR_DEFAULTS.clone(), COLOR_POSITIONS.clone(),
                    COLOR_DEFAULTS.clone(), COLOR_POSITIONS.clone());
        }
    }

    static final class GetHook implements XposedInterface.Hooker {
        private final java.lang.reflect.Constructor ctor;
        GetHook(java.lang.reflect.Constructor c) { this.ctor = c; }
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object arg = chain.getArg(0);
            if (sLogged < 10) {
                sLogged++;
                XLog.i("stub PaletteSeekbarColors.get(" + arg + ") -> recipe(3colors)");
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
