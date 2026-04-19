package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.content.ContentResolver;
import android.content.ContentValues;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

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

    // === SessionManager (カメラアプリプロセス内で動作) ===
    public static class SessionManager {
        public static final AtomicBoolean isActive = new AtomicBoolean(false);
        public static final List<Uri> SESSION_URIS = new CopyOnWriteArrayList<>();

        public static void start() {
            isActive.set(true);
            SESSION_URIS.clear();
            Log.i(TAG, "Secure Camera Session Started");
        }

        public static void end() {
            isActive.set(false);
            SESSION_URIS.clear();
            Log.i(TAG, "Secure Camera Session Cleared");
        }

        public static void add(Uri uri) {
            // isActive のチェックを Atomic に
            if (isActive.get() && uri != null && !SESSION_URIS.contains(uri)) {
                SESSION_URIS.add(uri);
                Log.d(TAG, "Added to Session: " + uri + " | Total: " + SESSION_URIS.size());
            }
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String pkg = param.getPackageName();
        // LSPosed スコープに委ねるため、判定を緩やかに
        if (!isCameraPackage(pkg)) {
            return;
        }

        log(Log.INFO, TAG, "Targeting Camera App: " + pkg);

        // 0. View の透明化・非表示化を物理的に阻止
        try {
            hook(View.class.getDeclaredMethod("setVisibility", int.class)).intercept(chain -> {
                View view = (View) chain.getThisObject();
                if (isCameraContext(view.getContext())) {
                    List<Object> args = chain.getArgs();
                    int vis = (int) args.get(0);
                    
                    // DecorView の場合は常に VISIBLE を強制
                    if (isDecorView(view)) {
                        if (vis != View.VISIBLE) {
                            args.set(0, View.VISIBLE);
                        }
                    } else {
                        // 通常の View もカメラコンテキストなら VISIBLE を強制（簡易バックグラウンド対策）
                         if (vis != View.VISIBLE) {
                            args.set(0, View.VISIBLE);
                        }
                    }
                }
                return chain.proceed();
            });
            
            hook(View.class.getDeclaredMethod("setAlpha", float.class)).intercept(chain -> {
                View view = (View) chain.getThisObject();
                if (isDecorView(view) && isCameraContext(view.getContext())) {
                    List<Object> args = chain.getArgs();
                    float alpha = (float) args.get(0);
                    if (alpha < 1.0f) args.set(0, 1.0f);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "View hook failed", t);
        }

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
            hook(Activity.class.getDeclaredMethod("isResumed")).intercept(chain -> {
                if (isCameraActivity((Activity) chain.getThisObject())) return true;
                return (Boolean) chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 3. Intent の動的書き換え
        try {
            hook(Activity.class.getDeclaredMethod("getIntent")).intercept(chain -> {
                Intent intent = (Intent) chain.proceed();
                if (isCameraActivity((Activity) chain.getThisObject()) && intent != null) {
                    if (intent.getBooleanExtra("com.miui.camera.extra.START_BY_KEYGUARD", false)) {
                        intent.putExtra("is_secure_camera", true);
                        intent.putExtra("ShowCameraWhenLocked", true);
                    }
                }
                return intent;
            });
        } catch (Throwable ignored) {}

        // 4. Secure Gallery Redirect
        try {
            XposedModuleInterface.Interceptor startHook = chain -> {
                Context ctx = (Context) chain.getThisObject();
                Intent originalIntent = (Intent) chain.getArgs().get(0);
                
                // セッション中でなければ通常通り
                if (!SessionManager.isActive.get()) return chain.proceed();

                String action = originalIntent != null ? originalIntent.getAction() : null;
                if (Intent.ACTION_VIEW.equals(action) || (action != null && action.contains("REVIEW"))) {
                    Log.i(TAG, "Intercepting Gallery Launch -> Redirecting to SecureViewer");
                    
                    Intent secureIntent = new Intent();
                    secureIntent.setComponent(new ComponentName(
                            "com.github.droserasprout.lockscreencamera",
                            "com.github.droserasprout.lockscreencamera.SecureViewerActivity"
                    ));

                    ArrayList<Uri> uriList = new ArrayList<>(SessionManager.SESSION_URIS);
                    if (uriList.isEmpty() && originalIntent.getData() != null) {
                        uriList.add(originalIntent.getData());
                    }

                    secureIntent.setData(originalIntent.getData());
                    secureIntent.putParcelableArrayListExtra("session_photos_list", uriList);

                    if (!uriList.isEmpty()) {
                        ClipData clipData = new ClipData("session_photos", new String[]{"image/*"}, new ClipData.Item(uriList.get(0)));
                        for (int i = 1; i < uriList.size(); i++) clipData.addItem(new ClipData.Item(uriList.get(i)));
                        secureIntent.setClipData(clipData);
                    }

                    secureIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION 
                                        | Intent.FLAG_ACTIVITY_NEW_TASK 
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);

                    ctx.startActivity(secureIntent);
                    // 元のギャラリー起動をキャンセル
                    return null; 
                }
                return chain.proceed();
            };

            hook(Activity.class.getDeclaredMethod("startActivity", Intent.class)).intercept(startHook);
            hook(Activity.class.getDeclaredMethod("startActivityForResult", Intent.class, int.class)).intercept(startHook);
            hook(ContextWrapper.class.getDeclaredMethod("startActivity", Intent.class)).intercept(startHook);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook gallery redirect", t);
        }

        // 5. ライフサイクルフック
        String[] criticalMethods = {"onCreate", "onDestroy", "onWindowFocusChanged"};
        for (String mname : criticalMethods) {
            try {
                Method m = Activity.class.getDeclaredMethod(mname, 
                    "onCreate".equals(mname) ? new Class[]{Bundle.class} : 
                    "onWindowFocusChanged".equals(mname) ? new Class[]{boolean.class} : 
                    new Class[0]);

                final String methodName = mname;
                hook(m).intercept(chain -> {
                    Object thisObj = chain.getThisObject();
                    if (thisObj instanceof Activity) {
                        Activity act = (Activity) thisObj;
                        if (isCameraActivity(act)) {
                            if ("onDestroy".equals(methodName)) {
                                if (SessionManager.isActive.get()) {
                                    SessionManager.end();
                                }
                                return chain.proceed();
                            }

                            if ("onWindowFocusChanged".equals(methodName)) {
                                boolean hasFocus = (boolean) chain.getArgs().get(0);
                                if (!hasFocus) return chain.proceed();
                            }

                            if ("onCreate".equals(methodName)) {
                                Object res = chain.proceed();
                                Intent intent = act.getIntent();
                                boolean isLockscreenLaunch = intent != null && intent.getBooleanExtra("com.miui.camera.extra.START_BY_KEYGUARD", false);

                                if (isLockscreenLaunch) {
                                    SessionManager.start();
                                    registerScreenOffReceiver(act);
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

        // 6. ContentResolver フック（写真保存トラッキング）
        try {
            // insert の戻り値を正しく URI として扱う
            hook(ContentResolver.class.getDeclaredMethod("insert", Uri.class, ContentValues.class))
                .intercept(chain -> {
                    if (SessionManager.isActive.get()) {
                        Uri requestedUri = (Uri) chain.getArgs().get(0);
                        // 処理を実行し、結果（保存された URI）を取得
                        Object result = chain.proceed();
                        Uri finalUri = (result instanceof Uri) ? (Uri) result : requestedUri;
                        
                        if (finalUri != null) {
                            SessionManager.add(finalUri);
                        }
                        return result;
                    }
                    return chain.proceed();
                });
            
            hook(ContentResolver.class.getDeclaredMethod("update", Uri.class, ContentValues.class, String.class, String[].class))
                .intercept(chain -> {
                    if (SessionManager.isActive.get()) {
                        Uri uri = (Uri) chain.getArgs().get(0);
                        ContentValues values = (ContentValues) chain.getArgs().get(1);

                        if (uri != null && values != null) {
                            // IS_PENDING チェック (Android 10+)
                            boolean isFinished = true;
                            if (Build.VERSION.SDK_INT >= 29 && values.containsKey(MediaStore.MediaColumns.IS_PENDING)) {
                                isFinished = (Integer) values.get(MediaStore.MediaColumns.IS_PENDING) == 0;
                            }
                            if (isFinished) {
                                SessionManager.add(uri);
                            }
                        }
                    }
                    return chain.proceed();
                });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "ContentResolver hook failed", t);
        }

        // 7. その他システムフック群
        try {
            Class<?> callbackClass = Class.forName("android.hardware.camera2.CameraManager$AvailabilityCallback", true, param.getClassLoader());
            hook(callbackClass.getDeclaredMethod("onCameraUnavailable", String.class)).intercept(chain -> {
                log(Log.INFO, TAG, "Blocked onCameraUnavailable for ID: " + ((List<?>) chain.getArgs()).get(0));
                return null;
            });
        } catch (Throwable ignored) {}

    }

    // --- ヘルパーメソッド群 ---

    // LSPosed のスコープ設定を信頼し、広くカメラアプリを許可する
    private boolean isCameraPackage(String pkg) {
        if (pkg == null) return false;
        // 標準的なカメラパッケージ
        if (pkg.equals("com.android.camera") || pkg.equals("org.codeaurora.snapcam")) return true;
        // GCam 関連
        if (pkg.contains("GoogleCamera")) return true;
        // 緩やかなマッチング（システムアプリ以外で "camera" を含むもの）
        if (pkg.contains("camera") && !pkg.startsWith("com.android.") && !pkg.startsWith("com.google.")) {
             return true;
        }
        return false;
    }

    private boolean isCameraActivity(Activity act) {
        try { 
            return act != null && isCameraPackage(act.getPackageName()); 
        }
        catch (Exception e) { 
            return false; 
        }
    }

    private boolean isCameraContext(Context ctx) {
        if (ctx == null) return false;
        try { return isCameraPackage(ctx.getPackageName()); } 
        catch (Exception e) { return false; }
    }

    private boolean isDecorView(View v) {
        if (v == null) return false;
        return v.getClass().getName().endsWith("DecorView");
    }

    private void registerScreenOffReceiver(Activity act) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (SessionManager.isActive.get()) {
                    SessionManager.end();
                    act.finish();
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                act.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                act.registerReceiver(receiver, filter);
            }
        } catch (Exception e) { Log.w(TAG, "Receiver registration failed", e); }
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
                    KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                    boolean isLocked = (km != null && km.isKeyguardLocked());

                    Intent intent = new Intent(isLocked ? MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE 
                                                        : MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                    
                    PackageManager pm = context.getPackageManager();
                    ResolveInfo info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
                    if (info != null && !info.activityInfo.name.contains("Resolver")) {
                        intent.setComponent(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
                    }

                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    if (isLocked) {
                        intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", true);
                        intent.putExtra("is_secure_camera", true);
                        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                    }

                    ActivityOptions options = ActivityOptions.makeBasic();
                    if (Build.VERSION.SDK_INT >= 34) {
                        options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    }

                    context.startActivity(intent, options.toBundle());
                    return true;
                } catch (Throwable t) { return chain.proceed(); }
            });
        } catch (Throwable ignored) {}
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
                
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                              | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                              | WindowManager.LayoutParams.FLAG_FULLSCREEN
                              | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                              | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                              | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            }
        } catch (Throwable ignored) {}
    }
}
