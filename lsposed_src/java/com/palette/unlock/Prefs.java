package com.palette.unlock;

import android.content.SharedPreferences;
import io.github.libxposed.api.XposedModule;

/** 开关存储。默认全开（强开模式）；可用 LSPosed 远程偏好关闭任一钩子。 */
public final class Prefs {
    public static final String FILE = "cfg";
    public static final String PKG = "com.palette.unlock";

    public static final String K_CONFIG = "force_config";     // 强制配置键
    public static final String K_FUNCTION = "force_function"; // 强制能力位
    public static final String K_REDIRECT = "redirect_path";  // 路径重定向兜底
    public static final String K_SEEKBAR = "seekbar_stub";    // 滑块取色兜底
    private static XposedModule sModule;
    private static SharedPreferences sRemote;
    private static boolean sTried;

    private Prefs() {}

    static void bind(XposedModule module) { sModule = module; }

    private static SharedPreferences remote() {
        SharedPreferences sp = sRemote;
        if (sp != null) return sp;
        if (!sTried && sModule != null) {
            sTried = true;
            try { sRemote = sModule.getRemotePreferences(FILE); } catch (Throwable t) { sRemote = null; }
        }
        return sRemote;
    }

    public static boolean get(String key, boolean def) {
        try {
            SharedPreferences sp = remote();
            if (sp != null) return sp.getBoolean(key, def);
        } catch (Throwable ignored) {}
        return def;
    }

    public static boolean forceConfig() { return get(K_CONFIG, true); }

    public static boolean forceFunction() { return get(K_FUNCTION, true); }

    public static boolean redirectPath() { return get(K_REDIRECT, false); }

    public static boolean seekbarStub() { return get(K_SEEKBAR, true); }}
