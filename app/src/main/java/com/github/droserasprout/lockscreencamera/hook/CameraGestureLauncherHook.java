package com.github.droserasprout.lockscreencamera.hook;

import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * 電源ボタン二度押し等の「カメラジェスチャー」ハンドラをフックし、
 * ロック中でも Secure Camera Intent を明示的な Component 指定で起動する。
 * AOSP の GestureLauncherService と MIUI 固有クラスの両方に対応する。
 */
public final class CameraGestureLauncherHook {

    private static final String TAG = "LockscreenCamera.Gesture";
    private static final String EXTRA_START_BY_KEYGUARD = "com.miui.camera.extra.START_BY_KEYGUARD";
    private static final int BACKGROUND_ACTIVITY_START_ALLOWED = 2;

    private CameraGestureLauncherHook() {}

    public static void install(XposedModule module, SystemServerStartingParam param) {
        boolean hooked = false;

        // 1. AOSP GestureLauncherService
        hooked |= tryHookGestureService(module, param.getClassLoader(),
                "com.android.server.GestureLauncherService");

        // 2. MIUI GestureLauncherService（一部 MIUI/HyperOS バージョン）
        hooked |= tryHookGestureService(module, param.getClassLoader(),
                "com.miui.server.GestureLauncherService");

        // 3. MIUI のキーガード カメラ起動 (KeyguardCameraLauncher)
        hooked |= tryHookMiuiKeyguardCamera(module, param.getClassLoader());

        if (!hooked) {
            module.log(Log.WARN, TAG, "No camera gesture hook point found on this device");
        } else {
            module.log(Log.INFO, TAG, "Camera gesture hook installed successfully");
        }
    }

    /**
     * 指定された GestureLauncherService クラスの handleCameraGesture をフックする。
     * メソッドが見つからない場合は何もしない（false を返す）。
     */
    private static boolean tryHookGestureService(XposedModule module, ClassLoader cl, String className) {
        try {
            Class<?> gestureClass = Class.forName(className, true, cl);

            // (boolean, int) シグネチャ
            try {
                Method m = gestureClass.getDeclaredMethod("handleCameraGesture", boolean.class, int.class);
                module.hook(m).intercept(chain -> {
                    try {
                        Context ctx = getContextFromService(chain.getThisObject());
                        launchSecureCamera(ctx);
                        return true;
                    } catch (Throwable t) {
                        module.log(Log.WARN, TAG, "Custom launch failed, falling back to original");
                        return chain.proceed();
                    }
                });
                module.log(Log.INFO, TAG, "Hooked " + className + ".handleCameraGesture(boolean, int)");
                return true;
            } catch (NoSuchMethodException ignored) {}

            // 引数なしシグネチャ（一部デバイス）
            try {
                Method m = gestureClass.getDeclaredMethod("handleCameraGesture");
                module.hook(m).intercept(chain -> {
                    try {
                        Context ctx = getContextFromService(chain.getThisObject());
                        launchSecureCamera(ctx);
                        return null;
                    } catch (Throwable t) {
                        module.log(Log.WARN, TAG, "Custom launch failed, falling back to original");
                        return chain.proceed();
                    }
                });
                module.log(Log.INFO, TAG, "Hooked " + className + ".handleCameraGesture()");
                return true;
            } catch (NoSuchMethodException ignored) {}

            // (boolean) シグネチャ
            try {
                Method m = gestureClass.getDeclaredMethod("handleCameraGesture", boolean.class);
                module.hook(m).intercept(chain -> {
                    try {
                        Context ctx = getContextFromService(chain.getThisObject());
                        launchSecureCamera(ctx);
                        return null;
                    } catch (Throwable t) {
                        module.log(Log.WARN, TAG, "Custom launch failed, falling back to original");
                        return chain.proceed();
                    }
                });
                module.log(Log.INFO, TAG, "Hooked " + className + ".handleCameraGesture(boolean)");
                return true;
            } catch (NoSuchMethodException ignored) {}

        } catch (ClassNotFoundException ignored) {}
        return false;
    }

    /**
     * MIUI 固有のキーガードカメラ起動をフックする。
     * launchCamera / startCameraFromKeyguard 等のメソッド名を試行する。
     */
    private static boolean tryHookMiuiKeyguardCamera(XposedModule module, ClassLoader cl) {
        String[] classNames = {
                "com.android.keyguard.KeyguardCameraLauncher",
                "com.miui.keyguard.camera.KeyguardCameraLauncher",
                "com.android.systemui.camera.CameraLauncher"
        };
        String[] methodNames = {
                "launchCamera", "startCamera", "startCameraFromKeyguard", "launchSecureCamera"
        };

        for (String cn : classNames) {
            try {
                Class<?> clazz = Class.forName(cn, true, cl);
                for (String mn : methodNames) {
                    for (Method m : clazz.getDeclaredMethods()) {
                        if (m.getName().equals(mn) && m.getParameterCount() == 0) {
                            module.hook(m).intercept(chain -> {
                                try {
                                    Context ctx = getContextFromService(chain.getThisObject());
                                    if (ctx != null) launchSecureCamera(ctx);
                                } catch (Throwable t) {
                                    module.log(Log.WARN, TAG, "MIUI camera launch failed: " + t.getMessage());
                                }
                                return chain.proceed();
                            });
                            module.log(Log.INFO, TAG, "Hooked " + cn + "." + mn + "()");
                            return true;
                        }
                    }
                }
            } catch (ClassNotFoundException ignored) {}
        }
        return false;
    }

    private static Context getContextFromService(Object service) {
        try {
            Method m = service.getClass().getMethod("getContext");
            return (Context) m.invoke(service);
        } catch (Exception e) {
            Log.w(TAG, "getContext failed: " + e.getMessage());
            return null;
        }
    }

    private static void launchSecureCamera(Context context) {
        if (context == null) {
            Log.w(TAG, "launchSecureCamera: context is null");
            return;
        }

        KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        boolean isLocked = km != null && km.isKeyguardLocked();

        ComponentName target = resolveCameraComponent(context);

        Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
        if (target != null) {
            intent.setComponent(target);
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        if (isLocked) {
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.putExtra(EXTRA_START_BY_KEYGUARD, true);
            intent.putExtra("StartActivityWhenLocked", true);
            intent.putExtra("is_secure_camera", true);
        }

        intent.putExtra("android.intent.extra.CAMERA_OPEN_ONLY", true);
        intent.putExtra("com.android.systemui.camera_launch_source", "lockscreen_affordance");

        ActivityOptions options = ActivityOptions.makeBasic();
        if (Build.VERSION.SDK_INT >= 34) {
            options.setPendingIntentBackgroundActivityStartMode(BACKGROUND_ACTIVITY_START_ALLOWED);
        }

        Log.i(TAG, "Launching secure camera: " + target + ", locked=" + isLocked);
        context.startActivity(intent, options.toBundle());
    }

    private static ComponentName resolveCameraComponent(Context context) {
        PackageManager pm = context.getPackageManager();

        // SECURE アクションを試す
        Intent resolveIntent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
        ResolveInfo info = pm.resolveActivity(resolveIntent, PackageManager.MATCH_DEFAULT_ONLY);

        // Resolver に解決された場合は通常アクションにフォールバック
        if (info == null || info.activityInfo.name.contains("Resolver")) {
            resolveIntent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
            info = pm.resolveActivity(resolveIntent, PackageManager.MATCH_DEFAULT_ONLY);
        }

        if (info != null && !info.activityInfo.name.contains("Resolver")) {
            return new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
        }
        return new ComponentName("com.android.camera", "com.android.camera.Camera");
    }
}
