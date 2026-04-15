package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
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
    
    // フィールド検索用キャッシュ
    private static final Map<String, Field> fieldCache = new ConcurrentHashMap<>();

    // 書き換え対象の内部フィールド名リスト
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

    @Override    public void onPackageReady(@NonNull PackageReadyParam param) {
        // スコープ設定により、com.android.camera 以外での呼び出しは発生しないはずですが保険として
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting com.android.camera (LaunchGuard Bypass)");

        // 1. MIUI LaunchGuard の直接無効化
        try {
            String[] candidateClasses = {
                "com.android.camera.LaunchGuard",
                "com.android.camera.guard.LaunchGuard",
                "com.android.camera.module.loader.FunctionCameraPrepare"
            };
            Class<?> launchGuardClass = null;
            for (String className : candidateClasses) {
                try {
                    launchGuardClass = Class.forName(className, true, param.getClassLoader());
                    break;
                } catch (ClassNotFoundException e) {
                    // 見つからなければ次へ
                }
            }

            if (launchGuardClass != null) {
                log(Log.INFO, TAG, "Found LaunchGuard class: " + launchGuardClass.getName());
                for (Method method : launchGuardClass.getDeclaredMethods()) {
                    if (method.getReturnType() == boolean.class) {
                        hook(method).intercept(chain -> true);
                    }
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "LaunchGuard hook failed", t);
        }

        // 2. finish() 呼び出しのブロック
        try {
            Method finishMethod = Activity.class.getDeclaredMethod("finish");
            hook(finishMethod).intercept(chain -> {
                Activity act = (Activity) chain.getThisObject();
                if (isCameraActivity(act)) {
                    log(Log.WARN, TAG, "BLOCKED: finish() intercepted.");
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}
        // 2b. finishAfterTransition() のブロック
        try {
            Method finishAfterMethod = Activity.class.getDeclaredMethod("finishAfterTransition");
            hook(finishAfterMethod).intercept(chain -> {
                Activity act = (Activity) chain.getThisObject();
                if (isCameraActivity(act)) {
                    log(Log.WARN, TAG, "BLOCKED: finishAfterTransition() intercepted.");
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 3. ライフサイクルフック（attachBaseContext, onCreate, etc.）
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

                final String methodName = mname; // ラムダ内用
                hook(m).intercept(chain -> {
                    Object thisObj = chain.getThisObject();
                    if (thisObj instanceof Activity) {
                        Activity act = (Activity) thisObj;
                        // attachBaseContext のタイミングでは getPackageName が null になることがあるためクラス名で判定
                        boolean isTarget = false;
                        try {
                            isTarget = isCameraActivity(act);
                        } catch (Exception e) {
                            isTarget = act.getClass().getName().startsWith("com.android.camera");
                        }

                        if (isTarget) {
                            // フォーカス喪失時はスキップ
                            if ("onWindowFocusChanged".equals(methodName)) {
                                boolean hasFocus = (boolean) ((List<?>) chain.getArgs()).get(0);
                                if (!hasFocus) return chain.proceed();
                            }
                            // パッチ適用
                            applyWindowAndBufferFixes(act);
                        }                    }
                    return chain.proceed();
                });
            } catch (Throwable ignored) {
                // メソッドが見つからない場合は無視
            }
        }

        // 4. 既存のシステムフック群
        
        // CameraManager AvailabilityCallback ブロック
        try {
            Class<?> callbackClass = Class.forName("android.hardware.camera2.CameraManager$AvailabilityCallback", true, param.getClassLoader());
            Method onUnavailable = callbackClass.getDeclaredMethod("onCameraUnavailable", String.class);
            hook(onUnavailable).intercept(chain -> {
                String cameraId = (String) ((List<?>) chain.getArgs()).get(0);
                log(Log.INFO, TAG, "Blocked onCameraUnavailable for ID: " + cameraId);
                return null;
            });
        } catch (Throwable ignored) {}

        // BiometricManager 偽装
        try {
            Class<?> biometricClass = Class.forName("android.hardware.biometrics.BiometricManager", true, param.getClassLoader());
            hook(biometricClass.getDeclaredMethod("canAuthenticate", int.class)).intercept(chain -> 0);
        } catch (Throwable ignored) {}

        // KeyguardManager 偽装
        try {
            Class<?> kmClass = KeyguardManager.class;
            hook(kmClass.getDeclaredMethod("isKeyguardLocked")).intercept(chain -> false);
            hook(kmClass.getDeclaredMethod("isDeviceLocked")).intercept(chain -> false);
        } catch (Throwable ignored) {}

        // Intent 保護
        try {
            hook(Activity.class.getDeclaredMethod("setIntent", Intent.class)).intercept(chain -> {
                Intent intent = (Intent) ((List<?>) chain.getArgs()).get(0);
                if (intent != null) intent.setAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                return chain.proceed();
            });
        } catch (Throwable ignored) {}
    } // onPackageReady メソッドの閉じ括弧

    /**
     * カメラアプリの Activity かどうか判定するヘルパー
     */
    private boolean isCameraActivity(Activity act) {
        try {
            return "com.android.camera".equals(act.getPackageName());        } catch (Exception e) {
            return act.getClass().getName().startsWith("com.android.camera");
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        try {
            Class<?> gestureClass = Class.forName("com.android.server.GestureLauncherService", true, param.getClassLoader());
            Method handleCameraGesture = gestureClass.getDeclaredMethod("handleCameraGesture", boolean.class, int.class);

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

                    // LaunchGuard 突破のための Extra
                    intent.putExtra("android.intent.extra.CAMERA_OPEN_SOURCE", "lockscreen_gesture");
                    intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", true);
                    intent.putExtra("ShowCameraWhenLocked", true);
                    intent.putExtra("StartFromKeyguard", true);
                    
                    context.startActivity(intent);
                    return true; // boolean 戻り値
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Failed to launch", t);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "System hook skipped", t);
        }
    } // onSystemServerStarting メソッドの閉じ括弧

    private void applyWindowAndBufferFixes(Activity activity) {
        try {
            // 1. ロック画面表示設定
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);

            // 2. Intent への Extra 再注入
            Intent intent = activity.getIntent();            if (intent != null) {
                intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", true);
                intent.putExtra("ShowCameraWhenLocked", true);
                intent.putExtra("android.intent.extra.CAMERA_OPEN_SOURCE", "lockscreen_gesture");
                intent.putExtra("StartFromKeyguard", true);
            }

            // 3. Window 属性の操作
            Window window = activity.getWindow();
            if (window != null) {
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                          | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                          | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                          | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                          | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
                window.setAttributes(lp);

                // 4. バッファ問題回避
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                window.setFormat(PixelFormat.TRANSLUCENT);

                // 5. キーガード解除要求
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    KeyguardManager km = (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
                    if (km != null) {
                        km.requestDismissKeyguard(activity, null);
                    }
                }

                // 6. 親ウィンドウからの設定継承
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    activity.setInheritShowWhenLocked(true);
                }
            }

            // 7. 内部フィールドパッチ
            for (String fieldName : TARGET_BOOLEAN_FIELDS) {
                setFieldFast(activity, fieldName, true);
            }
            setFieldFast(activity, "mIsNormalIntent", false);
            setFieldFast(activity, "mShowEnteringAnimation", false);
            setFieldFast(activity, "mKeyguardStatus", 1);
            setFieldFast(activity, "mIsSecureCameraId", 0);
            
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "Fixes failed", t);
        }
    }
    /**
     * 高速フィールド探索（キャッシュ付き）
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
} // クラスの閉じ括弧
