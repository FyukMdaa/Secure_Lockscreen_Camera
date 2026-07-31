package com.github.droserasprout.lockscreencamera.hook;

import android.app.Activity;

import com.github.droserasprout.lockscreencamera.util.CameraPackageUtil;

import io.github.libxposed.api.XposedModule;

/** カメラ Activity の hasWindowFocus/isResumed を常に true として返す（フォーカス喪失の誤検知対策）。 */
public final class ActivityVisibilitySpoofHook {

    private ActivityVisibilitySpoofHook() {}

    public static void install(XposedModule module) {
        try {
            module.hook(Activity.class.getDeclaredMethod("hasWindowFocus")).intercept(chain -> {
                if (CameraPackageUtil.isCameraActivity((Activity) chain.getThisObject())) return true;
                return (Boolean) chain.proceed();
            });
            module.hook(Activity.class.getDeclaredMethod("isResumed")).intercept(chain -> {
                if (CameraPackageUtil.isCameraActivity((Activity) chain.getThisObject())) return true;
                return (Boolean) chain.proceed();
            });
        } catch (Throwable ignored) {}
    }
}
