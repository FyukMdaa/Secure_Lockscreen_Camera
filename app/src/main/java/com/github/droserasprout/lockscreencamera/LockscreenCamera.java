package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";

    private static Method findMethod(Class<?> clazz, String name, Class<?>... params)
            throws NoSuchMethodException {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "applying com.android.camera hooks");

        try {
            Class<?> cameraClass = Class.forName(
                    "com.android.camera.Camera", true, param.getClassLoader());

            // onCreate: ロック画面フラグ設定
            Method onCreate = findMethod(cameraClass, "onCreate", Bundle.class);
            hook(onCreate).intercept(chain -> {
                log(Log.INFO, TAG, "onCreate hooked");
                Activity activity = (Activity) chain.getThisObject();
                activity.setShowWhenLocked(true);
                activity.setTurnScreenOn(true);
                final Window win = activity.getWindow();
                win.addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                );
                return chain.proceed();
            });

            // onStart / onResume: フラグ再適用
            for (String methodName : new String[]{"onStart", "onResume"}) {
                try {
                    Method m = findMethod(cameraClass, methodName);
                    hook(m).intercept(chain -> {
                        Activity activity = (Activity) chain.getThisObject();
                        activity.setShowWhenLocked(true);
                        activity.setTurnScreenOn(true);
                        final Window win = activity.getWindow();
                        win.addFlags(
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        );
                        return chain.proceed();
                    });
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "Could not hook " + methodName + ": " + t.getMessage());
                }
            }

            // checkKeyguard / checkKeyguardFlag: MIUIがsetShowWhenLocked(false)するのを抑制
            for (String methodName : new String[]{"checkKeyguard", "checkKeyguardFlag"}) {
                try {
                    Method m = findMethod(cameraClass, methodName);
                    hook(m).intercept(chain -> {
                        log(Log.INFO, TAG, "suppressing " + methodName);
                        return null;
                    });
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "Could not hook " + methodName + ": " + t.getMessage());
                }
            }

        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Error in onPackageReady: " + t.getMessage(), t);
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        log(Log.INFO, TAG, "onSystemServerStarting called");

        try {
            Class<?> gestureClass = Class.forName(
                    "com.android.server.GestureLauncherService", true, param.getClassLoader());
            Method handleCameraGesture = gestureClass.getDeclaredMethod(
                    "handleCameraGesture", boolean.class, int.class);

            hook(handleCameraGesture).intercept(chain -> {
                log(Log.INFO, TAG, "intercepting GestureLauncherService.handleCameraGesture");
                try {
                    Method getContextMethod = chain.getThisObject().getClass()
                            .getMethod("getContext");
                    Context context = (Context) getContextMethod.invoke(chain.getThisObject());
                    Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    intent.setPackage("com.android.camera");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                                    Intent.FLAG_ACTIVITY_CLEAR_TASK |
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    android.os.UserHandle current = (android.os.UserHandle)
                            android.os.UserHandle.class.getField("CURRENT").get(null);
                    Method startActivityAsUser = Context.class.getMethod(
                            "startActivityAsUser", Intent.class, android.os.UserHandle.class);
                    startActivityAsUser.invoke(context, intent, current);
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Error launching camera: " + t.getMessage(), t);
                }
                return Boolean.FALSE;
            });

        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Error hooking GestureLauncherService: " + t.getMessage(), t);
        }
    }
}
