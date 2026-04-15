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
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";

    // 【Phase 4 追加】カメラセッション維持・プライバシー回避用フィールド
    private static final String[] TARGET_BOOLEAN_FIELDS = {
        "mIsSecure", "mIsSecureCamera", "mKeyguardLocked",
        "mInLockScreen", "mIgnoreKeyguard", "mIsScreenOn",
        "mSecureCamera", "mIsKeyguardLocked", "mIsHideForeground", 
        "mIsGalleryLock", "mIsCaptureIntent", "mIsPortraitIntent", "mIsVideoIntent",
        "mUserAuthenticationFlag", "mIgnoreKeyguardCheck",
        // ↓ 追加キーワード
        "mIsCameraApp", "mPrivacyAuthorized", "mIsForeground"
    };

    public LockscreenCamera() {
        super();
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting com.android.camera (Phase 4: Camera Service Bypass)");
        // 1. 【重要】CameraManager$AvailabilityCallback フック（切断トリガーの無力化）
        // "onCameraUnavailable" (例: ID 4 が存在しない等の通知) をブロックし、
        // アプリがセッションを破棄するのを防ぐ
        try {
            Class<?> callbackClass = Class.forName("android.hardware.camera2.CameraManager$AvailabilityCallback");
            Method onUnavailable = callbackClass.getDeclaredMethod("onCameraUnavailable", String.class);
            hook(onUnavailable).intercept(chain -> {
                String cameraId = (String) chain.getArgs().get(0);
                log(Log.INFO, TAG, "Blocked onCameraUnavailable for ID: " + cameraId);
                return null; // イベントを握りつぶす
            });
        } catch (Throwable ignored) {}

        // 2. BiometricManager 偽装（認証リソース競合の回避）
        try {
            Class<?> biometricClass = Class.forName("android.hardware.biometrics.BiometricManager");
            Method canAuth = biometricClass.getDeclaredMethod("canAuthenticate", int.class);
            hook(canAuth).intercept(chain -> 0); // BIOMETRIC_SUCCESS
        } catch (Throwable ignored) {}

        // 3. KeyguardManager 偽装
        try {
            Class<?> kmClass = KeyguardManager.class;
            hook(kmClass.getDeclaredMethod("isKeyguardLocked")).intercept(chain -> false);
            hook(kmClass.getDeclaredMethod("isDeviceLocked")).intercept(chain -> false);
        } catch (Throwable ignored) {}

        // 4. Intent 保護
        try {
            hook(Activity.class.getDeclaredMethod("setIntent", Intent.class)).intercept(chain -> {
                Intent intent = (Intent) ((List<?>) chain.getArgs()).get(0);
                if (intent != null) {
                    intent.setAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 5. finish() 監視＋ブロック
        try {
            hook(Activity.class.getDeclaredMethod("finish")).intercept(chain -> {
                log(Log.ERROR, TAG, "!!! Finish Attempted !!! Trace: " + Log.getStackTraceString(new Throwable()));
                return null;
            });
        } catch (Throwable ignored) {}

        // 6. ライフサイクルフック（描画パイプライン再構築）
        String[] bufferCriticalMethods = {"onStart", "onResume", "onWindowFocusChanged"};
        for (String mname : bufferCriticalMethods) {
            try {                Method m = "onWindowFocusChanged".equals(mname)
                        ? Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class)
                        : Activity.class.getDeclaredMethod(mname);

                hook(m).intercept(chain -> {
                    Activity act = (Activity) chain.getThisObject();
                    
                    if (act.getClass().getName().contains("onetrack")) {
                        return chain.proceed();
                    }

                    if (act.getPackageName().equals("com.android.camera")) {
                        Object res = chain.proceed();
                        applyWindowAndBufferFixes(act);
                        return res;
                    }
                    return chain.proceed();
                });
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        // ... (ジェスチャー起動処理は従来通り) ...
        try {
            Class<?> gestureClass = Class.forName(
                    "com.android.server.GestureLauncherService", true, param.getClassLoader());
            Method handleCameraGesture = gestureClass.getDeclaredMethod(
                    "handleCameraGesture", boolean.class, int.class);

            hook(handleCameraGesture).intercept(chain -> {
                try {
                    Method getContextMethod = chain.getThisObject().getClass().getMethod("getContext");
                    Context context = (Context) getContextMethod.invoke(chain.getThisObject());

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
                    log(Log.ERROR, TAG, "Failed to launch", t);
                }
                return chain.proceed();            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "System hook skipped", t);
        }
    }

    /**
     * 描画パイプライン偽装 & カメラセッション維持設定
     */
    private void applyWindowAndBufferFixes(Activity activity) {
        try {
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
            
            // 【Phase 4 追加】親ウィンドウからの表示設定継承を有効化
            // IME や他のシステム UI がフォーカスを奪った際でも、ロック画面表示を維持する
            activity.setInheritShowWhenLocked(true);

            Window win = activity.getWindow();
            if (win != null) {
                // 厳格なセキュアチェック解除
                win.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                
                // ロック画面フラグ再適用
                win.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                );
            }

            // セッション権限・プライバシー判定フラグ書き換え
            for (String fieldName : TARGET_BOOLEAN_FIELDS) {
                setFieldIfExists(activity, fieldName, true);
            }
            setFieldIfExists(activity, "mKeyguardStatus", 1);
            
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "Fixes failed", t);
        }
    }

    /**
     * 高速フィールド探索
     */
    private void setFieldIfExists(Object obj, String fieldName, Object value) {
        try {
            Class<?> current = obj.getClass();
            while (current != null && !current.getName().equals("android.app.Activity")) {                try {
                    Field f = current.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    f.set(obj, value);
                    return;
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                }
            }
        } catch (Throwable ignored) {}
    }
}
