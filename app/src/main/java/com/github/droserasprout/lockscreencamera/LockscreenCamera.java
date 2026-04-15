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
    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting com.android.camera (No-PIN / Passive Overlay Mode)");

        // 1. Keyguard の解除要求（PIN 画面表示）を完全にブロック
        try {
            Method dismissMethod = KeyguardManager.class.getDeclaredMethod(
                    "requestDismissKeyguard", Activity.class, KeyguardManager.KeyguardDismissCallback.class);
            hook(dismissMethod).intercept(chain -> {
                log(Log.WARN, TAG, "BLOCKED: requestDismissKeyguard (Preventing PIN screen)");
                // 何もせず null を返すことで、PIN 入力のトリガーを握りつぶす
                return null;
            });
        } catch (Throwable ignored) {}

        // 2. Activity Visibility Spoofing (Surface 生存のための最重要ハック)
        // MIUI カメラに「お前は最前面で見えている」と嘘をつくことで、リソース解放を防ぐ
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
                if (isCameraActivity((Activity) chain.getThisObject()))
                    return Uri.parse("android-app://android");
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

        // 6. SurfaceView/View 非表示化を阻止（NativeWindow 死亡防止）
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

        // 7. ライフサイクルフック（Window フラグ設定など）
        String[] criticalMethods = {"attachBaseContext", "onCreate", "onStart", "onResume", "onWindowFocusChanged"};
        for (String mname : criticalMethods) {
            try {
                Method m;
                if ("attachBaseContext".equals(mname)) {
                    m = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
                } else if ("onCreate".equals(mname)) {
                    m = Activity.class.getDeclaredMethod("onCreate", Bundle.class);                } else if ("onWindowFocusChanged".equals(mname)) {
                    m = Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class);
                } else {
                    m = Activity.class.getDeclaredMethod(mname);
                }

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
                            applyWindowAndBufferFixes(act);
                        }
                    }
                    return chain.proceed();
                });
            } catch (Throwable ignored) {}
        }

        // 8. その他システムフック群
        
        // CameraManager AvailabilityCallback ブロック
        try {
            Class<?> callbackClass = Class.forName("android.hardware.camera2.CameraManager$AvailabilityCallback", true, param.getClassLoader());
            Method onUnavailable = callbackClass.getDeclaredMethod("onCameraUnavailable", String.class);
            hook(onUnavailable).intercept(chain -> {
                log(Log.INFO, TAG, "Blocked onCameraUnavailable for ID: " + ((List<?>) chain.getArgs()).get(0));
                return null;
            });
        } catch (Throwable ignored) {}

        // BiometricManager 偽装
        try {
            Class<?> biometricClass = Class.forName("android.hardware.biometrics.BiometricManager", true, param.getClassLoader());
            hook(biometricClass.getDeclaredMethod("canAuthenticate", int.class)).intercept(chain -> 0);
        } catch (Throwable ignored) {}
        
        // setIntent フック
        try {
            hook(Activity.class.getDeclaredMethod("setIntent", Intent.class)).intercept(chain -> {
                Intent intent = (Intent) ((List<?>) chain.getArgs()).get(0);
                if (isCameraActivity((Activity) chain.getThisObject()) && intent != null) {
                    intent.setAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}
    } // onPackageReady end

    /**
     * カメラアプリの Activity かどうか判定するヘルパー
     */
    private boolean isCameraActivity(Activity act) {
        try {
            if (act == null) return false;
            String pkg = act.getPackageName();
            return pkg != null && pkg.equals("com.android.camera");
        } catch (Exception e) {
            try { return act.getClass().getName().startsWith("com.android.camera"); } 
            catch (Exception e2) { return false; }
        }
    }

    /**
     * コンテキストがカメラアプリのものか判定するヘルパー
     */
    private boolean isCameraContext(Context ctx) {
        if (ctx == null) return false;
        try {
            return "com.android.camera".equals(ctx.getPackageName());
        } catch (Exception e) {
            return ctx.getClass().getName().contains("camera");
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

                    // 【重要】SECURE Intent を使用し、システムに PIN 不要であることを伝える
                    Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    intent.setPackage("com.android.camera"); // パッケージ指定で起動を確実に
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                            | Intent.FLAG_ACTIVITY_SHOW_WHEN_LOCKED // ロック画面上に表示
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK 
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS                             | Intent.FLAG_ACTIVITY_NO_USER_ACTION);

                    // MIUI がセキュア起動と認識するためのフラグ
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
        }
    }

    /**
     * Window 属性の操作と NativeWindow 維持処理
     */
    private void applyWindowAndBufferFixes(Activity activity) {
        try {
            // 1. ロック画面の上に表示（解除は要求しない）
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);

            Window window = activity.getWindow();
            if (window != null) {
                // 2. バッファ問題回避
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                window.setFormat(PixelFormat.TRANSLUCENT);

                WindowManager.LayoutParams lp = window.getAttributes();
                // 【重要】FLAG_DISMISS_KEYGUARD は外す！
                // これが PIN 画面を呼び出すトリガーになるため。
                // SHOW_WHEN_LOCKED だけで「透過して表示」し、ロックは維持する。
                lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                          | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                          | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
                
                // 明示的に DISMISS フラグをオフにする
                lp.flags &= ~WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
                
                window.setAttributes(lp);

                // 3. キーガード解除要求の無効化（保険）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // KeyguardManager 自体のフックで止めるが、念のため
                }                
                // 4. 親ウィンドウからの設定継承
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    activity.setInheritShowWhenLocked(true);
                }
            }

            // 5. 内部フィールドパッチ
            for (String fieldName : TARGET_BOOLEAN_FIELDS) {
                setFieldFast(activity, fieldName, true);
            }
            setFieldFast(activity, "mIsNormalIntent", false);
            setFieldFast(activity, "mShowEnteringAnimation", false);
            setFieldFast(activity, "mKeyguardStatus", 1);
            setFieldFast(activity, "mIsSecureCameraId", 0);
            
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "Fixes failed: " + t.toString());
        }
    }

    /**
     * 高速フィールド探索（キャッシュ付き・Lambda不使用版）
     */
    private void setFieldFast(Object obj, String fieldName, Object value) {
        try {
            Class<?> current = obj.getClass();
            String cacheKey = current.getName() + ":" + fieldName;
            Field f = fieldCache.get(cacheKey);

            // キャッシュにない場合のみ探索
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
                // 見つからなくても null をキャッシュして再探索を防ぐ
                fieldCache.put(cacheKey, f);
            }

            if (f != null) {
                f.set(obj, value);
            }
        } catch (Throwable ignored) {}
    }}
