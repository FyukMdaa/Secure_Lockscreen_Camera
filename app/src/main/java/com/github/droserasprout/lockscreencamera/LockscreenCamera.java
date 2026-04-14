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

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";
    private static final Set<Class<?>> appliedClasses = new HashSet<>();

    public LockscreenCamera() {
        super();
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
                    Intent intent = activity.getIntent();
                    
                    // ロック画面からの起動かどうかを判定
                    boolean isSecureLaunch = intent != null && 
                            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE.equals(intent.getAction());

                    if ("onCreate".equals(methodName)) {
                        if (isSecureLaunch) {
                            // 1. onCreat eではフラグのみ適用（初期化を妨げない）
                            applyLockscreenFlags(activity);
                        }
                        // chain.proceed() で通常の初期化を先に実行させ、NPEを回避
                        return chain.proceed();
                    }

                    if (("onStart".equals(methodName) || "onResume".equals(methodName)) && isSecureLaunch) {
                        // 2. 初期化完了後のタイミングで監視メソッド抑制を実行
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
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        log(Log.INFO, TAG, "System Server Starting: Hooking GestureLauncherService");

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

                    // 【修正】ホーム画面など、ロック画面が出ていない場合は通常起動に任せる
                    KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                    if (km != null && !km.isKeyguardLocked()) {
                        return chain.proceed();
                    }

                    Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK 
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS 
                            | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
                    
                    context.startActivity(intent);
                    return null; // 元の処理をキャンセル
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
                
                // ライフサイクルメソッドや内部初期化メソッドのフックを明示的に除外
                if (name.startsWith("oncreate") || name.startsWith("onstart") || 
                    name.startsWith("onresume") || name.contains("init") || name.contains("setup")) continue;

                if (m.getParameterTypes().length > 2) continue;
                Class<?> ret = m.getReturnType();
                if (ret != boolean.class && ret != void.class && ret != int.class) continue;

                // 【修正】"camera" の除外を削除し、keyguard/secure のみに厳格化
                // (initCamera 等のカメラ処理を潰すのを防ぐため)
                if (name.contains("keyguard") || name.contains("secure")) {
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
