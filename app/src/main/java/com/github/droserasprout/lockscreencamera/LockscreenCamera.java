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
    
    // 重複スキャン・重複フック防止用キャッシュ
    private static final Set<Class<?>> scannedClasses = new HashSet<>();

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
            Class<?> cameraClass = Class.forName(
                    "com.android.camera.Camera", true, param.getClassLoader());

            // 1. onCreate フック（フラグ適用 ＋ 内部フィールド修正）
            Method onCreate = findMethod(cameraClass, "onCreate", Bundle.class);
            hook(onCreate).intercept(chain -> {
                Activity activity = (Activity) chain.getThisObject();
                
                // ① 標準APIフラグを適用
                applyLockscreenFlags(activity);
                
                // ② MIUI内部フィールドを上書き（初期化より先に実行）
                scanAndFixInternalFields(activity);
                
                return chain.proceed();
            });

            // onStart / onResume でもフラグを再適用（ROM側で書き換えられた場合の保険）
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

            // 2. MIUI 固有メソッドフック (checkKeyguard / checkKeyguardFlag)
            for (String methodName : new String[]{"checkKeyguard", "checkKeyguardFlag"}) {
                try {
                    Method m = findMethod(cameraClass, methodName);
                    hook(m).intercept(chain -> {
                        Class<?> retType = m.getReturnType();
                        log(Log.INFO, TAG, "Suppressed method: " + methodName);
                        if (retType == boolean.class) return false;
                        if (retType == int.class) return 0;
                        return null;
                    });
                } catch (Throwable ignored) {}
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

                    // ホーム画面など鍵画面が出ていない場合は通常起動にフォールバック
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

    /** 
     * Gemini 提案：内部 boolean フィールドのスキャン＆強制上書き
     * MIUIの難読化フィールド（例: mIsKeyguardBlocked, mSecureMode等）を直接制御する
     */
    private void scanAndFixInternalFields(Activity activity) {
        Class<?> clazz = activity.getClass();
        // 同じクラスは一度だけスキャン（パフォーマンス保護）
        if (scannedClasses.contains(clazz)) return;

        try {
            Class<?> current = clazz;
            while (current != null && !current.getName().equals("android.app.Activity")) {
                for (Field f : current.getDeclaredFields()) {
                    String name = f.getName().toLowerCase();
                    // キーガード関連の boolean フィールドを抽出
                    if ((name.contains("keyguard") || name.contains("secure") || name.contains("locked")) 
                            && f.getType() == boolean.class) {
                        try {
                            f.setAccessible(true);
                            boolean oldVal = f.getBoolean(activity);
                            log(Log.DEBUG, TAG, "Field scan: " + f.getName() + " in " + clazz.getSimpleName() 
                                    + " | old=" + oldVal + " -> force=true");
                            // 全て true に設定。もし不具合が出る場合はログを見て特定フィールドのみ false に調整
                            f.setBoolean(activity, true);
                        } catch (Throwable ignored) {}
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Field scan failed", t);
        }
        scannedClasses.add(clazz);
    }
}
