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
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;        }

        log(Log.INFO, TAG, "Targeting com.android.camera (LaunchGuard Bypass)");

        // 1. ライフサイクルフックの拡張（attachBaseContext を追加）
        String[] criticalMethods = {"attachBaseContext", "onCreate", "onStart", "onResume", "onWindowFocusChanged"};

        for (String mname : criticalMethods) {
            try {
                Method m;
                // attachBaseContext は ContextWrapper で定義されている
                if ("attachBaseContext".equals(mname)) {
                    m = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
                } else if ("onCreate".equals(mname)) {
                    m = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
                } else if ("onWindowFocusChanged".equals(mname)) {
                    m = Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class);
                } else {
                    m = Activity.class.getDeclaredMethod(mname);
                }

                hook(m).intercept(chain -> {
                    Object thisObj = chain.getThisObject();
                    if (thisObj instanceof Activity) {
                        Activity act = (Activity) thisObj;
                        
                        // パッケージ名の判定（初期化段階では安全のため try-catch で囲む）
                        boolean isTarget = false;
                        try {
                            isTarget = act.getPackageName().equals("com.android.camera");
                        } catch (Exception e) {
                            if (act.getClass().getName().startsWith("com.android.camera")) {
                                isTarget = true;
                            }
                        }

                        if (isTarget) {
                            // フォーカス喪失時はスキップ
                            if ("onWindowFocusChanged".equals(mname)) {
                                boolean hasFocus = (boolean) ((List<?>) chain.getArgs()).get(0);
                                if (!hasFocus) return chain.proceed(); 
                            }
                            // パッチ適用
                            applyWindowAndBufferFixes(act);
                        }
                    }
                    return chain.proceed();
                });
            } catch (Throwable ignored) {}
        }
        // 2. 既存のシステムフック群（NPE Fix, Keyguard Spoofing, etc）
        
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

        // finish() ブロック（LaunchGuard による自爆を防ぐ）
        try {
            hook(Activity.class.getDeclaredMethod("finish")).intercept(chain -> {
                log(Log.ERROR, TAG, "Finish Attempted (Blocked) by: " + Log.getStackTraceString(new Throwable()));
                return null;
            });
        } catch (Throwable ignored) {}
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

                    // 【重要】MIUI LaunchGuard を突破するための Extra 注入
                    intent.putExtra("android.intent.extra.CAMERA_OPEN_SOURCE", "lockscreen_gesture");
                    intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", true);
                    intent.putExtra("ShowCameraWhenLocked", true);
                    intent.putExtra("StartFromKeyguard", true);
                    
                    context.startActivity(intent);
                    
                    // NPE 回避のため boolean を返す
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
            // 1. ロック画面表示設定
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);

            // 2. Intent への Extra 再注入（LaunchGuard チェック用）
            Intent intent = activity.getIntent();
            if (intent != null) {
                intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", true);
                intent.putExtra("ShowCameraWhenLocked", true);
                intent.putExtra("android.intent.extra.CAMERA_OPEN_SOURCE", "lockscreen_gesture");
                intent.putExtra("StartFromKeyguard", true);
            }

            Window window = activity.getWindow();            if (window != null) {
                // 3. Window 属性の直接操作
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                          | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                          | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                          | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                          | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON; // アクティブ化フラグ
                window.setAttributes(lp);

                // 4. バッファ問題回避
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                window.setFormat(PixelFormat.TRANSLUCENT);

                // 5. キーガード解除要求（システムへのシグナル）
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

    private void setFieldFast(Object obj, String fieldName, Object value) {
        try {
            Class<?> current = obj.getClass();
            String cacheKey = current.getName() + ":" + fieldName;
            
            Field f = fieldCache.computeIfAbsent(cacheKey, k -> {
                Class<?> c = current;
                while (c != null && !c.getName().equals("android.app.Activity")) {                    try {
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
