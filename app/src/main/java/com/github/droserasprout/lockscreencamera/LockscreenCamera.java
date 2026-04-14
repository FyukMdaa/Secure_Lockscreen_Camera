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

    // 【認証競合回避】判定に直結するフィールドのみを狙い撃ち（全スキャン廃止）
    private static final String[] TARGET_BOOLEAN_FIELDS = {
        "mIsSecure", "mIsSecureCamera", "mKeyguardLocked",
        "mInLockScreen", "mIgnoreKeyguard", "mIsScreenOn",
        "mSecureCamera", "mIsKeyguardLocked", "mIsHideForeground", 
        "mIsGalleryLock", "mIsCaptureIntent", "mIsPortraitIntent", "mIsVideoIntent",
        "mUserAuthenticationFlag", "mIgnoreKeyguardCheck" // 認証競合回避用
    };

    public LockscreenCamera() {
        super();
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting com.android.camera (Biometric Bypass / Phase3)");

        // 1. 【核心】BiometricManager 偽装（認証リソース競合の回避）
        // システムに「認証プロセスは完了／競合なし」と誤認させ、CameraService の切断を防ぐ        try {
            Class<?> biometricClass = Class.forName("android.hardware.biometrics.BiometricManager");
            Method canAuth = biometricClass.getDeclaredMethod("canAuthenticate", int.class);
            hook(canAuth).intercept(chain -> 0); // BIOMETRIC_SUCCESS を返す
        } catch (Throwable ignored) {}

        // 2. KeyguardManager 偽装（システム判定回避）
        try {
            Class<?> kmClass = KeyguardManager.class;
            hook(kmClass.getDeclaredMethod("isKeyguardLocked")).intercept(chain -> false);
            hook(kmClass.getDeclaredMethod("isDeviceLocked")).intercept(chain -> false);
        } catch (Throwable ignored) {}

        // 3. Intent 保護（セキュアアクション維持）
        try {
            hook(Activity.class.getDeclaredMethod("setIntent", Intent.class)).intercept(chain -> {
                Intent intent = (Intent) ((List<?>) chain.getArgs()).get(0);
                if (intent != null) {
                    intent.setAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 4. finish() 監視＋ブロック（真犯人特定）
        try {
            hook(Activity.class.getDeclaredMethod("finish")).intercept(chain -> {
                log(Log.ERROR, TAG, "!!! Finish Attempted !!! Trace: " + Log.getStackTraceString(new Throwable()));
                return null; // 終了処理をキャンセル
            });
        } catch (Throwable ignored) {}

        // 5. 描画パイプライン確定タイミングでのみパッチ適用（onCreate は避ける）
        String[] bufferCriticalMethods = {"onStart", "onResume", "onWindowFocusChanged"};
        for (String mname : bufferCriticalMethods) {
            try {
                Method m = "onWindowFocusChanged".equals(mname)
                        ? Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class)
                        : Activity.class.getDeclaredMethod(mname);

                hook(m).intercept(chain -> {
                    Activity act = (Activity) chain.getThisObject();
                    
                    // OneTrack 関連は即スキップ（ANR/リーク防止）
                    if (act.getClass().getName().contains("onetrack")) {
                        return chain.proceed();
                    }

                    if (act.getPackageName().equals("com.android.camera")) {
                        // MIUI の初期化を先に通す                        Object res = chain.proceed();
                        // ウィンドウ確定後にバッファ状態をリセット＆認証フラグ適用
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
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "System hook skipped", t);
        }
    }

    /**
     * 描画パイプライン偽装：FLAG_SECURE クリア → フラグ再設定 → 認証フラグパッチ
     * Biometric/Sensor 競合による HardwareBuffer 破棄を防ぐ
     */
    private void applyWindowAndBufferFixes(Activity activity) {
        try {            // Android 15 要件：画面点灯状態とカメラ権限の明示的リンク
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);

            Window win = activity.getWindow();
            if (win != null) {
                // 1. 厳格なセキュアチェックを一度解除（Buffer 破棄トリガーを無効化）
                win.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                
                // 2. ロック画面フラグを再適用（SurfaceFlinger に「安全な割り当て」と誤認させる）
                win.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                );
            }

            // 3. 遅延パッチ：セッション権限・認証判定に必要なフィールドのみ書き換え
            for (String fieldName : TARGET_BOOLEAN_FIELDS) {
                setFieldIfExists(activity, fieldName, true);
            }
            setFieldIfExists(activity, "mKeyguardStatus", 1);
            
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "Buffer/Biometric fix failed", t);
        }
    }

    /**
     * 高速フィールド探索（全スキャン廃止。見つかり次第即終了）
     */
    private void setFieldIfExists(Object obj, String fieldName, Object value) {
        try {
            Class<?> current = obj.getClass();
            while (current != null && !current.getName().equals("android.app.Activity")) {
                try {
                    Field f = current.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    f.set(obj, value);
                    return; // 成功即終了
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                }
            }
        } catch (Throwable ignored) {}
    }
}
