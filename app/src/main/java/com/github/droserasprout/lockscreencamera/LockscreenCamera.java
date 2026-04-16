package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
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

    public LockscreenCamera() {        super();
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting com.android.camera (Screen-Off Auto-Finish Integration)");

        // 0. DecorView の透明化・非表示化を物理的に阻止
        try {
            hook(View.class.getDeclaredMethod("setAlpha", float.class)).intercept(chain -> {
                View view = (View) chain.getThisObject();
                if (isDecorView(view) && isCameraContext(view.getContext())) {
                    List<Object> args = chain.getArgs();
                    float alpha = (float) args.get(0);
                    if (alpha < 1.0f) args.set(0, 1.0f);
                }
                return chain.proceed();
            });
            hook(View.class.getDeclaredMethod("setVisibility", int.class)).intercept(chain -> {
                View view = (View) chain.getThisObject();
                if (isDecorView(view) && isCameraContext(view.getContext())) {
                    List<Object> args = chain.getArgs();
                    int vis = (int) args.get(0);
                    if (vis != View.VISIBLE) args.set(0, View.VISIBLE);
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 1. Keyguard の解除要求（PIN 画面表示）を完全にブロック
        try {
            Method dismissMethod = KeyguardManager.class.getDeclaredMethod(
                    "requestDismissKeyguard", Activity.class, KeyguardManager.KeyguardDismissCallback.class);
            hook(dismissMethod).intercept(chain -> {
                log(Log.WARN, TAG, "BLOCKED: requestDismissKeyguard (Preventing PIN screen)");
                return null;
            });
        } catch (Throwable ignored) {}

        // 2. Activity Visibility Spoofing
        try {
            hook(Activity.class.getDeclaredMethod("hasWindowFocus")).intercept(chain -> {
                if (isCameraActivity((Activity) chain.getThisObject())) return true;
                return (Boolean) chain.proceed();
            });
            hook(Activity.class.getDeclaredMethod("isResumed")).intercept(chain -> {                if (isCameraActivity((Activity) chain.getThisObject())) return true;
                return (Boolean) chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 3. 【削除】Finish Blocking Hook
        // クラッシュが解消し、ユーザーが手動で閉じられるようにするため削除。

        // 4. 信頼性偽装 (Referrer)
        try {
            hook(Activity.class.getDeclaredMethod("getReferrer")).intercept(chain -> {
                if (isCameraActivity((Activity) chain.getThisObject()))
                    return Uri.parse("android-app://android");
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 5. Intent の動的書き換え (ロック画面起動フラグの注入)
        try {
            hook(Activity.class.getDeclaredMethod("getIntent")).intercept(chain -> {
                Intent intent = (Intent) chain.proceed();
                if (isCameraActivity((Activity) chain.getThisObject()) && intent != null) {
                    // セキュアインテントの場合のみ、ロック画面起動フラグを付与
                    if (MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE.equals(intent.getAction())) {
                        if (!intent.getBooleanExtra("com.miui.camera.extra.START_BY_KEYGUARD", false)) {
                            intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", true);
                            intent.putExtra("is_secure_camera", true);
                            intent.putExtra("ShowCameraWhenLocked", true);
                            intent.putExtra("StartFromKeyguard", true);
                        }
                    }
                }
                return intent;
            });
        } catch (Throwable ignored) {}

        // 6. SurfaceView/View 非表示化を阻止
        try {
            hook(View.class.getMethod("setVisibility", int.class)).intercept(chain -> {
                View v = (View) chain.getThisObject();
                if (isCameraContext(v.getContext()) && !isDecorView(v)) {
                    List<Object> args = chain.getArgs();
                    int vis = (int) args.get(0);
                    if (vis != View.VISIBLE) args.set(0, View.VISIBLE);
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 7. ライフサイクルフック（+ 自動終了機能の実装）        String[] criticalMethods = {"attachBaseContext", "onCreate", "onStart", "onResume", "onWindowFocusChanged"};
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
                            
                            if ("onCreate".equals(methodName)) {
                                Object res = chain.proceed();
                                
                                // ロック画面からの起動判定と画面オフ自動終了の設定
                                boolean isLockscreenLaunch = false;
                                try {
                                    // getIntent フックにより、セキュアインテントにはフラグが付与されているはず
                                    Intent intent = act.getIntent();
                                    if (intent != null) {
                                        isLockscreenLaunch = intent.getBooleanExtra("com.miui.camera.extra.START_BY_KEYGUARD", false);
                                    }
                                } catch (Exception e) {}

                                if (isLockscreenLaunch) {
                                    log(Log.INFO, TAG, "Lockscreen launch detected. Registering screen-off auto-finish.");
                                    IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
                                    
                                    BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
                                        @Override
                                        public void onReceive(Context context, Intent i) {
                                            try {
                                                // 画面オフ時の状態確認
                                                // ロック画面起動時かつ、現在もロック状態（入力制限中）であれば終了                                                KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                                                boolean shouldFinish = true;
                                                
                                                // isKeyguardLocked は偽装されているため使えない。
                                                // inKeyguardRestrictedInputMode を代用に試みる
                                                // true = ロック中（入力制限あり） / false = 解除済み（入力可能）
                                                if (km != null) {
                                                    try {
                                                        if (!km.inKeyguardRestrictedInputMode()) {
                                                            // ロック解除済みなら終了しない（撮影継続）
                                                            shouldFinish = false;
                                                            log(Log.DEBUG, TAG, "Device unlocked, keeping camera open.");
                                                        }
                                                    } catch (Exception e) {
                                                        // Method error -> assume finish to be safe
                                                    }
                                                }
                                                
                                                if (shouldFinish) {
                                                    log(Log.INFO, TAG, "Screen off on lockscreen. Finishing activity.");
                                                    act.finish();
                                                }
                                            } catch (Exception e) {
                                                log(Log.ERROR, TAG, "Error in screen-off receiver", e);
                                            }
                                        }
                                    };
                                    
                                    try {
                                        if (Build.VERSION.SDK_INT >= 33) {
                                            // Android 13+ での登録クラッシュ回避
                                            act.registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                                        } else {
                                            act.registerReceiver(screenOffReceiver, filter);
                                        }
                                    } catch (Exception e) {
                                        log(Log.WARN, TAG, "Failed to register receiver: " + e.getMessage());
                                    }
                                }

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
                return chain.proceed();
            });
        } catch (Throwable ignored) {}
    }

    // ヘルパーメソッド群
    private boolean isCameraActivity(Activity act) {
        try { return act != null && "com.android.camera".equals(act.getPackageName()); } 
        catch (Exception e) { try { return act.getClass().getName().startsWith("com.android.camera"); } catch (Exception e2) { return false; } }
    }

    private boolean isCameraContext(Context ctx) {
        if (ctx == null) return false;
        try { return "com.android.camera".equals(ctx.getPackageName()); } 
        catch (Exception e) { return ctx.getClass().getName().contains("camera"); }
    }

    private boolean isDecorView(View v) {
        if (v == null) return false;
        return v.getClass().getName().endsWith("DecorView");
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
                    intent.setComponent(new android.content.ComponentName("com.android.camera", "com.android.camera.Camera"));
                    
                    int flags = 0x00040000 | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
                    intent.addFlags(flags);

                    intent.putExtra("is_secure_camera", true);
                    intent.putExtra("com.miui.camera.extra.IS_SECURE_CAMERA", true);
                    intent.putExtra("android.intent.extra.CAMERA_OPEN_ONLY", true);
                    intent.putExtra("StartActivityWhenLocked", true);
                    intent.putExtra("com.android.systemui.camera_launch_source", "lockscreen_affordance");

                    ActivityOptions options = ActivityOptions.makeBasic();
                    if (Build.VERSION.SDK_INT >= 34) {
                        options.setPendingIntentBackgroundActivityStartMode(2);
                    }
                    
                    context.startActivity(intent, options.toBundle());
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
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setInheritShowWhenLocked(true);
            }

            Window window = activity.getWindow();
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                window.setFormat(PixelFormat.TRANSLUCENT);

                WindowManager.LayoutParams lp = window.getAttributes();
                lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                          | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                          | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON                          | WindowManager.LayoutParams.FLAG_FULLSCREEN 
                          | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
                
                lp.flags &= ~WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }
                window.setAttributes(lp);
                window.addFlags(lp.flags);

                View decorView = window.getDecorView();
                if (decorView != null) {
                    decorView.setAlpha(1.0f);
                    decorView.setVisibility(View.VISIBLE);
                    decorView.requestFocus();
                }
            }

            for (String fieldName : TARGET_BOOLEAN_FIELDS) {
                setFieldFast(activity, fieldName, true);
            }
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
            if (f != null) f.set(obj, value);
        } catch (Throwable ignored) {}
    }
}
