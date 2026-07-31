package com.github.droserasprout.lockscreencamera.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.github.droserasprout.lockscreencamera.util.CameraPackageUtil;

import io.github.libxposed.api.XposedModule;

/** getIntent() の動的書き換え：MIUI のキーガード起動フラグから is_secure_camera 等を補完する。 */
public final class KeyguardIntentRewriteHook {

    private static final String EXTRA_START_BY_KEYGUARD = "com.miui.camera.extra.START_BY_KEYGUARD";
    private static Context settingsContext;

    private KeyguardIntentRewriteHook() {}

    public static void install(XposedModule module, Context context) {
        settingsContext = context;
        try {
            module.hook(Activity.class.getDeclaredMethod("getIntent")).intercept(chain -> {
                Intent intent = (Intent) chain.proceed();
                Activity act = (Activity) chain.getThisObject();
                if (CameraPackageUtil.isCameraActivity(act, settingsContext) && intent != null
                        && intent.getBooleanExtra(EXTRA_START_BY_KEYGUARD, false)) {
                    intent.putExtra("is_secure_camera", true);
                    intent.putExtra("ShowCameraWhenLocked", true);
                }
                return intent;
            });
        } catch (Throwable ignored) {}
    }
}
