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
import io.github.libxposed.api.XposedModuleInterface;

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

            Method onCreate = findMethod(cameraClass, "onCreate", Bundle.class);
            hook(onCreate).intercept(chain -> {
                log(Log.INFO, TAG, "hooking Camera activity onCreate");
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
                    log(Log.WARN, TAG, "Could not hook " + methodName, t);
                }
            }

            // checkKeyguard と checkKeyguardFlag を無効化
            for (String methodName : new String[]{"checkKeyguard", "checkKeyguardFlag"}) {
                try {
                    Method m = findMethod(cameraClass, methodName);
                    hook(m).intercept(chain -> {
                        log(Log.INFO, TAG, "suppressing " + methodName);
                        // 何もせずreturn（setShowWhenLocked(false)を呼ばせない）
                        return null;
                    });
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "Could not hook " + methodName, t);
                }
            }

        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Error hooking com.android.camera", t);
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        log(Log.INFO, TAG, "onSystemServerStarting called");
        log(Log.INFO, TAG, "applying system_server hooks for GestureLauncherService");

        try {
            Class<?> gestureClass = Class.forName(
                    "com.android.server.GestureLauncherService", true, param.getClassLoader());
            Method handleCameraGesture = gestureClass.getDeclaredMethod(
                    "handleCameraGesture", boolean.class, int.class);

            hook(handleCameraGesture).intercept(chain -> {
                log(Log.INFO, TAG, "hooking GestureLauncherService.handleCameraGesture");
                try {
                    Method getContextMethod = chain.getThisObject().getClass()
                            .getMethod("getContext");
                    Context context = (Context) getContextMethod.invoke(chain.getThisObject());
                    Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    android.os.UserHandle current = (android.os.UserHandle) 
                        android.os.UserHandle.class.getField("CURRENT").get(null);
                    Method startActivityAsUser = Context.class.getMethod(
                        "startActivityAsUser", Intent.class, android.os.UserHandle.class);
                    startActivityAsUser.invoke(context, intent, current);
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Error launching secure camera from system_server", t);
                }
                return null;
            });

        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Error hooking GestureLauncherService", t);
        }
    }
}
