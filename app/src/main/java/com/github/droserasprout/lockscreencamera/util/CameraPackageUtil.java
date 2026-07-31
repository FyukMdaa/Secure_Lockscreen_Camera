package com.github.droserasprout.lockscreencamera.util;

import android.app.Activity;
import android.content.Context;
import android.view.View;

/**
 * カメラアプリのパッケージ／コンテキスト／DecorView かどうかを判定するヘルパー群。
 * 各フッククラスから共通利用するため、ここに一本化している。
 */
public final class CameraPackageUtil {

    private CameraPackageUtil() {}

    public static boolean isCameraPackage(String pkg) {
        if (pkg == null) return false;
        return pkg.equals("com.android.camera")
                || pkg.contains("GoogleCamera")
                || pkg.equals("org.codeaurora.snapcam")
                || pkg.contains("camera");
    }

    public static boolean isCameraActivity(Activity act) {
        if (act == null) return false;
        try {
            return isCameraPackage(act.getPackageName());
        } catch (Exception e) {
            try {
                return act.getClass().getName().contains("camera");
            } catch (Exception e2) {
                return false;
            }
        }
    }

    public static boolean isCameraContext(Context ctx) {
        if (ctx == null) return false;
        try {
            return isCameraPackage(ctx.getPackageName());
        } catch (Exception e) {
            return ctx.getClass().getName().contains("camera");
        }
    }

    public static boolean isDecorView(View v) {
        return v != null && v.getClass().getName().endsWith("DecorView");
    }
}
