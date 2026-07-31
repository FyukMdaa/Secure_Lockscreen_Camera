package com.github.droserasprout.lockscreencamera.hook;

import android.view.View;

import java.util.List;

import com.github.droserasprout.lockscreencamera.util.CameraPackageUtil;

import io.github.libxposed.api.XposedModule;

/** カメラアプリ内の SurfaceView 等（DecorView 以外）が非表示化されるのを阻止する。 */
public final class ViewVisibilityProtectionHook {

    private ViewVisibilityProtectionHook() {}

    public static void install(XposedModule module) {
        try {
            module.hook(View.class.getMethod("setVisibility", int.class)).intercept(chain -> {
                View v = (View) chain.getThisObject();
                if (CameraPackageUtil.isCameraContext(v.getContext()) && !CameraPackageUtil.isDecorView(v)) {
                    List<Object> args = chain.getArgs();
                    int vis = (int) args.get(0);
                    if (vis != View.VISIBLE) args.set(0, View.VISIBLE);
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}
    }
}
