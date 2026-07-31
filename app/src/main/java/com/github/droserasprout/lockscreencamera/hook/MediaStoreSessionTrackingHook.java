package com.github.droserasprout.lockscreencamera.hook;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.github.droserasprout.lockscreencamera.session.SessionManager;

import io.github.libxposed.api.XposedModule;

/**
 * セッション中に MediaStore へ挿入・更新された画像 URI を追跡し、
 * ギャラリーリダイレクト（{@link GalleryRedirectHook}）でプレビュー一覧として使えるようにする。
 *
 * 修正メモ: insert(Uri, ContentValues) の元の実装は、実際に挿入された行の URI
 * （chain.proceed() の戻り値）ではなく第2引数の ContentValues を Uri にキャストしていたため、
 * 呼び出しのたびに ClassCastException が発生していた（フックの例外保護により黙って握りつぶされ、
 * 実質「撮影した写真がセッションに追加されない」状態になっていた）。
 */
public final class MediaStoreSessionTrackingHook {

    private MediaStoreSessionTrackingHook() {}

    public static void install(XposedModule module) {
        installInsertHook(module);
        installUpdateHook(module);
    }

    private static void installInsertHook(XposedModule module) {
        try {
            module.hook(ContentResolver.class.getDeclaredMethod("insert", Uri.class, ContentValues.class))
                    .intercept(chain -> {
                        Uri returnedUri = (Uri) chain.proceed();
                        if (SessionManager.isActive && returnedUri != null) {
                            SessionManager.add(returnedUri);
                        }
                        return returnedUri;
                    });
        } catch (Throwable ignored) {}
    }

    private static void installUpdateHook(XposedModule module) {
        try {
            module.hook(ContentResolver.class.getDeclaredMethod(
                            "update", Uri.class, ContentValues.class, String.class, String[].class))
                    .intercept(chain -> {
                        if (SessionManager.isActive) {
                            Uri uri = (Uri) chain.getArgs().get(0);
                            ContentValues values = (ContentValues) chain.getArgs().get(1);
                            if (uri != null && values != null && isWriteFinished(values)) {
                                SessionManager.add(uri);
                            }
                        }
                        return chain.proceed();
                    });
        } catch (Throwable ignored) {}
    }

    private static boolean isWriteFinished(ContentValues values) {
        if (Build.VERSION.SDK_INT >= 29 && values.containsKey(MediaStore.MediaColumns.IS_PENDING)) {
            return (Integer) values.get(MediaStore.MediaColumns.IS_PENDING) == 0;
        }
        return values.containsKey(MediaStore.Images.Media.DATA);
    }
}
