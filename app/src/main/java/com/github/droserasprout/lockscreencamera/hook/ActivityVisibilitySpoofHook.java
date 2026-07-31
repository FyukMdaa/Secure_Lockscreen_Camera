package com.github.droserasprout.lockscreencamera.hook;

import android.app.Activity;
import android.content.Context;

import com.github.droserasprout.lockscreencamera.util.CameraPackageUtil;

import io.github.libxposed.api.XposedModule;

/** hasWindowFocus/isResumed を常に true として返す（フォーカス喪失の誤検知対策）。 */
public final class ActivityVisibilitySpoofHook {

    private static Context settingsContext;

    private ActivityVisibilitySpoofHook() {}

    public static void install(XposedModule module, Context context) {
        settingsContext = context;
        try {
            module.hook(Activity.class.getDeclaredMethod("hasWindowFocus")).intercept(chain -> {
                if (CameraPackageUtil.isCameraActivity((Activity) chain.getThisObject(), settingsContext)) return true;
                return (Boolean) chain.proceed();
            });
            module.hook(Activity.class.getDeclaredMethod("isResumed")).intercept(chain -> {
                if (CameraPackageUtil.isCameraActivity((Activity) chain.getThisObject(), settingsContext)) return true;
                return (Boolean) chain.proceed();
            });
        } catch (Throwable ignored) {}
    }
}
