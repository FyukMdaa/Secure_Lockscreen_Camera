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

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";

    public LockscreenCamera() {
        super();
    }

    /** クラス階層を遡ってメソッドを検索するヘルパー */
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

        log(Log.INFO, TAG, "Targeting com.android.camera");

        try {
            // MIUI Camera のメイン Activity をターゲット
            Class<?> cameraClass = Class.forName(
                    "com.android.camera.Camera", true, param.getClassLoader());

            // 1. ライフサイクルフック（フラグ適用）
            Method onCreate = findMethod(cameraClass, "onCreate", Bundle.class);
            hook(onCreate).intercept(chain -> {
                Activity activity = (Activity) chain.getThisObject();
                applyLockscreenFlags(activity);
                return chain.proceed();
            });

            for (String methodName : new String[]{"onStart", "onResume"}) {
                try {
                    Method m = findMethod(cameraClass, methodName);
                    hook(m).intercept(chain -> {
                        Activity activity = (Activity) chain.getThisObject();
                        applyLockscreenFlags(activity);
                        return chain.proceed();
                    });
                } catch (Throwable ignored) {}
            }

            // 2. 【核心修正】MIUI の checkKeyguard / checkKeyguardFlag を無効化
            // これらが setShowWhenLocked(false) を呼ぶのをブロックする
            for (String methodName : new String[]{"checkKeyguard", "checkKeyguardFlag"}) {
                try {
                    Method m = findMethod(cameraClass, methodName);
                    hook(m).intercept(chain -> {
                        log(Log.INFO, TAG, "Suppressed MIUI keyguard check: " + methodName);
                        Class<?> retType = m.getReturnType();
                        // 戻り値型に応じて安全なデフォルトを返す
                        if (retType == boolean.class) return false; // カメラ終了要求を無効化
                        if (retType == int.class) return 0;
                        return null; // void または Object
                    });
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "Method not found or hook failed: " + methodName);
                }
            }

        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook camera activity", t);
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

                    // 【修正】ホーム画面など、鍵画面が出ていない場合は通常起動に任せる（問題点2の解決）
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
                    return null; // 元のシステム処理をキャンセル
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
            activity.setKeepScreenOn(true);

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
}
