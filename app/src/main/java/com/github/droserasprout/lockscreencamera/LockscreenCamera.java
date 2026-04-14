package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";
    private static final Set<Class<?>> scannedClasses = new HashSet<>();

    public LockscreenCamera() {
        super();
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... params)
            throws NoSuchMethodException {
        Class<?> c = clazz;
        while (c != null) {
            try { return c.getDeclaredMethod(name, params); }
            catch (NoSuchMethodException e) { c = c.getSuperclass(); }
        }
        throw new NoSuchMethodException(name);
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting com.android.camera (with Debug Hook)");

        // デバッグフック：setShowWhenLocked(false) を呼んでいる場所を特定
        try {
            Method target = Activity.class.getDeclaredMethod("setShowWhenLocked", boolean.class);
            hook(target).intercept(chain -> {
                // 【修正】List<Object> なので .get(0) を使用
                boolean show = (boolean) chain.getArgs().get(0);
                if (!show) {
                    log(Log.WARN, TAG, "!!! ALERT !!! setShowWhenLocked(false) called by: " + 
                        Log.getStackTraceString(new Throwable()));
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        try {
            Class<?> cameraClass = Class.forName("com.android.camera.Camera", true, param.getClassLoader());

            String[] methods = {"onCreate", "onStart", "onResume"};
            for (String methodName : methods) {
                try {
                    Method m = methodName.equals("onCreate") 
                        ? findMethod(cameraClass, "onCreate", Bundle.class) 
                        : findMethod(cameraClass, methodName);
                    
                    hook(m).intercept(chain -> {
                        Object res = chain.proceed();
                        
                        Activity activity = (Activity) chain.getThisObject();
                        applyLockscreenFlags(activity);
                        forceFixInternalState(activity);
                        
                        return res;
                    });
                } catch (Throwable ignored) {}
            }

            for (String methodName : new String[]{"checkKeyguard", "checkKeyguardFlag"}) {
                try {
                    Method m = findMethod(cameraClass, methodName);
                    hook(m).intercept(chain -> {
                        log(Log.DEBUG, TAG, "Suppressed method: " + methodName);
                        if (m.getReturnType() == boolean.class) return false;
                        return null;
                    });
                } catch (Throwable ignored) {}
            }

        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Hook setup failed", t);
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        log(Log.INFO, TAG, "System Server Starting: Hooking GestureLauncherService");

        try {
            Class<?> gestureClass = Class.forName(
                    "com.android.server.GestureLauncherService", true, param.getClassLoader());
            Method handleCameraGesture = gestureClass.getDeclaredMethod(
                    "handleCameraGesture", boolean.class, int.class);

            hook(handleCameraGesture).intercept(chain -> {
                try {
                    Method getContextMethod = chain.getThisObject().getClass().getMethod("getContext");
                    Context context = (Context) getContextMethod.invoke(chain.getThisObject());

                    KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                    if (km != null && !km.isKeyguardLocked()) {
                        return chain.proceed();
                    }

                    Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK 
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS 
                            | Intent.FLAG_ACTIVITY_NO_USER_ACTION
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    
                    context.startActivity(intent);
                    return null;
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Failed to launch secure camera", t);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "GestureLauncherService hook skipped", t);
        }
    }

    private void applyLockscreenFlags(Activity activity) {
        try {
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);

            Window win = activity.getWindow();
            if (win != null) {
                win.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                );
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Flags apply failed", t);
        }
    }

    private void forceFixInternalState(Activity activity) {
        Class<?> clazz = activity.getClass();
        if (scannedClasses.contains(clazz)) return;

        try {
            Class<?> current = clazz;
            while (current != null && !current.getName().equals("android.app.Activity")) {
                for (Field f : current.getDeclaredFields()) {
                    String name = f.getName().toLowerCase();
                    if ((name.contains("keyguard") || name.contains("secure") || name.contains("locked")) 
                            && f.getType() == boolean.class) {
                        try {
                            f.setAccessible(true);
                            boolean oldVal = f.getBoolean(activity);
                            if (!oldVal) {
                                log(Log.INFO, TAG, "Patched field: " + f.getName() + " in " + current.getSimpleName() + " -> true");
                                f.setBoolean(activity, true);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Field patch failed", t);
        }
        scannedClasses.add(clazz);
    }
}
