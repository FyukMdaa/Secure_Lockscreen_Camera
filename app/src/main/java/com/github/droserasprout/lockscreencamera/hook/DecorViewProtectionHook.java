package com.github.droserasprout.lockscreencamera.hook;

import android.util.Log;
import android.view.View;

import java.util.List;

import com.github.droserasprout.lockscreencamera.util.CameraPackageUtil;

import io.github.libxposed.api.XposedModule;

/**
 * DecorView（Activity のルートビュー）が setAlpha(0) や setVisibility(GONE) で
 * 非表示化・透明化されるのを物理的に防ぐ。
 */
public final class DecorViewProtectionHook {

    private static final String TAG = "LockscreenCamera";

    private DecorViewProtectionHook() {}

    public static void install(XposedModule module) {
        try {
            module.hook(View.class.getDeclaredMethod("setAlpha", float.class)).intercept(chain -> {
                View view = (View) chain.getThisObject();
                if (CameraPackageUtil.isDecorView(view) && CameraPackageUtil.isCameraContext(view.getContext())) {
                    List<Object> args = chain.getArgs();
                    float alpha = (float) args.get(0);
                    if (alpha < 1.0f) args.set(0, 1.0f);
                }
                return chain.proceed();
            });
            module.hook(View.class.getDeclaredMethod("setVisibility", int.class)).intercept(chain -> {
                View view = (View) chain.getThisObject();
                if (CameraPackageUtil.isDecorView(view) && CameraPackageUtil.isCameraContext(view.getContext())) {
                    List<Object> args = chain.getArgs();
                    int vis = (int) args.get(0);
                    if (vis != View.VISIBLE) args.set(0, View.VISIBLE);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "DecorViewProtectionHook install failed: " + t);
        }
    }
}
