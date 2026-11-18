package io.github.libxposed.api;

import java.lang.reflect.Executable;

/** 编译期桩：运行时由 LSPosed 框架提供真实实现，不打包进 APK。 */
public interface XposedInterface {

    interface Chain {
        Object getArg(int index);
        Object proceed();
        Object proceed(Object[] args);
    }

    interface Hooker {
        Object intercept(Chain chain) throws Throwable;
    }

    interface HookHandle {}

    interface HookBuilder {
        HookHandle intercept(Hooker hooker);
    }

    HookBuilder hook(Executable executable);

    void log(int priority, String tag, String msg);
}
