package com.palette.unlock;

import android.util.Log;
import java.io.FileOutputStream;

/** 统一日志：logcat + 落盘到相机私有目录，便于真机取证。 */
public final class XLog {
    public static final String TAG = "PaletteUnlock";
    private static final String PATH = "/sdcard/Android/data/com.oplus.camera/files/palette_unlock.log";
    private static FileOutputStream sFos;
    private static int sCount;

    private XLog() {}

    public static void i(String msg) { write(android.util.Log.INFO, msg); }

    public static void w(String msg) { write(android.util.Log.WARN, msg); }

    public static void w(String msg, Throwable t) { write(android.util.Log.WARN, msg + " | " + t); }

    private static void write(int prio, String msg) {
        if (sCount > 400) return;
        sCount++;
        try { android.util.Log.println(prio, TAG, msg); } catch (Throwable ignored) {}
        try {
            if (sFos == null) sFos = new FileOutputStream(PATH, true);
            sFos.write((System.currentTimeMillis() + " " + msg + "\n").getBytes("UTF-8"));
            sFos.flush();
        } catch (Throwable t) {
            try { if (sFos != null) sFos.close(); } catch (Throwable ignored) {}
            sFos = null;
        }
    }
}
