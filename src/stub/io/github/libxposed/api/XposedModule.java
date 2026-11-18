package io.github.libxposed.api;

import android.content.SharedPreferences;

/** 编译期桩。 */
public abstract class XposedModule implements XposedInterface, XposedModuleInterface {

    public SharedPreferences getRemotePreferences(String name) { return null; }

    @Override public HookBuilder hook(java.lang.reflect.Executable executable) { return null; }

    @Override public void log(int priority, String tag, String msg) {}
}
