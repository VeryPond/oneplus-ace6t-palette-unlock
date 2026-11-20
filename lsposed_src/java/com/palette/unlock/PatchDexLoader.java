package com.palette.unlock;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 加载 X10 调色盘补丁类（PaletteColorTable / PaletteColorRow /
 * PaletteSeekbarColorTable / PaletteSeekbarColorRow 等）。
 *
 * 6T 的相机 APK 里只有对这些类的"引用"，没有 class_def；缺了它们，
 * PaletteColors.get() / PaletteSeekbarColors.get() 必然抛
 * ClassNotFoundException，色盘只能退化成固定的 6 个假颜色。
 *
 * 这里把补丁 dex 作为模块 assets 打包进来，运行时用 InMemoryDexClassLoader
 * 加载，并把加载出来的 Class 暴露给 hook 使用。
 */
final class PatchDexLoader {

    /** assets 下补丁 dex 的文件名。 */
    private static final String[] DEX_FILES = {
        "PaletteColorRow.dex",
        "PaletteColorTable.dex",
        "PaletteSeekbarColorRow.dex",
        "PaletteSeekbarColorTable.dex",
        "CombineLutJNI.dex",
        "CombineLutProcessUnitSDK.dex",
    };

    /** 类名 -> Class 缓存。 */
    private static final Map<String, Class<?>> sClasses = new HashMap<String, Class<?>>();
    private static ClassLoader sLoader;
    private static boolean sTried;

    private PatchDexLoader() {}

    /** 触发一次加载（幂等）。 */
    static synchronized void ensureLoaded(ClassLoader parent, XposedModule module) {
        if (sTried) return;
        sTried = true;
        try {
            java.util.List<ByteBuffer> bufList = new java.util.ArrayList<ByteBuffer>();
            int loaded = 0;
            for (String name : DEX_FILES) {
                byte[] b = readAsset(module, name);
                if (b == null || b.length == 0) {
                    XLog.w("补丁 dex 缺失: " + name);
                    continue;
                }
                // 每个 dex 独立成一个 ByteBuffer（不能拼接，dex 头会错位）
                bufList.add(ByteBuffer.wrap(b));
                loaded++;
            }
            if (loaded == 0) {
                XLog.w("没有可用的补丁 dex");
                return;
            }
            ByteBuffer[] bufs = bufList.toArray(new ByteBuffer[0]);
            Class<?> clc = Class.forName("dalvik.system.InMemoryDexClassLoader");
            sLoader = (ClassLoader) clc.getConstructor(ByteBuffer[].class, ClassLoader.class)
                    .newInstance(bufs, parent);
            XLog.i("补丁 dex 加载完成: " + loaded + " 个, loader=" + sLoader);

            // ★ 关键：把补丁 dex 的 Element 注入相机 ClassLoader 的 DexPathList，
            //   让相机自己的 cl.loadClass() 也能找到这些类
            try {
                Class<?> clCls = sLoader.getClass();
                java.lang.reflect.Field plf = null;
                for (Class<?> c = clCls; c != null; c = c.getSuperclass()) {
                    try { plf = c.getDeclaredField("pathList"); break; } catch (Throwable ignored) {}
                }
                if (plf != null) {
                    plf.setAccessible(true);
                    Object patchPathList = plf.get(sLoader);
                    java.lang.reflect.Field elf = patchPathList.getClass().getDeclaredField("dexElements");
                    elf.setAccessible(true);
                    Object[] patchElems = (Object[]) elf.get(patchPathList);

                    // 父 loader 的 pathList
                    Object parentPathList = plf.get(parent);
                    Object[] parentElems = (Object[]) elf.get(parentPathList);
                    XLog.i("注入前: 相机 dexElements=" + parentElems.length + ", 补丁=" + patchElems.length);

                    // 合并：补丁 + 相机
                    Object[] merged = (Object[]) java.lang.reflect.Array.newInstance(
                            parentElems.getClass().getComponentType(),
                            patchElems.length + parentElems.length);
                    System.arraycopy(patchElems, 0, merged, 0, patchElems.length);
                    System.arraycopy(parentElems, 0, merged, patchElems.length, parentElems.length);
                    elf.set(parentPathList, merged);
                    XLog.i("注入后: 相机 dexElements=" + ((Object[]) elf.get(parentPathList)).length);
                }
            } catch (Throwable t2) {
                XLog.w("注入 dexElements 失败", t2);
            }
        } catch (Throwable t) {
            XLog.w("补丁 dex 加载失败", t);
        }
    }

    /** 模块 APK 的路径（反射从模块 ClassLoader 的 DexPathList 取）。 */
    private static String[] moduleApkPaths() {
        try {
            Object cl = PatchDexLoader.class.getClassLoader();
            java.lang.reflect.Field f = null;
            Class<?> c = cl.getClass();
            while (c != null) {
                try { f = c.getDeclaredField("pathList"); break; } catch (Throwable ignored) { c = c.getSuperclass(); }
            }
            if (f == null) return new String[0];
            f.setAccessible(true);
            Object pathList = f.get(cl);
            java.lang.reflect.Field df = pathList.getClass().getDeclaredField("dexElements");
            df.setAccessible(true);
            Object[] elems = (Object[]) df.get(pathList);
            String[] out = new String[elems.length];
            for (int i = 0; i < elems.length; i++) {
                java.lang.reflect.Field ff = elems[i].getClass().getDeclaredField("path");
                ff.setAccessible(true);
                out[i] = String.valueOf(ff.get(elems[i]));
            }
            return out;
        } catch (Throwable t) {
            XLog.w("取模块 APK 路径失败", t);
            return new String[0];
        }
    }

    /** 从模块 apk 的 assets 里读一个 dex。 */
    private static byte[] readAsset(XposedModule module, String name) {
        // 方式1：模块 ClassLoader 的 getResourceAsStream
        InputStream in = null;
        try {
            in = PatchDexLoader.class.getClassLoader().getResourceAsStream("assets/patch/" + name);
            if (in != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                XLog.i("asset 读取成功(classloader): " + name + " " + out.size() + " 字节");
                return out.toByteArray();
            }
        } catch (Throwable ignored) {}

        // 方式2：从模块 APK 路径用 ZipFile 读
        for (String path : moduleApkPaths()) {
            try {
                if (path == null || !path.endsWith(".apk")) continue;
                java.util.zip.ZipFile zf = new java.util.zip.ZipFile(path);
                java.util.zip.ZipEntry e = zf.getEntry("assets/patch/" + name);
                if (e == null) { zf.close(); continue; }
                InputStream in2 = zf.getInputStream(e);
                ByteArrayOutputStream out2 = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in2.read(buf)) > 0) out2.write(buf, 0, n);
                in2.close();
                zf.close();
                XLog.i("asset 读取成功(zip): " + name + " " + out2.size() + " 字节 from " + path);
                return out2.toByteArray();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 取一个补丁类；没加载到返回 null。 */
    static Class<?> get(String clsName) {
        if (sClasses.containsKey(clsName)) return sClasses.get(clsName);
        Class<?> c = null;
        try {
            if (sLoader != null) c = sLoader.loadClass(clsName);
        } catch (Throwable ignored) {}
        if (c != null) sClasses.put(clsName, c);
        return c;
    }

    /** 是否可用（实际尝试加载一次）。 */
    static boolean available() {
        if (sLoader == null) return false;
        Class<?> c = get("com.oplus.ocs.camera.ipusdk.processunit.filter.palette.PaletteColorTable");
        if (c == null) {
            XLog.w("补丁 loader 存在但加载 PaletteColorTable 失败");
        }
        return c != null;
    }
}
