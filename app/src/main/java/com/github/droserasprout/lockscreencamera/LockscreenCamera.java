package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper; // 追加
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";

    // スレッドセーフなキャッシュ
    private static final Map<String, Field> fieldCache = new ConcurrentHashMap<>();

    private static final String[] TARGET_BOOLEAN_FIELDS = {
        "mIsSecure", "mIsSecureCamera", "mKeyguardLocked",
        "mInLockScreen", "mIgnoreKeyguard", "mIsScreenOn",
        "mSecureCamera", "mIsKeyguardLocked", "mIsHideForeground", 
        "mIsGalleryLock", "mIsCaptureIntent", "mIsPortraitIntent", "mIsVideoIntent",
        "mUserAuthenticationFlag", "mIgnoreKeyguardCheck",
        "mIsCameraApp", "mPrivacyAuthorized", "mIsForeground"
    };

    public LockscreenCamera() {
        super();
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting com.android.camera (Foreground Start Fix)");

        // フック対象のメソッドを拡張（attachBaseContext を追加）
        // attachBaseContext は ContextWrapper で定義されているため、別途メソッド取得が必要
        String[] criticalMethods = {"attachBaseContext", "onCreate", "onStart", "onResume", "onWindowFocusChanged"};

        for (String mname : criticalMethods) {
            try {
                Method m;
                if ("attachBaseContext".equals(mname)) {
                    m = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
                } else if ("onCreate".equals(mname)) {
                    m = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
                } else if ("onWindowFocusChanged".equals(mname)) {
                    m = Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class);
                } else {
                    m = Activity.class.getDeclaredMethod(mname);
                }

                // 【修正】intercept を使用し、chain.proceed() の前にパッチを適用（Before フック相当）
                hook(m).intercept(chain -> {
                    Object thisObj = chain.getThisObject();
                    if (thisObj instanceof Activity) {
                        Activity act = (Activity) thisObj;
                        
                        // attachBaseContext のタイミングでは getPackageName() が null を返す場合があるため
                        // クラス名で判定するフォールバックを用意
                        boolean isTarget = false;
                        try {
                            isTarget = act.getPackageName().equals("com.android.camera");
                        } catch (Exception e) {
                            isTarget = act.getClass().getName().startsWith("com.android.camera");
                        }

                        if (isTarget) {
                            // フォーカス喪失時のスキップ処理
                            if ("onWindowFocusChanged".equals(mname)) {
                                boolean hasFocus = (boolean) ((List<?>) chain.getArgs()).get(0);
                                if (!hasFocus) return chain.proceed(); 
                            }

                            // 【重要】ここでフラグを適用してから proceed() を呼ぶ
                            // これにより、カメラの初期化段階ですでにロック画面特権が与えられている状態になる
                            applyWindowAndBufferFixes(act);
                        }
                    }                    return chain.proceed();
                });
            } catch (Throwable ignored) {}
        }

        // 既存のシステムフック群（onCameraUnavailable, KeyguardManager 偽装など）
        try {
            Class<?> callbackClass = Class.forName("android.hardware.camera2.CameraManager$AvailabilityCallback", true, param.getClassLoader());
            Method onUnavailable = callbackClass.getDeclaredMethod("onCameraUnavailable", String.class);
            hook(onUnavailable).intercept(chain -> {
                String cameraId = (String) ((List<?>) chain.getArgs()).get(0);
                log(Log.INFO, TAG, "Blocked onCameraUnavailable for ID: " + cameraId);
                return null;
            });
        } catch (Throwable ignored) {}

        try {
            Class<?> biometricClass = Class.forName("android.hardware.biometrics.BiometricManager", true, param.getClassLoader());
            hook(biometricClass.getDeclaredMethod("canAuthenticate", int.class)).intercept(chain -> 0);
        } catch (Throwable ignored) {}

        try {
            Class<?> kmClass = KeyguardManager.class;
            hook(kmClass.getDeclaredMethod("isKeyguardLocked")).intercept(chain -> false);
            hook(kmClass.getDeclaredMethod("isDeviceLocked")).intercept(chain -> false);
        } catch (Throwable ignored) {}
        
        try {
            hook(Activity.class.getDeclaredMethod("setIntent", Intent.class)).intercept(chain -> {
                Intent intent = (Intent) ((List<?>) chain.getArgs()).get(0);
                if (intent != null) intent.setAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        try {
            hook(Activity.class.getDeclaredMethod("finish")).intercept(chain -> {
                log(Log.ERROR, TAG, "Finish Attempted by: " + Log.getStackTraceString(new Throwable()));
                return null;
            });
        } catch (Throwable ignored) {}
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        try {
            Class<?> gestureClass = Class.forName("com.android.server.GestureLauncherService", true, param.getClassLoader());
            Method handleCameraGesture = gestureClass.getDeclaredMethod("handleCameraGesture", boolean.class, int.class);

            hook(handleCameraGesture).intercept(chain -> {                try {
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
                    // ジェスチャーを消費したことを示す true を返す（NPE 防止）
                    return true;
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Failed to launch", t);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "System hook skipped", t);
        }
    }

    private void applyWindowAndBufferFixes(Activity activity) {
        try {
            // 1. Android 8.0 以降のロック画面表示設定（Activity レベル）
            // attachBaseContext の段階でも有効な設定
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);

            Window window = activity.getWindow();
            if (window != null) {
                // 2. ロック画面上での表示を保証するフラグ群
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON | // 追加：ロック画面でのアクティブ化
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                );
                
                // セキュアフラグの解除とバッファ形式の変更（既存対策）
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                window.setFormat(PixelFormat.TRANSLUCENT);

                // 3. Android 15 のバックグラウンド制限回避
                // OS に対して「これは正当なロック画面操作である」と認識させる
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {                    KeyguardManager km = (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
                    if (km != null) {
                        // キーガードの解除要求を送ることで、システムがロック画面コンテキストを認識しやすくなる
                        km.requestDismissKeyguard(activity, null);
                    }
                }
            }

            // 4. セッション維持・パス切り替え用フィールドパッチ
            for (String fieldName : TARGET_BOOLEAN_FIELDS) {
                setFieldFast(activity, fieldName, true);
            }
            setFieldFast(activity, "mIsNormalIntent", false);
            setFieldFast(activity, "mShowEnteringAnimation", false);
            setFieldFast(activity, "mKeyguardStatus", 1);
            setFieldFast(activity, "mIsSecureCameraId", 0);
            
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "Stage 4 fixes failed", t);
        }
    }

    /**
     * 高速フィールド探索（スレッドセーフキャッシュ付き）
     */
    private void setFieldFast(Object obj, String fieldName, Object value) {
        try {
            Class<?> current = obj.getClass();
            String cacheKey = current.getName() + ":" + fieldName;
            
            Field f = fieldCache.computeIfAbsent(cacheKey, k -> {
                Class<?> c = current;
                while (c != null && !c.getName().equals("android.app.Activity")) {
                    try {
                        Field found = c.getDeclaredField(fieldName);
                        found.setAccessible(true);
                        return found;
                    } catch (NoSuchFieldException e) {
                        c = c.getSuperclass();
                    }
                }
                return null;
            });

            if (f != null) {
                f.set(obj, value);
            }
        } catch (Throwable ignored) {}
    }
}
