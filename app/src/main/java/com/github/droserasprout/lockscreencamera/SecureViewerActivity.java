package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.ImageView;

import java.io.InputStream;

public class SecureViewerActivity extends Activity {

    private static final String TAG = "SecureViewer";
    private BroadcastReceiver screenOffReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. ロック画面上での表示・画面点灯を強制
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setInheritShowWhenLocked(true);
        }
        // 2. スクリーンショット・録画を物理的に防止（セキュリティ必須）
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        // 3. 動的ビュー作成（レイアウトXML不要）
        ImageView imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setBackgroundColor(0xFF000000); // 背景は黒
        imageView.setOnClickListener(v -> finish()); // タップで閉じる
        setContentView(imageView);

        // 4. 渡されたURIから画像を読み込み
        Uri uri = getIntent().getData();
        if (uri != null) {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                imageView.setImageBitmap(BitmapFactory.decodeStream(is));
                Log.i(TAG, "Image loaded successfully from session URI");
            } catch (Exception e) {
                Log.e(TAG, "Failed to load image: " + e.getMessage());
                finish();
            }
        } else {
            Log.w(TAG, "No image URI provided. Closing.");
            finish();
        }

        // 5. 画面OFFで自動終了（ロック画面復帰時に消す）
        screenOffReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { finish(); }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenOffReceiver, filter);
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (screenOffReceiver != null) {
            try { unregisterReceiver(screenOffReceiver); } catch (Exception ignored) {}
        }
    }
}
