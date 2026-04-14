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
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoaderParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerReadyParam;

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";
    private static final Set<Class<?>> appliedClasses = new HashSet<>();

    // ModuleLoaderParam のインポート解決
    public LockscreenCamera(@NonNull ModuleLoaderParam param) {
        super(param);
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting " + param.getPackageName());

        try {
            String[] lifecycleMethods = {"onCreate", "onStart", "onResume"};
            for (String methodName : lifecycleMethods) {
                Method method = methodName.equals("onCreate")
                        ? Activity.class.getDeclaredMethod(methodName, Bundle.class)
                        : Activity.class.getDeclaredMethod(methodName);

                hook(method).intercept(chain -> {
                    Activity activity = (Activity) chain.getThisObject();
                    if (activity.getPackageName().equals("com.android.camera")) {
                        applyLockscreenFlags(activity);
                        suppressKeyguardMethods(activity.getClass());
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook Activity lifecycle", t);
        }
    }

    @Override
    // SystemServerReadyParam のインポート解決
    public void onSystemServerReady(@NonNull SystemServerReadyParam param) {
        log(Log.INFO, TAG, "System Server Ready: Hooking GestureLauncherService");

        try {
            Class<?> gestureClass = param.getClassLoader().loadClass(
                    "com.android.server.GestureLauncherService");
            
            Method handleCameraGesture = gestureClass.getDeclaredMethod(
                    "handleCameraGesture", boolean.class, int.class);

            hook(handleCameraGesture).intercept(chain -> {
                log(Log.INFO, TAG, "Intercepted GestureLauncherService.handleCameraGesture");
                try {
                    Method getContextMethod = chain.getThisObject().getClass().getMethod("getContext");
                    Context context = (Context) getContextMethod.invoke(chain.getThisObject());

                    Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK 
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS 
                            | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
                    
                    context.startActivity(intent);
                    return null; // 元の処理をキャンセルして独自の起動のみ実行
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
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD // MIUI監視回避の保険
                );
            }
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "Flags apply failed", t);
        }
    }

    private void suppressKeyguardMethods(Class<?> clazz) {
        if (appliedClasses.contains(clazz)) return;
        
        Class<?> current = clazz;
        int depth = 0;
        final int MAX_DEPTH = 5;

        while (current != null && depth < MAX_DEPTH && !current.getName().equals("android.app.Activity")) {
            for (Method m : current.getDeclaredMethods()) {
                String name = m.getName().toLowerCase();
                if (m.getParameterTypes().length > 2) continue;
                Class<?> ret = m.getReturnType();
                if (ret != boolean.class && ret != void.class && ret != int.class) continue;

                if (name.contains("keyguard") || name.contains("secure") || name.contains("camera")) {
                    try {
                        hook(m).intercept(chain -> getSafeDefault(m));
                    } catch (Throwable ignored) {}
                }
            }
            current = current.getSuperclass();
            depth++;
        }
        appliedClasses.add(clazz);
    }

    private Object getSafeDefault(Method m) {
        Class<?> type = m.getReturnType();
        String name = m.getName().toLowerCase();

        if (type == boolean.class) {
            if (name.contains("check") || name.contains("issecure") || name.contains("islocked")) return true;
            if (name.contains("dismiss") || name.contains("hide") || name.contains("shouldfinish")) return false;
            return false;
        }
        if (type == int.class) return 0;
        return null;
    }
}
