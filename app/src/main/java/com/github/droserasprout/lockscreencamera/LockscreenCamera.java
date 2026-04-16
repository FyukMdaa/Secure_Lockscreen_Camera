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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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

    // === SessionManager (カメラアプリプロセス内で動作) ===
    public static class SessionManager {
        public static volatile boolean isActive = false;
        public static final List<Uri> SESSION_URIS = new CopyOnWriteArrayList<>();

        public static void start() {
            isActive = true;
            SESSION_URIS.clear();
            Log.i(TAG, "📸 Secure Camera Session Started");
        }

        public static void end() {
            isActive = false;
            SESSION_URIS.clear();
            Log.i(TAG, "🔒 Secure Camera Session Cleared");
        }

        public static void add(Uri uri) {
            if (isActive && uri != null && !SESSION_URIS.contains(uri)) {
                SESSION_URIS.add(uri);
                Log.d(TAG, "Added to Session: " + uri + " | Total: " + SESSION_URIS.size());
            }
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!isCameraPackage(pkg)) return;

        Log.i(TAG, "Hooking Camera Package: " + pkg);

        // 1. 【GCam 必須】Keyguard 判定の完全スプーフィング
        // GCam は isDeviceLocked/isKeyguardSecure でロック状態を確認し、true なら即座に finish() する
        try {
            Class<?> km = KeyguardManager.class;
            hook(km.getDeclaredMethod("isKeyguardLocked")).intercept(chain -> false);
            hook(km.getDeclaredMethod("isDeviceLocked")).intercept(chain -> false);
            hook(km.getDeclaredMethod("isKeyguardSecure")).intercept(chain -> false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { hook(km.getDeclaredMethod("canRequestEphemeralUnlock")).intercept(chain -> false); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // 2. ContentResolver フック (GCam の IS_PENDING 処理対応)
        try {
            XposedModuleInterface.Interceptor contentHook = chain -> {
                if (SessionManager.isActive) {
                    Uri uri = (Uri) chain.getArgs().get(0);
                    ContentValues values = (ContentValues) chain.getArgs().get(1);
                    Object result = chain.proceed(); // 先に実行して URI を確定
                    
                    Uri finalUri = (result instanceof Uri) ? (Uri) result : uri;
                    boolean isFinished = true;
                    
                    if (values != null && Build.VERSION.SDK_INT >= 29 && values.containsKey(MediaStore.MediaColumns.IS_PENDING)) {
                        isFinished = (Integer) values.get(MediaStore.MediaColumns.IS_PENDING) == 0;
                    }

                    if (isFinished && finalUri != null) {
                        SessionManager.add(finalUri);
                    }
                    return result;
                }
                return chain.proceed();
            };

            hook(ContentResolver.class.getDeclaredMethod("insert", Uri.class, ContentValues.class)).intercept(contentHook);
            hook(ContentResolver.class.getDeclaredMethod("update", Uri.class, ContentValues.class, String.class, String[].class)).intercept(contentHook);
        } catch (Throwable ignored) {}

        // 3. ギャラリーリダイレクト (安全なキャンセル & 自前ビューア起動)
        try {
            XposedModuleInterface.Interceptor startHook = chain -> {
                Context ctx = (Context) chain.getThisObject();
                Intent originalIntent = (Intent) chain.getArgs().get(0);
                
                // セッション中でなければ通常通り
                if (!SessionManager.isActive) return chain.proceed();

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
        } catch (Throwable ignored) {}

        // 4. ライフサイクル & セッション開始 / 終了
        try {
            hook(Activity.class.getDeclaredMethod("onCreate", Bundle.class)).intercept(chain -> {
                Activity act = (Activity) chain.getThisObject();
                if (isCameraActivity(act)) {
                    Intent intent = act.getIntent();
                    if (intent != null && intent.getBooleanExtra("com.miui.camera.extra.START_BY_KEYGUARD", false)) {
                        SessionManager.start();
                        registerScreenOffReceiver(act);
                    }
                    applyWindowAndBufferFixes(act);
                }
                return chain.proceed();
            });

            hook(Activity.class.getDeclaredMethod("onDestroy")).intercept(chain -> {
                if (isCameraActivity((Activity) chain.getThisObject()) && SessionManager.isActive) {
                    SessionManager.end();
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}
    }

    // 【修正】GCam 向けに Resolver 回避 & ActivityOptions 適用
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

                    // SECURE アクションを優先 (MIUI 描画トリガー)
                    Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    
                    // デフォルトカメラ解決 (Resolver 回避)
                    PackageManager pm = context.getPackageManager();
                    ResolveInfo info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
                    if (info != null && !info.activityInfo.name.contains("Resolver")) {
                        intent.setComponent(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
                    }

                    // Android 14+ バックグラウンド起動制限解除
                    ActivityOptions options = ActivityOptions.makeBasic();
                    if (Build.VERSION.SDK_INT >= 34) {
                        options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    }

                    int flags = 0x00040000 | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP;
                    if (isLocked) {
                        flags |= Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
                        intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", true);
                        intent.putExtra("is_secure_camera", true);
                    }

                    intent.addFlags(flags);
                    context.startActivity(intent, options.toBundle());
                    return true;
                } catch (Throwable t) { 
                    Log.e(TAG, "Launch failed", t); 
                    return chain.proceed(); 
                }
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
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                              | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                              | WindowManager.LayoutParams.FLAG_FULLSCREEN
                              | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                              | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                              | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                window.setFormat(PixelFormat.TRANSLUCENT);
            }

            for (String f : TARGET_BOOLEAN_FIELDS) setFieldFast(activity, f, true);
        } catch (Throwable ignored) {}
    }

    private void registerScreenOffReceiver(Activity act) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (SessionManager.isActive) {
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

    private boolean isCameraPackage(String pkg) {
        return pkg != null && (pkg.equals("com.android.camera") || pkg.contains("GoogleCamera") || pkg.contains("camera"));
    }

    private boolean isCameraActivity(Activity act) {
        return act != null && isCameraPackage(act.getPackageName());
    }

    private void setFieldFast(Object obj, String fieldName, Object value) {
        try {
            Class<?> current = obj.getClass();
            String key = current.getName() + ":" + fieldName;
            Field f = fieldCache.get(key);
            if (f == null) {
                while (current != null && !current.getName().equals("android.app.Activity")) {
                    try { f = current.getDeclaredField(fieldName); f.setAccessible(true); break; }
                    catch (NoSuchFieldException e) { current = current.getSuperclass(); }
                }
                if (f != null) fieldCache.put(key, f);
            }
            if (f != null) f.set(obj, value);
        } catch (Throwable ignored) {}
    }
}
