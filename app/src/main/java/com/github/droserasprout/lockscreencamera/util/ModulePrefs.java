package com.github.droserasprout.lockscreencamera.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * モジュールの SharedPreferences への読み書きを一本化するヘルパー。
 * <p>
 * 設定画面（モジュール自身のプロセス）では Context ベースのメソッドを、
 * フック側（ターゲットアプリのプロセス）では SharedPreferences ベースのメソッドを使う。
 * フック側では {@code XposedModule.getRemotePreferences(PREFS_NAME)} で取得した
 * SharedPreferences を渡す。
 */
public final class ModulePrefs {

    private static final String TAG = "LockscreenCamera";
    public static final String PREFS_NAME = "secure_camera_prefs";

    // キー名
    public static final String KEY_ENABLED_PACKAGES = "enabled_packages";
    public static final String KEY_SECURE_VIEWER_ENABLED = "secure_viewer_enabled";
    public static final String KEY_SECURE_VIEWER_EXCLUSIONS = "secure_viewer_exclusions";
    public static final String KEY_FIRST_RUN = "first_run";

    private ModulePrefs() {}

    // ---- 書き込み（設定画面プロセスから呼ばれる） ----

    public static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void setEnabledPackages(Context ctx, Set<String> packages) {
        getPrefs(ctx).edit()
                .putStringSet(KEY_ENABLED_PACKAGES, packages)
                .apply();
    }

    public static void setSecureViewerEnabled(Context ctx, boolean enabled) {
        getPrefs(ctx).edit()
                .putBoolean(KEY_SECURE_VIEWER_ENABLED, enabled)
                .apply();
    }

    public static void setSecureViewerExclusions(Context ctx, Set<String> packages) {
        getPrefs(ctx).edit()
                .putStringSet(KEY_SECURE_VIEWER_EXCLUSIONS, packages)
                .apply();
    }

    public static void markFirstRunDone(Context ctx) {
        getPrefs(ctx).edit()
                .putBoolean(KEY_FIRST_RUN, false)
                .apply();
    }

    // ---- 読み込み（フック側プロセスから呼ばれる） ----

    /**
     * フックプロセス用: 有効なカメラパッケージセットを取得する。
     * 設定が空の場合はフォールバックとしてデフォルトリストを返す。
     *
     * @param prefs {@code XposedModule.getRemotePreferences(PREFS_NAME)} で取得したもの
     */
    public static Set<String> getEnabledPackages(SharedPreferences prefs) {
        if (prefs == null) return getDefaultPackages();
        Set<String> packages = prefs.getStringSet(KEY_ENABLED_PACKAGES, null);
        if (packages == null || packages.isEmpty()) return getDefaultPackages();
        return new HashSet<>(packages);
    }

    /**
     * フックプロセス用: SecureViewer が有効かどうか。
     * デフォルトは true（有効）。
     */
    public static boolean isSecureViewerEnabled(SharedPreferences prefs) {
        if (prefs == null) return true;
        return prefs.getBoolean(KEY_SECURE_VIEWER_ENABLED, true);
    }

    /**
     * フックプロセス用: SecureViewer を無効化するパッケージセット。
     */
    public static Set<String> getSecureViewerExclusions(SharedPreferences prefs) {
        if (prefs == null) return Collections.emptySet();
        Set<String> exclusions = prefs.getStringSet(KEY_SECURE_VIEWER_EXCLUSIONS, null);
        if (exclusions == null) return Collections.emptySet();
        return new HashSet<>(exclusions);
    }

    /**
     * フックプロセス用: 指定パッケージで SecureViewer を使うべきか。
     */
    public static boolean shouldUseSecureViewer(SharedPreferences prefs, String pkg) {
        if (!isSecureViewerEnabled(prefs)) return false;
        return !getSecureViewerExclusions(prefs).contains(pkg);
    }

    /**
     * フックプロセス用: 指定パッケージが設定済みのカメラパッケージか。
     */
    public static boolean isPackageEnabled(SharedPreferences prefs, String pkg) {
        return getEnabledPackages(prefs).contains(pkg);
    }

    // ---- デフォルト値 ----

    /** フォールバック: 設定が空の場合に使うデフォルトパッケージ */
    private static Set<String> getDefaultPackages() {
        Set<String> defaults = new HashSet<>();
        defaults.add("com.android.camera");
        defaults.add("com.google.android.GoogleCamera");
        defaults.add("com.android.camera2");
        defaults.add("org.codeaurora.snapcam");
        return defaults;
    }

    /**
     * 設定画面用: フォールバック候補パッケージ（自動検出に使われる補助リスト）。
     */
    public static Set<String> getKnownCameraPackages() {
        Set<String> known = new HashSet<>();
        known.add("com.android.camera");
        known.add("com.android.camera2");
        known.add("com.google.android.GoogleCamera");
        known.add("com.android.MGC");
        known.add("org.codeaurora.snapcam");
        known.add("com.miui.camera");
        known.add("com.samsung.android.camera");
        known.add("com.oneplus.camera");
        known.add("com.oplus.camera");
        known.add("com.sonysmartphone.camera");
        return known;
    }

    /** 初回起動かどうか（設定画面用） */
    public static boolean isFirstRun(Context ctx) {
        return getPrefs(ctx).getBoolean(KEY_FIRST_RUN, true);
    }
}