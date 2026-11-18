package io.github.libxposed.api;

/** 编译期桩。 */
public interface XposedModuleInterface {

    interface PackageLoadedParam {
        String getPackageName();
        ClassLoader getDefaultClassLoader();
        boolean isFirstPackage();
    }

    default void onPackageLoaded(PackageLoadedParam param) {}
}
