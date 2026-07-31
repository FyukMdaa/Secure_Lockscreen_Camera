package com.github.droserasprout.lockscreencamera.hook;

import android.util.Log;
import android.view.View;

import java.util.List;

import com.github.droserasprout.lockscreencamera.util.CameraPackageUtil;

import io.github.libxposed.api.XposedModule;

/**
 * DecorView およびカメラアプリ内の全 View の透明化・非表示化を防ぐ。
 *
 * 修正メモ: リファクタリング版では DecorViewProtectionHook と
 * ViewVisibilityProtectionHook がそれぞれ独立して View.setVisibility をフックしており、
 * libxposed の実装によっては後から登録されたフックが前を上書きし、
 * DecorView 側の保護が失われる問題があった。
 * ここでは setVisibility のロジックを一本化し、DecorView かどうかに応じて
 * 適切に処理する単一フックに統合する。ViewVisibilityProtectionHook 側の
 * setVisibility フックは削除し、setAlpha 専用フックのみ残す。
 */
public final class DecorViewProtectionHook {

    private static final String TAG = "LockscreenCamera";

    private DecorViewProtectionHook() {}

    public static void install(XposedModule module) {
        try {
            // DecorView の透明化を防ぐ（setAlpha）
            module.hook(View.class.getDeclaredMethod("setAlpha", float.class)).intercept(chain -> {
                View view = (View) chain.getThisObject();
                if (CameraPackageUtil.isDecorView(view) && CameraPackageUtil.isCameraContext(view.getContext())) {
                    List<Object> args = chain.getArgs();
                    float alpha = (float) args.get(0);
                    if (alpha < 1.0f) args.set(0, 1.0f);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "DecorView setAlpha hook failed: " + t);
        }

        try {
            // setVisibility は DecorView / 非 DecorView を問わず、
            // カメラコンテキスト内では VISIBLE を強制する単一フック
            module.hook(View.class.getDeclaredMethod("setVisibility", int.class)).intercept(chain -> {
                View view = (View) chain.getThisObject();
                if (CameraPackageUtil.isCameraContext(view.getContext())) {
                    List<Object> args = chain.getArgs();
                    int vis = (int) args.get(0);
                    if (vis != View.VISIBLE) args.set(0, View.VISIBLE);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "DecorView setVisibility hook failed: " + t);
        }
    }
}
