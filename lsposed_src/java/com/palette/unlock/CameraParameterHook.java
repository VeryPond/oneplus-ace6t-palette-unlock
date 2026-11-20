package com.palette.unlock;

import android.hardware.camera2.CaptureRequest;

import java.lang.reflect.Field;

import io.github.libxposed.api.XposedModule;

/**
 * 第三层：com.oplus.ocs.camera.CameraParameter
 *
 * 取证：classes23.dex 的 <clinit> 里出现 "com.oplus.palette.capture.enable"，
 * 说明它被固化成某个 CaptureRequest.Key 的静态字段。
 * 抓图时若该 Key 未启用，调色参数不会随帧下发。
 *
 * 本层的做法：
 *   1) 扫出所有名字含 palette 的 CaptureRequest.Key 静态字段，打日志
 *   2) 若字段值为 null，尝试用反射构造一个 Boolean Key 填进去（部分 ROM 会把
 *      不支持的 key 置 null，导致 set() 时抛 IllegalArgumentException）
 *
 * 注意：CaptureRequest.Key 的构造器是 @hide 的 (String, Class)，用反射拿。
 */
final class CameraParameterHook {
    private static final String CLS = "com.oplus.ocs.camera.CameraParameter";
    private static final int MAX_LOG = 24;

    private CameraParameterHook() {}

    static void install(XposedModule module, ClassLoader cl) {
        try {
            Class<?> cls = cl.loadClass(CLS);
            int found = 0;
            int filled = 0;
            for (Field f : cls.getDeclaredFields()) {
                if (!CaptureRequest.Key.class.isAssignableFrom(f.getType())) continue;
                String n = f.getName().toLowerCase();
                if (!n.contains("palette")) continue;
                found++;
                if (found <= MAX_LOG) {
                    Object v = null;
                    try { f.setAccessible(true); v = f.get(null); } catch (Throwable ignored) {}
                    XLog.i("CameraParameter palette key: " + f.getName() + " = " + v);
                }
                // 若为 null，尝试补一个
                try {
                    f.setAccessible(true);
                    if (f.get(null) != null) continue;
                    if (java.lang.reflect.Modifier.isFinal(f.getModifiers())) {
                        // final 字段在 Android 上可用反射改（去掉 final 修饰）
                        try {
                            Field mods = Field.class.getDeclaredField("accessFlags");
                            mods.setAccessible(true);
                            mods.setInt(f, f.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
                        } catch (Throwable ignored) {}
                    }
                    Object key = makeBooleanKey(f.getName());
                    if (key != null) {
                        f.set(null, key);
                        filled++;
                        XLog.i("CameraParameter 补建 key: " + f.getName());
                    }
                } catch (Throwable ignored) {}
            }
            XLog.i("CameraParameterHook done, found=" + found + ", filled=" + filled);
        } catch (Throwable t) {
            XLog.w("CameraParameterHook skipped", t);
        }
    }

    private static Object makeBooleanKey(String name) {
        try {
            Class<?> kc = CaptureRequest.Key.class;
            java.lang.reflect.Constructor<?> c = kc.getDeclaredConstructor(String.class, Class.class);
            c.setAccessible(true);
            return c.newInstance(name, Boolean.class);
        } catch (Throwable t) {
            return null;
        }
    }
}
