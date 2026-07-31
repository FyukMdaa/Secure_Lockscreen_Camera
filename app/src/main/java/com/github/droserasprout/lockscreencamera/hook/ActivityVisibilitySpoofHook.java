package com.github.droserasprout.lockscreencamera.hook;

import android.app.Activity;
import android.content.SharedPreferences;

import com.github.droserasprout.lockscreencamera.util.CameraPackageUtil;

import io.github.libxposed.api.XposedModule;

/** hasWindowFocus/isResumed を常に true として返す（フォーカス喪失の誤検知対策）。 */
public final class ActivityVisibilitySpoofHook {

    private static SharedPreferences prefs;

    private ActivityVisibilitySpoofHook() {}

    public static void install(XposedModule module, SharedPreferences prefs) {
        ActivityVisibilitySpoofHook.prefs = prefs;
        try {
            module.hook(Activity.class.getDeclaredMethod("hasWindowFocus")).intercept(chain -> {
                if (CameraPackageUtil.isCameraActivity((Activity) chain.getThisObject(), ActivityVisibilitySpoofHook.prefs)) return true;
                return (Boolean) chain.proceed();
            });
            module.hook(Activity.class.getDeclaredMethod("isResumed")).intercept(chain -> {
                if (CameraPackageUtil.isCameraActivity((Activity) chain.getThisObject(), ActivityVisibilitySpoofHook.prefs)) return true;
                return (Boolean) chain.proceed();
            });
        } catch (Throwable ignored) {}
    }
}