package com.github.droserasprout.lockscreencamera.hook;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.github.droserasprout.lockscreencamera.session.SessionManager;
import com.github.droserasprout.lockscreencamera.util.CameraPackageUtil;

import io.github.libxposed.api.XposedModule;

/**
 * カメラ Activity のライフサイクルをフックし、
 * セッション開始・画面OFF時の自動終了・ウィンドウ属性の再適用を行う。
 */
public final class CameraActivityLifecycleHook {

    private static final String TAG = "LockscreenCamera";
    private static final String EXTRA_START_BY_KEYGUARD = "com.miui.camera.extra.START_BY_KEYGUARD";
    private static final String[] LIFECYCLE_METHODS =
            {"attachBaseContext", "onCreate", "onStart", "onResume", "onWindowFocusChanged", "onDestroy"};

    private static final Map<Activity, BroadcastReceiver> ACTIVE_RECEIVERS = new WeakHashMap<>();
    private static SharedPreferences prefs;

    private CameraActivityLifecycleHook() {}

    public static void install(XposedModule module, SharedPreferences prefs) {
        CameraActivityLifecycleHook.prefs = prefs;

        for (String methodName : LIFECYCLE_METHODS) {
            try {
                Method method = resolveMethod(methodName);
                final String mName = methodName;
                module.hook(method).intercept(chain -> {
                    Object thisObj = chain.getThisObject();
                    if (!(thisObj instanceof Activity)) return chain.proceed();

                    Activity act = (Activity) thisObj;
                    if (!CameraPackageUtil.isCameraActivity(act, CameraActivityLifecycleHook.prefs)) return chain.proceed();

                    if ("onDestroy".equals(mName)) {
                        if (SessionManager.isActive) {
                            SessionManager.end();
                        }
                        unregisterReceiver(act);
                        return chain.proceed();
                    }

                    if ("onWindowFocusChanged".equals(mName)) {
                        boolean hasFocus = (boolean) ((List<?>) chain.getArgs()).get(0);
                        if (!hasFocus) return chain.proceed();
                        WindowSecurityBypass.apply(act);
                        return chain.proceed();
                    }

                    if ("onCreate".equals(mName)) {
                        Object res = chain.proceed();

                        Intent intent = act.getIntent();
                        boolean isLockscreenLaunch =
                                intent != null && intent.getBooleanExtra(EXTRA_START_BY_KEYGUARD, false);

                        if (isLockscreenLaunch) {
                            SessionManager.start();
                            Log.i(TAG, "Secure Lockscreen launch detected. Session Started.");
                            registerScreenOffReceiver(act);
                        }

                        WindowSecurityBypass.apply(act);
                        return res;
                    }

                    WindowSecurityBypass.apply(act);
                    return chain.proceed();
                });
            } catch (Throwable ignored) {}
        }
    }

    private static Method resolveMethod(String methodName) throws NoSuchMethodException {
        switch (methodName) {
            case "attachBaseContext":
                return ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            case "onCreate":
                return Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            case "onWindowFocusChanged":
                return Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class);
            case "onDestroy":
                return Activity.class.getDeclaredMethod("onDestroy");
            default:
                return Activity.class.getDeclaredMethod(methodName);
        }
    }

    private static void registerScreenOffReceiver(Activity act) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i) {
                SessionManager.end();
                act.finish();
            }
        };

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                act.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                act.registerReceiver(receiver, filter);
            }
            ACTIVE_RECEIVERS.put(act, receiver);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register receiver: " + e.getMessage());
        }
    }

    private static void unregisterReceiver(Activity act) {
        BroadcastReceiver receiver = ACTIVE_RECEIVERS.remove(act);
        if (receiver == null) return;
        try {
            act.unregisterReceiver(receiver);
        } catch (Exception ignored) {}
    }
}
