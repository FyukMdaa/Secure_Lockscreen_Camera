package com.github.droserasprout.lockscreencamera.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

/**
 * カメラアプリのパッケージ／コンテキスト／DecorView かどうかを判定するヘルパー群。
 * <p>
 * リファクタリング版では pkg.contains("camera") という広すぎるワイルドカードで
 * カメラ以外のパッケージ（オーバーレイAPK等）に誤マッチする問題があった。
 * 修正後は {@link ModulePrefs} に保存された設定済みパッケージリストと照合し、
 * 設定されていないパッケージは一律で拒否する。
 */
public final class CameraPackageUtil {

    private CameraPackageUtil() {}

    /**
     * フックプロセス用: 設定済みパッケージリストと照合する。
     * prefs は {@code XposedModule.getRemotePreferences()} で取得したもの。
     */
    public static boolean isCameraPackage(String pkg, SharedPreferences prefs) {
        if (pkg == null || prefs == null) return false;
        return ModulePrefs.isPackageEnabled(prefs, pkg);
    }

    /**
     * フォールバック用: prefs が取得できない場合にデフォルトリストで判定する。
     */
    public static boolean isCameraPackage(String pkg) {
        if (pkg == null) return false;
        return pkg.equals("com.android.camera")
                || pkg.equals("com.google.android.GoogleCamera")
                || pkg.equals("com.android.camera2")
                || pkg.equals("org.codeaurora.snapcam")
                || pkg.equals("com.miui.camera");
    }

    /**
     * 設定が利用可能かどうかをチェックし、
     * 可能なら設定リスト、不可ならフォールバックリストを使う。
     */
    public static boolean isCameraPackage(String pkg, SharedPreferences prefs, boolean useSettings) {
        if (!useSettings || prefs == null) return isCameraPackage(pkg);
        try {
            return isCameraPackage(pkg, prefs);
        } catch (Exception e) {
            return isCameraPackage(pkg);
        }
    }

    public static boolean isCameraActivity(Activity act, SharedPreferences prefs) {
        if (act == null) return false;
        try {
            return isCameraPackage(act.getPackageName(), prefs);
        } catch (Exception e) {
            return isCameraPackage(act.getPackageName());
        }
    }

    /**
     * @deprecated SharedPreferences 版 {@link #isCameraActivity(Activity, SharedPreferences)} を推奨。
     */
    @Deprecated
    public static boolean isCameraActivity(Activity act) {
        if (act == null) return false;
        try {
            return isCameraPackage(act.getPackageName());
        } catch (Exception e) {
            try { return act.getClass().getName().contains("camera"); }
            catch (Exception e2) { return false; }
        }
    }

    /**
     * フックプロセス用: View の Context のパッケージがカメラか判定する。
     * prefs は {@code XposedModule.getRemotePreferences()} で取得したもの。
     */
    public static boolean isCameraContext(Context ctx, SharedPreferences prefs) {
        if (ctx == null) return false;
        try {
            return isCameraPackage(ctx.getPackageName(), prefs);
        } catch (Exception e) {
            return isCameraPackage(ctx.getPackageName());
        }
    }

    /**
     * @deprecated SharedPreferences 版 {@link #isCameraContext(Context, SharedPreferences)} を推奨。
     */
    @Deprecated
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
