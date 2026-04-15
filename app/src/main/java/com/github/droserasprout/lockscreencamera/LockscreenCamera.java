package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    public void onPackageReady(@NonNull PackageReadyParam param) {        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting com.android.camera (Z-Order / UI Visibility Force)");

        // 1. Keyguard の解除要求（PIN 画面表示）を完全にブロック
        try {
            Method dismissMethod = KeyguardManager.class.getDeclaredMethod(
                    "requestDismissKeyguard", Activity.class, KeyguardManager.KeyguardDismissCallback.class);
            hook(dismissMethod).intercept(chain -> {
                log(Log.WARN, TAG, "BLOCKED: requestDismissKeyguard (Preventing PIN screen)");
                return null;
            });
        } catch (Throwable ignored) {}

        // 2. Activity Visibility Spoofing (Surface 生存 & 最前面報告)
        try {
            hook(Activity.class.getDeclaredMethod("hasWindowFocus")).intercept(chain -> {
                if (isCameraActivity((Activity) chain.getThisObject())) return true;
                return (Boolean) chain.proceed();
            });
            hook(Activity.class.getDeclaredMethod("isResumed")).intercept(chain -> {
                if (isCameraActivity((Activity) chain.getThisObject())) return true;
                return (Boolean) chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 3. Aggressive Finish Blocking (自爆処理の完全阻止)
        try {
            hook(Activity.class.getDeclaredMethod("finish")).intercept(chain -> {
                if (isCameraActivity((Activity) chain.getThisObject())) {
                    log(Log.WARN, TAG, "BLOCKED finish() to prevent NativeWindow death.");
                    return null;
                }
                return chain.proceed();
            });
            hook(Activity.class.getDeclaredMethod("finishAfterTransition")).intercept(chain -> {
                if (isCameraActivity((Activity) chain.getThisObject())) {
                    log(Log.WARN, TAG, "BLOCKED finishAfterTransition().");
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 4. 信頼性偽装 (Referrer)
        try {
            hook(Activity.class.getDeclaredMethod("getReferrer")).intercept(chain -> {
                if (isCameraActivity((Activity) chain.getThisObject()))                    return Uri.parse("android-app://android");
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 5. Intent の動的書き換え
        try {
            hook(Activity.class.getDeclaredMethod("getIntent")).intercept(chain -> {
                Intent intent = (Intent) chain.proceed();
                if (isCameraActivity((Activity) chain.getThisObject()) && intent != null) {
                    if (!intent.getBooleanExtra("com.miui.camera.extra.START_BY_KEYGUARD", false)) {
                        intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", true);
                        intent.putExtra("is_secure_camera", true);
                        intent.putExtra("ShowCameraWhenLocked", true);
                        intent.putExtra("StartFromKeyguard", true);
                    }
                }
                return intent;
            });
        } catch (Throwable ignored) {}

        // 6. SurfaceView/View 非表示化を阻止
        try {
            hook(View.class.getMethod("setVisibility", int.class)).intercept(chain -> {
                View v = (View) chain.getThisObject();
                if (isCameraContext(v.getContext())) {
                    List<Object> args = chain.getArgs();
                    int vis = (int) args.get(0);
                    if (vis != View.VISIBLE) {
                        args.set(0, View.VISIBLE);
                        log(Log.DEBUG, TAG, "Prevented View from being hidden");
                    }
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 7. ライフサイクルフック（UI強制表示パッチ）
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
                    m = Activity.class.getDeclaredMethod(mname);                }

                final String methodName = mname;
                hook(m).intercept(chain -> {
                    Object thisObj = chain.getThisObject();
                    if (thisObj instanceof Activity) {
                        Activity act = (Activity) thisObj;
                        boolean isTarget = isCameraActivity(act);
                        
                        if (isTarget) {
                            if ("onWindowFocusChanged".equals(methodName)) {
                                boolean hasFocus = (boolean) ((List<?>) chain.getArgs()).get(0);
                                if (!hasFocus) return chain.proceed();
                            }
                            
                            // onCreate の場合は MIUI の初期化を先に通す
                            if ("onCreate".equals(methodName)) {
                                Object res = chain.proceed();
                                applyWindowAndBufferFixes(act);
                                return res;
                            }
                            applyWindowAndBufferFixes(act);
                        }
                    }
                    return chain.proceed();
                });
            } catch (Throwable ignored) {}
        }

        // 8. その他システムフック群
        try {
            Class<?> callbackClass = Class.forName("android.hardware.camera2.CameraManager$AvailabilityCallback", true, param.getClassLoader());
            hook(callbackClass.getDeclaredMethod("onCameraUnavailable", String.class)).intercept(chain -> {
                log(Log.INFO, TAG, "Blocked onCameraUnavailable for ID: " + ((List<?>) chain.getArgs()).get(0));
                return null;
            });
        } catch (Throwable ignored) {}

        try {
            Class<?> biometricClass = Class.forName("android.hardware.biometrics.BiometricManager", true, param.getClassLoader());
            hook(biometricClass.getDeclaredMethod("canAuthenticate", int.class)).intercept(chain -> 0);
        } catch (Throwable ignored) {}
        
        try {
            hook(Activity.class.getDeclaredMethod("setIntent", Intent.class)).intercept(chain -> {
                Intent intent = (Intent) ((List<?>) chain.getArgs()).get(0);
                if (isCameraActivity((Activity) chain.getThisObject()) && intent != null) {
                    intent.setAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                }
                return chain.proceed();            });
        } catch (Throwable ignored) {}
    }

    private boolean isCameraActivity(Activity act) {
        try {
            return act != null && "com.android.camera".equals(act.getPackageName());
        } catch (Exception e) {
            try { return act.getClass().getName().startsWith("com.android.camera"); } 
            catch (Exception e2) { return false; }
        }
    }

    private boolean isCameraContext(Context ctx) {
        if (ctx == null) return false;
        try { return "com.android.camera".equals(ctx.getPackageName()); } 
        catch (Exception e) { return ctx.getClass().getName().contains("camera"); }
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
                    intent.setPackage("com.android.camera");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK 
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS 
                            | Intent.FLAG_ACTIVITY_NO_USER_ACTION);

                    intent.putExtra("is_secure_camera", true);
                    intent.putExtra("com.miui.camera.extra.IS_SECURE_CAMERA", true);
                    
                    context.startActivity(intent);
                    return true;
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Failed to launch", t);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "System hook skipped", t);
        }    }

    /**
     * 【UI表示突破版】Window 属性の操作と DecorView 強制表示
     */
    private void applyWindowAndBufferFixes(Activity activity) {
        try {
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setInheritShowWhenLocked(true);
            }

            Window window = activity.getWindow();
            if (window != null) {
                // 1. Secure/Buffur 問題回避
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                window.setFormat(PixelFormat.TRANSLUCENT);

                // 2. LayoutParams 直接操作（Z-Order / Cutout / 可視性固定）
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                          | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                          | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                          | WindowManager.LayoutParams.FLAG_FULLSCREEN // システムUIより前に強制
                          | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
                
                // PIN 画面トリガーを明確に無効化
                lp.flags &= ~WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;

                // ノッチ/パンチホール領域への描画許可（UI欠け防止）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }
                window.setAttributes(lp);
                window.addFlags(lp.flags);

                // 3. DecorView（画面のルート）の可視性・Alpha 強制
                View decorView = window.getDecorView();
                if (decorView != null) {
                    decorView.setAlpha(1.0f);
                    decorView.setVisibility(View.VISIBLE);
                    // フォーカス要求（念のため）
                    decorView.requestFocus();
                }
            }

            // 4. 内部フィールドパッチ
            for (String fieldName : TARGET_BOOLEAN_FIELDS) {
                setFieldFast(activity, fieldName, true);            }
            setFieldFast(activity, "mIsNormalIntent", false);
            setFieldFast(activity, "mShowEnteringAnimation", false);
            setFieldFast(activity, "mKeyguardStatus", 1);
            setFieldFast(activity, "mIsSecureCameraId", 0);
            
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "UI Fixes failed: " + t.toString());
        }
    }

    private void setFieldFast(Object obj, String fieldName, Object value) {
        try {
            Class<?> current = obj.getClass();
            String cacheKey = current.getName() + ":" + fieldName;
            Field f = fieldCache.get(cacheKey);

            if (f == null && !fieldCache.containsKey(cacheKey)) {
                while (current != null && !current.getName().equals("android.app.Activity")) {
                    try {
                        f = current.getDeclaredField(fieldName);
                        f.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException e) {
                        current = current.getSuperclass();
                    }
                }
                fieldCache.put(cacheKey, f);
            }

            if (f != null) {
                f.set(obj, value);
            }
        } catch (Throwable ignored) {}
    }
}
