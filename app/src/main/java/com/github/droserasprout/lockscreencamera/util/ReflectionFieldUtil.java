package com.github.droserasprout.lockscreencamera.util;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * private フィールドをキャッシュ付きで高速に書き換えるためのユーティリティ。
 *
 * 修正メモ: 元の実装は「見つからなかった」結果を
 * {@code fieldCache.put(cacheKey, null)} でキャッシュしようとしていたが、
 * ConcurrentHashMap は null 値を許容しないため実際には NullPointerException が発生し
 * （呼び出し側の try-catch で握りつぶされていた）、
 * 見つからないフィールドについては毎回クラス階層を走査し直す状態になっていた。
 * ここでは「探索済みかどうか」を別の Map で管理することで意図通りにキャッシュされるようにしている。
 */
public final class ReflectionFieldUtil {

    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> SEARCHED = new ConcurrentHashMap<>();

    private ReflectionFieldUtil() {}

    /**
     * obj のクラス階層（android.app.Activity まで）を辿り、
     * fieldName という名前の private フィールドに value をセットする。
     * 見つからない／アクセスできない場合は何もしない（例外は握りつぶす）。
     */
    public static void setFieldFast(Object obj, String fieldName, Object value) {
        try {
            Class<?> current = obj.getClass();
            String cacheKey = current.getName() + ":" + fieldName;

            Field f = FIELD_CACHE.get(cacheKey);
            if (f == null && !SEARCHED.containsKey(cacheKey)) {
                while (current != null && !current.getName().equals("android.app.Activity")) {
                    try {
                        f = current.getDeclaredField(fieldName);
                        f.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException e) {
                        current = current.getSuperclass();
                    }
                }
                SEARCHED.put(cacheKey, Boolean.TRUE);
                if (f != null) {
                    FIELD_CACHE.put(cacheKey, f);
                }
            }

            if (f != null) {
                f.set(obj, value);
            }
        } catch (Throwable ignored) {
            // リフレクション失敗は致命的ではないため無視する
        }
    }
}
