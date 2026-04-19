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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        "mIsCameraApp", "mPrivacyAuthorized", "mIsForeground",
        "mKeyguardStatus", "mIsSecureCameraId"
    };

    public LockscreenCamera() {
        super();
    }

    // MIUI/HyperOS カメラのパッケージ名バリエーション
    private static final Set<String> MIUI_CAMERA_PACKAGES = new HashSet<>(Arrays.asList(
        "com.android.camera",
        "com.miui.camera",
        "com.xiaomi.camera"
    ));

    /**
     * MIUI 系カメラかどうかを多段的に判定する
     */
    private boolean isMIUICamera(Activity act) {
        if (act == null) return false;
        
        String pkg = act.getPackageName();
        if (pkg != null && MIUI_CAMERA_PACKAGES.contains(pkg)) return true;
        
        // パッケージ名が標準的でない場合、主要 Activity のクラス名でパターンマッチ
        String clsName = act.getClass().getName();
        if (clsName.contains("com.android.camera") || clsName.contains("com.miui.camera")) {
            return true;
        }
        
        // 最終フォールバック：Xiaomi 端末かつカメラパッケージであれば適用（移植版向け）
        try {
            if ("xiaomi".equalsIgnoreCase(Build.MANUFACTURER) && isCameraPackage(pkg)) {
                return true;
            }
        } catch (Exception ignored) {}
        
        return false;
    }

    // === SessionManager ===
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
            if (isActive.get() && uri != null && !SESSION_URIS.contains(uri)) {
                SESSION_URIS.add(uri);
                Log.d(TAG, "Added to Session: " + uri + " | Total: " + SESSION_URIS.size());
            }
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!isCameraPackage(pkg)) return;

        log(Log.INFO, TAG, "Targeting Camera App: " + pkg);

        // 1. Keyguard 解除要求ブロック
        try {
            Method dismissMethod = KeyguardManager.class.getDeclaredMethod(
                    "requestDismissKeyguard", Activity.class, KeyguardManager.KeyguardDismissCallback.class);
            hook(dismissMethod).intercept(chain -> {
                log(Log.WARN, TAG, "BLOCKED: requestDismissKeyguard");
                return null;
            });
        } catch (Throwable ignored) {}

        // 2. Visibility Spoofing
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

        // 3. Intent 書き換え
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

        // 4. Gallery Redirect (Smart Bypass 維持)
        try {
            hook(Activity.class.getDeclaredMethod("startActivity", Intent.class)).intercept(chain -> {
                handleGalleryRedirect((Context) chain.getThisObject(), (Intent) chain.getArgs().get(0));
                return chain.proceed();
            });
            hook(Activity.class.getDeclaredMethod("startActivityForResult", Intent.class, int.class)).intercept(chain -> {
                handleGalleryRedirect((Context) chain.getThisObject(), (Intent) chain.getArgs().get(0));
                return chain.proceed();
            });
            hook(ContextWrapper.class.getDeclaredMethod("startActivity", Intent.class)).intercept(chain -> {
                handleGalleryRedirect((Context) chain.getThisObject(), (Intent) chain.getArgs().get(0));
                return chain.proceed();
            });
        } catch (Throwable t) { log(Log.ERROR, TAG, "Gallery redirect hook failed", t); }

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
                        boolean isMIUI = "com.android.camera".equals(act.getPackageName());
                        boolean isTarget = isCameraActivity(act);

                        if (isTarget) {
                            if ("onDestroy".equals(methodName)) {
                                if (SessionManager.isActive.get()) SessionManager.end();
                                return chain.proceed();
                            }

                            if ("onWindowFocusChanged".equals(methodName)) {
                                return chain.proceed();
                            }

                            if ("onCreate".equals(methodName)) {
                                Intent intent = act.getIntent();
                                boolean isLockscreenLaunch = intent != null && intent.getBooleanExtra("com.miui.camera.extra.START_BY_KEYGUARD", false);

                                // 【MIUI 専用パッチ】Intent 書き換え & フィールド強制書き換え
                                if ("onCreate".equals(methodName)) {
                                    Intent intent = act.getIntent();
                                    boolean isLockscreenLaunch = intent != null && intent.getBooleanExtra("com.miui.camera.extra.START_BY_KEYGUARD", false);
                                    
                                    boolean isMIUI = isMIUICamera(act);
                                
                                    if (isLockscreenLaunch && isMIUI) {
                                        if (intent != null) {
                                            intent.putExtra("is_secure_camera", true);
                                            intent.putExtra("ShowCameraWhenLocked", true);
                                            act.setIntent(intent);
                                        }
                                
                                        for (String fieldName : TARGET_BOOLEAN_FIELDS) {
                                            try {
                                                Field f = findFieldRecursive(act.getClass(), fieldName);
                                                if (f != null) {
                                                    f.setAccessible(true);
                                                    if (f.getType() == boolean.class) f.setBoolean(act, true);
                                                    else if (f.getType() == int.class) f.setInt(act, 1);
                                                    else f.set(act, Boolean.TRUE);
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                    }

                                Object res = chain.proceed(); // super.onCreate 実行

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

        // 6. ContentResolver フック (セッション追跡)
        try {
            hook(ContentResolver.class.getDeclaredMethod("insert", Uri.class, ContentValues.class))
                .intercept(chain -> {
                    if (SessionManager.isActive.get()) {
                        Uri requestedUri = (Uri) chain.getArgs().get(0);
                        Object result = chain.proceed();
                        Uri finalUri = (result instanceof Uri) ? (Uri) result : requestedUri;
                        if (finalUri != null) SessionManager.add(finalUri);
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
                            boolean isFinished = true;
                            if (Build.VERSION.SDK_INT >= 29 && values.containsKey(MediaStore.MediaColumns.IS_PENDING)) {
                                isFinished = (Integer) values.get(MediaStore.MediaColumns.IS_PENDING) == 0;
                            }
                            if (isFinished) SessionManager.add(uri);
                        }
                    }
                    return chain.proceed();
                });
        } catch (Throwable t) { log(Log.ERROR, TAG, "ContentResolver hook failed", t); }
    }

    // --- ヘルパーメソッド群 ---

    private boolean isCameraPackage(String pkg) {
        if (pkg == null) return false;
        if (pkg.equals("com.android.camera") || pkg.equals("org.codeaurora.snapcam")) return true;
        if (pkg.contains("GoogleCamera")) return true;
        if (pkg.contains("camera") && !pkg.startsWith("com.android.") && !pkg.startsWith("com.google.")) return true;
        return false;
    }

    private boolean isCameraActivity(Activity act) {
        try { return act != null && isCameraPackage(act.getPackageName()); } 
        catch (Exception e) { return false; }
    }

    private boolean isCameraContext(Context ctx) {
        if (ctx == null) return false;
        try { return isCameraPackage(ctx.getPackageName()); } 
        catch (Exception e) { return false; }
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
        } catch (Exception e) { Log.w(TAG, "Receiver reg failed", e); }
    }

    private void handleGalleryRedirect(Context ctx, Intent intent) {
        if (ctx == null || intent == null || intent.getAction() == null) return;
        if (!SessionManager.isActive.get()) return;

        try { if (!isCameraPackage(ctx.getPackageName())) return; } catch (Exception e) { return; }

        if (intent.getData() != null && intent.hasExtra("is_secure_camera") && !"com.android.camera".equals(ctx.getPackageName())) {
            Log.d(TAG, "Smart Bypass: Secure intent detected in 3rd party camera.");
            return;
        }

        String action = intent.getAction();
        boolean isGallery = Intent.ACTION_VIEW.equals(action) || action.contains("REVIEW");

        if (isGallery) {
            Log.i(TAG, "Redirecting to SecureViewer");
            ArrayList<Uri> uriList = new ArrayList<>(SessionManager.SESSION_URIS);
            if (uriList.isEmpty() && intent.getData() != null) uriList.add(intent.getData());

            intent.setComponent(new ComponentName("com.github.droserasprout.lockscreencamera", "com.github.droserasprout.lockscreencamera.SecureViewerActivity"));
            intent.setPackage(null);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) intent.setSelector(null);

            if (!uriList.isEmpty()) {
                ClipData clipData = new ClipData("session_photos", new String[]{"image/*"}, new ClipData.Item(uriList.get(0)));
                for (int i = 1; i < uriList.size(); i++) clipData.addItem(new ClipData.Item(uriList.get(i)));
                intent.setClipData(clipData);
            }
            intent.putParcelableArrayListExtra("session_photos_list", uriList);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.addFlags(0x00080000 | 0x00400000 | 0x00200000);
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
                    KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                    boolean isLocked = (km != null && km.isKeyguardLocked());

                    Intent intent = new Intent("android.media.action.STILL_IMAGE_CAMERA_SECURE");
                    PackageManager pm = context.getPackageManager();
                    ResolveInfo info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
                    if (info != null && !info.activityInfo.name.contains("Resolver")) {
                        intent.setComponent(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
                    }

                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
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

    /**
     * MIUI Camera 専用パッチ
     */
    private void applyWindowAndBufferFixes(Activity activity) {
        try {
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
            
            if (isMIUICamera(activity)) {
                Window window = activity.getWindow();
                if (window != null) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                  | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * クラス階層を遡ってフィールドを検索するヘルパー
     */
    private Field findFieldRecursive(Class<?> clazz, String fieldName) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
}
