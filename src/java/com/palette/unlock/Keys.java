package com.palette.unlock;

import java.util.HashSet;
import java.util.Set;

/** 需要被强制为 true 的能力位 / 配置键。全部来自对 OplusCamera.apk 的字节码取证。 */
public final class Keys {

    /** CameraConfig 的调色盘总开关。见 ja.k0#i0(String)。 */
    public static final String CFG_PALETTE_SUPPORT = "com.oplus.feature.color.palette.support";

    /** BaseMode.getSupportFunction(String) 的能力位，调色盘 UI 入口依赖它。 */
    public static final String FN_SUPPORT_PALETTE = "key_support_palette";

    /** 抓图时携带调色参数的开关。 */
    public static final String CFG_PALETTE_CAPTURE = "com.oplus.palette.capture.enable";

    /** Lut3d 基础色调总开关，调色盘 init 前置。 */
    public static final String CFG_BASIC_TONE_LUT = "com.oplus.camera.basic.tone.lut.support";

    private static final Set<String> FORCE_TRUE = new HashSet<String>();

    static {
        FORCE_TRUE.add(CFG_PALETTE_SUPPORT);
        FORCE_TRUE.add(CFG_PALETTE_CAPTURE);
        FORCE_TRUE.add(CFG_BASIC_TONE_LUT);
        FORCE_TRUE.add("com.oplus.feature.color.palette.capture.support");
        FORCE_TRUE.add("com.oplus.feature.palette.support");
        FORCE_TRUE.add("pref_palette_filter_menu");
    }

    private Keys() {}

    /** 配置键是否需要强制 true。 */
    public static boolean forceTrueConfig(String key) {
        if (key == null) return false;
        if (FORCE_TRUE.contains(key)) return true;
        return key.contains("palette");
    }

    /** 能力位是否需要强制 true。 */
    public static boolean forceTrueFunction(String fn) {
        if (fn == null) return false;
        return FN_SUPPORT_PALETTE.equals(fn) || fn.contains("palette");
    }
}
