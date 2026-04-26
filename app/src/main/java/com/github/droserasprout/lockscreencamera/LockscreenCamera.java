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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";

    // ドキュメント: "public XposedModule()" — 引数なし
    public LockscreenCamera() {
        super();
    }

    // =========================================================================
    // SessionManager
    // =========================================================================

    public static final class SessionManager {

        static final String[] TARGET_BOOLEAN_FIELDS = {
            "mIsSecure", "mIsSecureCamera", "mKeyguardLocked",
            "mInLockScreen", "mIgnoreKeyguard", "mIsScreenOn",
            "mSecureCamera", "mIsKeyguardLocked", "mIsHideForeground",
            "mIsGalleryLock", "mIsCaptureIntent", "mIsPortraitIntent", "mIsVideoIntent",
            "mUserAuthenticationFlag", "mIgnoreKeyguardCheck",
            "mIsCameraApp", "mPrivacyAuthorized", "mIsForeground"
        };

        private static final Map<String, Field> fieldCache = new ConcurrentHashMap<>();

        public static final AtomicBoolean isActive = new AtomicBoolean(false);
        public static final Set<Uri> SESSION_URIS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

        private static final Field FIELD_NOT_FOUND_SENTINEL;
        static {
            Field sentinel = null;
            try {
                sentinel = SessionManager.class.getDeclaredField("isActive");
            } catch (Throwable ignored) {}
            FIELD_NOT_FOUND_SENTINEL = sentinel;
        }

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
            if (isActive.get() && uri != null) {
                boolean added = SESSION_URIS.add(uri);
                if (added) {
                    Log.d(TAG, "Added to Session: " + uri + " | Total: " + SESSION_URIS.size());
                }
            }
        }

        static void setFieldFast(Object obj, String fieldName, Object value) {
            try {
                Class<?> current = obj.getClass();
                String cacheKey = current.getName() + ":" + fieldName;
                Field f = fieldCache.get(cacheKey);

                if (f == null) {
                    Field found = null;
                    Class<?> search = current;
                    while (search != null && !search.getName().equals("android.app.Activity")) {
                        try {
                            found = search.getDeclaredField(fieldName);
                            found.setAccessible(true);
                            break;
                        } catch (NoSuchFieldException e) {
                            search = search.getSuperclass();
                        }
                    }
                    fieldCache.put(cacheKey, found != null ? found : FIELD_NOT_FOUND_SENTINEL);
                    f = fieldCache.get(cacheKey);
                }

                if (f != null && f != FIELD_NOT_FOUND_SENTINEL) {
                    f.set(obj, value);
                }
            } catch (Throwable ignored) {}
        }
    }

    // =========================================================================
    // XposedModule ライフサイクル
    // =========================================================================

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!isCameraPackage(pkg)) return;

        log(Log.INFO, TAG, "Targeting Camera App: " + pkg);

        // 0. DecorView の非表示化・透明化を阻止
        // DecorView のみを対象にすることで、カメラアプリ内のローディング表示・
        // ダイアログ・アニメーション等、意図的な非表示処理への干渉を避ける。
        // Chain.getArgs() の戻り値は immutable なため、引数変更には
        // chain.proceed(Object[] args) を使用する
        try {
            hook(View.class.getDeclaredMethod("setVisibility", int.class))
                .intercept(chain -> {
                    View view = (View) chain.getThisObject();
                    if (isDecorView(view) && isCameraContext(view.getContext())) {
                        int vis = (int) chain.getArg(0);
                        if (vis != View.VISIBLE) {
                            return chain.proceed(new Object[]{View.VISIBLE});
                        }
                    }
                    return chain.proceed();
                });

            hook(View.class.getDeclaredMethod("setAlpha", float.class))
                .intercept(chain -> {
                    View view = (View) chain.getThisObject();
                    if (isDecorView(view) && isCameraContext(view.getContext())) {
                        float alpha = (float) chain.getArg(0);
                        if (alpha < 1.0f) {
                            return chain.proceed(new Object[]{1.0f});
                        }
                    }
                    return chain.proceed();
                });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "View hook failed", t);
        }

        // 1. Keyguard 解除要求（PIN 画面）を完全にブロック
        try {
            Method dismissMethod = KeyguardManager.class.getDeclaredMethod(
                "requestDismissKeyguard",
                Activity.class,
                KeyguardManager.KeyguardDismissCallback.class);
            hook(dismissMethod).intercept(chain -> {
                log(Log.WARN, TAG, "BLOCKED: requestDismissKeyguard (Preventing PIN screen)");
                return null;
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "requestDismissKeyguard hook failed", t);
        }

        // 2. Activity Visibility Spoofing
        try {
            hook(Activity.class.getDeclaredMethod("hasWindowFocus"))
                .intercept(chain -> {
                    if (isCameraActivity((Activity) chain.getThisObject())) return true;
                    return chain.proceed();
                });
            hook(Activity.class.getDeclaredMethod("isResumed"))
                .intercept(chain -> {
                    if (isCameraActivity((Activity) chain.getThisObject())) return true;
                    return chain.proceed();
                });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Activity visibility hook failed", t);
        }

        // 3. Intent の動的書き換え
        try {
            hook(Activity.class.getDeclaredMethod("getIntent"))
                .intercept(chain -> {
                    Intent intent = (Intent) chain.proceed();
                    if (isCameraActivity((Activity) chain.getThisObject()) && intent != null) {
                        if (intent.getBooleanExtra(
                                "com.miui.camera.extra.START_BY_KEYGUARD", false)) {
                            intent.putExtra("is_secure_camera", true);
                            intent.putExtra("ShowCameraWhenLocked", true);
                        }
                    }
                    return intent;
                });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "getIntent hook failed", t);
        }

        // 4. Secure Gallery Redirect
        try {
            hook(Activity.class.getDeclaredMethod("startActivity", Intent.class))
                .intercept(chain -> {
                    handleGalleryRedirect(
                        (Context) chain.getThisObject(),
                        (Intent) chain.getArg(0));
                    return chain.proceed();
                });

            hook(Activity.class.getDeclaredMethod(
                    "startActivityForResult", Intent.class, int.class))
                .intercept(chain -> {
                    handleGalleryRedirect(
                        (Context) chain.getThisObject(),
                        (Intent) chain.getArg(0));
                    return chain.proceed();
                });

            hook(ContextWrapper.class.getDeclaredMethod("startActivity", Intent.class))
                .intercept(chain -> {
                    handleGalleryRedirect(
                        (Context) chain.getThisObject(),
                        (Intent) chain.getArg(0));
                    return chain.proceed();
                });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook gallery redirect", t);
        }

        // 5. ライフサイクルフック
        String[] criticalMethods = {
            "attachBaseContext", "onCreate", "onStart", "onResume",
            "onWindowFocusChanged", "onDestroy"
        };

        for (String mname : criticalMethods) {
            try {
                final Method m;
                if ("attachBaseContext".equals(mname)) {
                    m = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
                } else if ("onCreate".equals(mname)) {
                    m = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
                } else if ("onWindowFocusChanged".equals(mname)) {
                    m = Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class);
                } else if ("onDestroy".equals(mname)) {
                    m = Activity.class.getDeclaredMethod("onDestroy");
                } else {
                    m = Activity.class.getDeclaredMethod(mname);
                }

                final String methodName = mname;
                hook(m).intercept(chain -> {
                    Object thisObj = chain.getThisObject();
                    if (!(thisObj instanceof Activity)) return chain.proceed();

                    Activity act = (Activity) thisObj;
                    if (!isCameraActivity(act)) return chain.proceed();

                    if ("onDestroy".equals(methodName)) {
                        if (SessionManager.isActive.get()) {
                            SessionManager.end();
                        }
                        return chain.proceed();
                    }

                    if ("onWindowFocusChanged".equals(methodName)) {
                        boolean hasFocus = (boolean) chain.getArg(0);
                        if (!hasFocus) return chain.proceed();
                    }

                    if ("onCreate".equals(methodName)) {
                        Object res = chain.proceed();

                        Intent intent = act.getIntent();
                        boolean isLockscreenLaunch = intent != null &&
                            intent.getBooleanExtra(
                                "com.miui.camera.extra.START_BY_KEYGUARD", false);

                        if (isLockscreenLaunch) {
                            SessionManager.start();
                            log(Log.INFO, TAG,
                                "Secure Lockscreen launch detected. Session Started.");

                            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
                            BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
                                @Override
                                public void onReceive(Context context, Intent i) {
                                    SessionManager.end();
                                    act.finish();
                                }
                            };

                            try {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    act.registerReceiver(screenOffReceiver, filter,
                                        Context.RECEIVER_NOT_EXPORTED);
                                } else {
                                    act.registerReceiver(screenOffReceiver, filter);
                                }
                            } catch (Exception e) {
                                log(Log.WARN, TAG,
                                    "Failed to register receiver: " + e.getMessage());
                            }
                        }

                        applyWindowAndBufferFixes(act);
                        return res;
                    }

                    applyWindowAndBufferFixes(act);
                    return chain.proceed();
                });
            } catch (Throwable t) {
                log(Log.WARN, TAG, "Lifecycle hook failed for " + mname, t);
            }
        }

        // 6. 写真保存のトラッキング
        try {
            hook(ContentResolver.class.getDeclaredMethod(
                    "insert", Uri.class, ContentValues.class))
                .intercept(chain -> {
                    Uri returnedUri = (Uri) chain.proceed();
                    if (SessionManager.isActive.get() && returnedUri != null) {
                        SessionManager.add(returnedUri);
                    }
                    return returnedUri;
                });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "ContentResolver.insert hook failed", t);
        }

        try {
            hook(ContentResolver.class.getDeclaredMethod(
                    "update", Uri.class, ContentValues.class, String.class, String[].class))
                .intercept(chain -> {
                    if (SessionManager.isActive.get()) {
                        Uri uri = (Uri) chain.getArg(0);
                        ContentValues values = (ContentValues) chain.getArg(1);

                        if (uri != null && values != null) {
                            boolean isFinished = false;
                            if (Build.VERSION.SDK_INT >= 29 &&
                                values.containsKey(MediaStore.MediaColumns.IS_PENDING)) {
                                isFinished =
                                    ((Integer) values.get(
                                        MediaStore.MediaColumns.IS_PENDING)) == 0;
                            } else if (values.containsKey(MediaStore.Images.Media.DATA)) {
                                isFinished = true;
                            }
                            if (isFinished) {
                                SessionManager.add(uri);
                            }
                        }
                    }
                    return chain.proceed();
                });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "ContentResolver.update hook failed", t);
        }

        // 7. その他システムフック群
        try {
            Class<?> callbackClass = Class.forName(
                "android.hardware.camera2.CameraManager$AvailabilityCallback",
                true, param.getClassLoader());
            hook(callbackClass.getDeclaredMethod("onCameraUnavailable", String.class))
                .intercept(chain -> {
                    log(Log.INFO, TAG,
                        "Blocked onCameraUnavailable for ID: " + chain.getArg(0));
                    return null;
                });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "CameraAvailabilityCallback hook failed", t);
        }

        try {
            Class<?> biometricClass = Class.forName(
                "android.hardware.biometrics.BiometricManager", true, param.getClassLoader());
            hook(biometricClass.getDeclaredMethod("canAuthenticate", int.class))
                .intercept(chain -> 0);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "BiometricManager hook failed", t);
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
                    Method getContextMethod =
                        chain.getThisObject().getClass().getMethod("getContext");
                    Context context =
                        (Context) getContextMethod.invoke(chain.getThisObject());

                    KeyguardManager km =
                        (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                    boolean isLocked = (km != null && km.isKeyguardLocked());

                    PackageManager pm = context.getPackageManager();
                    Intent resolveIntent =
                        new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    ResolveInfo info =
                        pm.resolveActivity(resolveIntent, PackageManager.MATCH_DEFAULT_ONLY);

                    if (info == null || info.activityInfo.name.contains("Resolver")) {
                        resolveIntent =
                            new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                        info = pm.resolveActivity(
                            resolveIntent, PackageManager.MATCH_DEFAULT_ONLY);
                    }

                    String targetPkg = "com.android.camera";
                    String targetCls = "com.android.camera.Camera";

                    if (info != null && !info.activityInfo.name.contains("Resolver")) {
                        targetPkg = info.activityInfo.packageName;
                        targetCls = info.activityInfo.name;
                    }

                    Intent intent =
                        new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    if (targetPkg != null && targetCls != null) {
                        intent.setComponent(new ComponentName(targetPkg, targetCls));
                    }

                    final int FLAG_ACTIVITY_RESET_TASK_IF_NEEDED = 0x00040000;
                    final int FLAG_SHOW_WHEN_LOCKED_COMPAT        = 0x00080000;
                    final int FLAG_DISMISS_KEYGUARD_COMPAT         = 0x00400000;
                    final int FLAG_TURN_SCREEN_ON_COMPAT           = 0x00200000;

                    int flags = FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP;

                    if (isLocked) {
                        flags |= Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
                        intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", true);
                        intent.putExtra("StartActivityWhenLocked", true);
                        intent.putExtra("is_secure_camera", true);
                    } else {
                        intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", false);
                        intent.putExtra("StartActivityWhenLocked", false);
                    }

                    intent.addFlags(flags);
                    intent.addFlags(FLAG_SHOW_WHEN_LOCKED_COMPAT
                        | FLAG_DISMISS_KEYGUARD_COMPAT
                        | FLAG_TURN_SCREEN_ON_COMPAT);
                    intent.putExtra("android.intent.extra.CAMERA_OPEN_ONLY", true);
                    intent.putExtra("com.android.systemui.camera_launch_source",
                        "lockscreen_affordance");

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

    // =========================================================================
    // ヘルパーメソッド群
    // =========================================================================

    private boolean isCameraPackage(String pkg) {
        if (pkg == null) return false;
        if (pkg.equals("com.android.camera") || pkg.equals("org.codeaurora.snapcam")) return true;
        if (pkg.contains("GoogleCamera")) return true;
        if (pkg.contains("cam")
            && !pkg.startsWith("com.android.")
            && !pkg.startsWith("com.google.")) {
            return true;
        }
        return false;
    }

    private boolean isCameraActivity(Activity act) {
        try {
            return act != null && isCameraPackage(act.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isCameraContext(Context ctx) {
        if (ctx == null) return false;
        try {
            return isCameraPackage(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isDecorView(View v) {
        return v != null && v.getClass().getName().endsWith("DecorView");
    }

    private void handleGalleryRedirect(Context ctx, Intent intent) {
        if (ctx == null || intent == null || intent.getAction() == null) return;
        if (!SessionManager.isActive.get()) return;

        try {
            if (!isCameraPackage(ctx.getPackageName())) return;
        } catch (Exception e) {
            return;
        }

        if (intent.getData() != null) {
            try {
                if (intent.hasExtra("is_secure_camera")
                    && !"com.android.camera".equals(ctx.getPackageName())) {
                    Log.d(TAG, "Smart Bypass: Intent is already secure in non-MIUI app.");
                    return;
                }
            } catch (Exception ignored) {}
        }

        String action = intent.getAction();
        boolean isGallery = Intent.ACTION_VIEW.equals(action)
            || Intent.ACTION_PICK.equals(action)
            || action.contains("REVIEW")
            || action.contains("STILL_IMAGE_CAMERA");

        if (!isGallery) return;

        Log.i(TAG, "Redirecting to SecureViewer: Force hijacking intent");

        ArrayList<Uri> uriList = new ArrayList<>(SessionManager.SESSION_URIS);
        if (uriList.isEmpty() && intent.getData() != null) {
            uriList.add(intent.getData());
        }

        intent.setComponent(new ComponentName(
            "com.github.droserasprout.lockscreencamera",
            "com.github.droserasprout.lockscreencamera.SecureViewerActivity"
        ));
        intent.setPackage(null);
        intent.setSelector(null);

        if (!uriList.isEmpty()) {
            ClipData clipData = new ClipData(
                "session_photos",
                new String[]{"image/*"},
                new ClipData.Item(uriList.get(0))
            );
            for (int i = 1; i < uriList.size(); i++) {
                clipData.addItem(new ClipData.Item(uriList.get(i)));
            }
            intent.setClipData(clipData);
        }

        intent.putParcelableArrayListExtra("session_photos_list", uriList);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_CLEAR_TOP
            | Intent.FLAG_ACTIVITY_NO_ANIMATION);

        final int FLAG_SHOW_WHEN_LOCKED_COMPAT = 0x00080000;
        final int FLAG_DISMISS_KEYGUARD_COMPAT  = 0x00400000;
        final int FLAG_TURN_SCREEN_ON_COMPAT    = 0x00200000;
        intent.addFlags(FLAG_SHOW_WHEN_LOCKED_COMPAT
            | FLAG_DISMISS_KEYGUARD_COMPAT
            | FLAG_TURN_SCREEN_ON_COMPAT);

        Log.d(TAG, "Intent modification complete. Proceeding with hijacked intent.");
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
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

                lp.flags &= ~WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }
                window.setAttributes(lp);
                window.addFlags(lp.flags);
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                );

                View decorView = window.getDecorView();
                if (decorView != null) {
                    decorView.setAlpha(1.0f);
                    decorView.setVisibility(View.VISIBLE);
                    decorView.requestFocus();
                }
            }

            for (String fieldName : SessionManager.TARGET_BOOLEAN_FIELDS) {
                SessionManager.setFieldFast(activity, fieldName, true);
            }
            SessionManager.setFieldFast(activity, "mIsNormalIntent", false);
            SessionManager.setFieldFast(activity, "mShowEnteringAnimation", false);
            SessionManager.setFieldFast(activity, "mKeyguardStatus", 1);
            SessionManager.setFieldFast(activity, "mIsSecureCameraId", 0);

        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "UI Fixes failed: " + t.toString());
        }
    }
}
