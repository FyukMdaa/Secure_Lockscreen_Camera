package com.github.droserasprout.lockscreencamera.hook;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * その他の細かいシステムフック群：
 *  - CameraManager.AvailabilityCallback.onCameraUnavailable を無効化
 *  - BiometricManager.canAuthenticate を常に「利用可能」として返す
 */
public final class MiscSystemHook {

    private static final String TAG = "LockscreenCamera";

    private MiscSystemHook() {}

    public static void install(XposedModule module, PackageReadyParam param) {
        try {
            Class<?> callbackClass = Class.forName(
                    "android.hardware.camera2.CameraManager$AvailabilityCallback", true, param.getClassLoader());
            module.hook(callbackClass.getDeclaredMethod("onCameraUnavailable", String.class)).intercept(chain -> {
                module.log(Log.INFO, TAG, "Blocked onCameraUnavailable for ID: " + chain.getArgs().get(0));
                return null;
            });
        } catch (Throwable ignored) {}

        try {
            Class<?> biometricClass = Class.forName(
                    "android.hardware.biometrics.BiometricManager", true, param.getClassLoader());
            module.hook(biometricClass.getDeclaredMethod("canAuthenticate", int.class)).intercept(chain -> 0);
        } catch (Throwable ignored) {}
    }
}
