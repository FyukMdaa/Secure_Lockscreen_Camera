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
        "mIsCameraApp", "mPrivacyAuthorized", "mIsForeground",
        "mKeyguardStatus", "mIsSecureCameraId"
    };

    public LockscreenCamera() {
        super();
    }

    private static final Set<String> MIUI_CAMERA_PACKAGES = new HashSet<>(Arrays.asList(
        "com.android.camera",
        "com.miui.camera",
        "com.xiaomi.camera"
    ));

    private boolean isMIUICamera(Activity act) {
        if (act == null) return false;
        String pkg = act.getPackageName();
        if (pkg != null && MIUI_CAMERA_PACKAGES.contains(pkg)) return true;
        
        String clsName = act.getClass().getName();
        if (clsName.contains("com.android.camera") || clsName.contains("com.miui.camera")) {
            return true;
        }
        
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
        public static final CopyOnWriteArrayList<Uri> SESSION_URIS = new CopyOnWriteArrayList<>();

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
                if (SESSION_URIS.addIfAbsent(uri)) {
                    Log.d(TAG, "Added to Session: " + uri + " | Total: " + SESSION_URIS.size());
                }
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

        // 4. Gallery Redirect (Smart Bypass)
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
                                boolean isMIUI = isMIUICamera(act);

                                Object res = chain.proceed();

                                if (isLockscreenLaunch && isMIUI) {
                                    for (String fieldName : TARGET_BOOLEAN_FIELDS) {
                                        try {
                                            Field f = findFieldRecursive(act.getClass(), fieldName);
                                            if (f != null) {
                                                f.setAccessible(true);
                                                if (f.getType() == boolean.class) f.setBoolean(act, true);
                                                else if (f.getType() == int.class) f.setInt(act, 1);
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                }

                                applyWindowAndBufferFixes(act);

                                if (isLockscreenLaunch) {
                                    SessionManager.start();
                                    registerScreenOffReceiver(act);
                                }
                                return res;
                            }
                            applyWindowAndBufferFixes(act);
                        }
                    }
                    return chain.proceed();
                });
            } catch (Throwable ignored) {}
        }

        // 6. ContentResolver フック
        try {
            // Android 29 以下向け
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
        } catch (Throwable ignored) {}

        try {
            // Android 30 以上向け
            hook(ContentResolver.class.getDeclaredMethod("insert", Uri.class, ContentValues.class, Bundle.class))
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
        } catch (Throwable ignored) {}

        try {
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
    
            // LC-02: ユーザーがスコープを管理することを前提に、互換性を優先した判定
            // 1. ホワイトリスト（標準的なパッケージ）
            if (pkg.equals("com.android.camera") || pkg.equals("org.codeaurora.snapcam")) return true;
            
            // 2. Google Camera バリエーション
            if (pkg.contains("GoogleCamera")) return true;
            
            // 3. 広義の判定（サードパーティ製カメラやカスタムROM向け）
            // android. や google. プレフィックスを除外することで、OS標準の無関係なサービスへの誤爆を最低限抑える
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
        } catch (Exception e) { Log.w(TAG, "Receiver registration failed", e); }
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

            intent.setComponent(new ComponentName(
                    "com.github.droserasprout.lockscreencamera",
                    "com.github.droserasprout.lockscreencamera.SecureViewerActivity"
            ));
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

                    Intent resolveIntent = new Intent("android.media.action.STILL_IMAGE_CAMERA_SECURE");
                    PackageManager pm = context.getPackageManager();
                    ResolveInfo info = pm.resolveActivity(resolveIntent, PackageManager.MATCH_DEFAULT_ONLY);

                    String targetPkg = "com.android.camera";
                    String targetCls = "com.android.camera.Camera";
                    boolean isMIUI = false;

                    if (info != null && !info.activityInfo.name.contains("Resolver")) {
                        targetPkg = info.activityInfo.packageName;
                        targetCls = info.activityInfo.name;
                        isMIUI = isMIUIPackage(targetPkg);
                    }

                    String action = isMIUI ? "android.media.action.STILL_IMAGE_CAMERA" 
                                           : "android.media.action.STILL_IMAGE_CAMERA_SECURE";

                    Intent intent = new Intent(action);
                    intent.setComponent(new ComponentName(targetPkg, targetCls));

                    intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", true);
                    intent.putExtra("is_secure_camera", true);

                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    if (isLocked) {
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

            if (isMIUICamera(activity)) {
                Window window = activity.getWindow();
                if (window != null) {
                    
                    WindowManager.LayoutParams lp = window.getAttributes();
                    lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                              | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                              | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
                    window.setAttributes(lp);
                }
            }
        } catch (Throwable ignored) {}
    }

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

    private boolean isMIUIPackage(String pkg) {
        return pkg != null && MIUI_CAMERA_PACKAGES.contains(pkg);
    }
}
