package com.palette.unlock;

import android.hardware.camera2.CaptureRequest;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 第三层：com.oplus.ocs.camera.CameraParameter
 *
 * 取证：classes23.dex 的 <clinit> 里出现 "com.oplus.palette.capture.enable"，
 * 说明它被固化成某个 CaptureRequest.Key 的静态字段。
 * 抓图时若该 Key 未启用，调色参数不会随帧下发。
 */
final class CameraParameterHook {
    private static final String CLS = "com.oplus.ocs.camera.CameraParameter";

    private CameraParameterHook() {}

    static void install(XposedModule module, ClassLoader cl) {
        try {
            Class<?> cls = cl.loadClass(CLS);
            for (final Field f : cls.getDeclaredFields()) {
                if (!CaptureRequest.Key.class.isAssignableFrom(f.getType())) continue;
                String n = f.getName().toLowerCase();
                if (!n.contains("palette")) continue;
                XLog.i("CameraParameter palette key field: " + f.getName());
            }
        } catch (Throwable t) {
            XLog.w("CameraParameterHook skipped", t);
        }
    }
}
