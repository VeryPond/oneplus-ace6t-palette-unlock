package com.palette.unlock;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 第七层：注入「大师模式 / 大师视频 / 视频 LOG」到相机模式栏。
 *
 * ===== 取证 =====
 * 模式栏列表来自 com.oplus.ocs.camera.config.FeatureConfigureAdapter#getFeatureInfoList(int)：
 *   public Set<String> getFeatureInfoList(@EntryType int i10) {
 *       HashSet set = new HashSet();
 *       this.mFeatureConfigure.getFeatureInfoList().stream()
 *           .filter(entryType 匹配)
 *           .forEach(fi -> set.add(fi.getFeatureName()));
 *       return set;
 *   }
 * 即：列表完全来自 OPPO 的加密 protobuf 配置
 * （/odm/etc/camera/config/camera_unit_feature_config.protobuf，实测加密无法改）。
 *
 * 所以唯一可行路径是 hook 这个方法的返回值，注入需要的 feature 名。
 *
 * 这与之前一加 6T「辅助构图按钮」成功的方法完全一致。
 *
 * ===== 要注入的项 =====
 *   com.oplus.camera.feature.master          -> 大师拍照
 *   com.oplus.camera.feature.master_video    -> 大师视频
 *   com.oplus.camera.feature.logvideo        -> 视频 LOG
 *
 * 同时要 hook FeatureConfigureAdapter 里所有 (String)->boolean / (String)->String
 * 的判定，让这些 feature 被认作「已配置」。
 */
final class MasterModeHook {
    private static final String FCA = "com.oplus.ocs.camera.config.FeatureConfigureAdapter";
    private static final String[] INJECT = {
            "com.oplus.camera.feature.master_effect",
            "com.oplus.camera.feature.master",
            "com.oplus.camera.feature.master_video_params",
            "com.oplus.camera.feature.master_video",
            "com.oplus.camera.feature.logvideo",
    };
    private static int sLogged;

    private MasterModeHook() {}

    static void install(XposedModule module, ClassLoader cl) {
        if (!Prefs.masterMode()) {
            XLog.i("MasterModeHook disabled");
            return;
        }
        try {
            Class<?> cls = cl.loadClass(FCA);
            int n = 0;
            for (Method m : cls.getDeclaredMethods()) {
                String name = m.getName();
                if ("getFeatureInfoList".equals(name) && Set.class.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    module.hook(m).intercept(new ListHook());
                    n++;
                    XLog.i("hooked FeatureConfigureAdapter.getFeatureInfoList");
                } else if (name.startsWith("has") && m.getReturnType() == boolean.class) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && p[0] == String.class) {
                        m.setAccessible(true);
                        module.hook(m).intercept(new HasHook());
                        n++;
                        XLog.i("hooked " + name + "(String) -> true for master/log");
                    }
                }
            }
            XLog.i("MasterModeHook installed=" + n);
        } catch (Throwable th) {
            XLog.w("MasterModeHook skipped", th);
        }
    }

    static final class ListHook implements XposedInterface.Hooker {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object r = chain.proceed();
            if (!(r instanceof Set)) return r;
            Set src = (Set) r;
            Set out;
            try {
                out = new LinkedHashSet(src);
            } catch (Throwable th) {
                out = new HashSet(src);
            }
            int added = 0;
            for (String s : INJECT) {
                if (out.add(s)) added++;
            }
            if (sLogged < 6) {
                sLogged++;
                XLog.i("featureInfoList entry=" + chain.getArg(0)
                        + " size=" + src.size() + " -> " + out.size() + " (added " + added + ")");
            }
            return out;
        }
    }

    static final class HasHook implements XposedInterface.Hooker {
        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object a0 = chain.getArg(0);
            if (a0 instanceof String) {
                String s = (String) a0;
                for (String k : INJECT) {
                    if (k.equals(s)) return Boolean.TRUE;
                }
                if (s.contains("master") || s.contains("logvideo")) {
                    return Boolean.TRUE;
                }
            }
            return chain.proceed();
        }
    }
}
