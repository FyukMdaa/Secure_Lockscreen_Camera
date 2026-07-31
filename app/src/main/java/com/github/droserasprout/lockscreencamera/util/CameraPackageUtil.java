package com.github.droserasprout.lockscreencamera.util;

import android.app.Activity;
import android.content.Context;
import android.view.View;

import java.util.Set;

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
     * context はターゲットアプリの Context（Activity 等）。
     */
    public static boolean isCameraPackage(String pkg, Context context) {
        if (pkg == null || context == null) return false;
        return ModulePrefs.isPackageEnabled(context, pkg);
    }

    /**
     * フォールバック用: context が取得できない場合にデフォルトリストで判定する。
     * 設定画面が未設定の場合もこちらにフォールバックする。
     */
    public static boolean isCameraPackage(String pkg) {
        if (pkg == null) return false;
        // ワイルドカードマッチは使わず、明示的なパッケージ名のみ
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
    public static boolean isCameraPackage(String pkg, Context context, boolean useSettings) {
        if (!useSettings || context == null) return isCameraPackage(pkg);
        try {
            return isCameraPackage(pkg, context);
        } catch (Exception e) {
            return isCameraPackage(pkg);
        }
    }

    public static boolean isCameraActivity(Activity act, Context context) {
        if (act == null) return false;
        try {
            return isCameraPackage(act.getPackageName(), context);
        } catch (Exception e) {
            return isCameraPackage(act.getPackageName());
        }
    }

    /**
     * @deprecated 設定連動版 {@link #isCameraActivity(Activity, Context)} を推奨。
     * フック内で Context が取れない場合のフォールバック用。
     */
    public static boolean isCameraActivity(Activity act) {
        if (act == null) return false;
        try {
            return isCameraPackage(act.getPackageName());
        } catch (Exception e) {
            try { return act.getClass().getName().contains("camera"); }
            catch (Exception e2) { return false; }
        }
    }

    public static boolean isCameraContext(Context ctx, Context settingsContext) {
        if (ctx == null) return false;
        try {
            return isCameraPackage(ctx.getPackageName(), settingsContext);
        } catch (Exception e) {
            return isCameraPackage(ctx.getPackageName());
        }
    }

    /**
     * @deprecated 設定連動版 {@link #isCameraContext(Context, Context)} を推奨。
     */
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