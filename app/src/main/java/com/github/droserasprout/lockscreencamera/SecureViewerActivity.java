package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SecureViewerActivity extends Activity {

    private static final String TAG = "SecureViewer";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private BroadcastReceiver screenOffReceiver;
    private LinearLayout photoContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. ロック画面表示 & セキュリティ設定
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setInheritShowWhenLocked(true);
        }
        // スクリーンショット/録画を物理的に防止
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        // 2. スクロールビューの構築
        // photoContainer の幅を WRAP_CONTENT にすることで、画像が増えると横に伸びてスクロール可能になる
        photoContainer = new LinearLayout(this);
        photoContainer.setOrientation(LinearLayout.HORIZONTAL);
        photoContainer.setGravity(Gravity.CENTER_VERTICAL); // 縦方向は中央揃え
        // 横幅は画像の分だけ伸びる (WRAP_CONTENT)
        photoContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 
                LinearLayout.LayoutParams.MATCH_PARENT));

        HorizontalScrollView scrollView = new HorizontalScrollView(this);
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.MATCH_PARENT));
        scrollView.setFillViewport(true); // スクロールビューをビューポートで満たす
        scrollView.addView(photoContainer);
        setContentView(scrollView);

        // 3. データの受け取り（Intent から）
        List<Uri> uris = getIntent().getParcelableArrayListExtra("session_photos_list");

        // フォールバック: リストが空でも、単一画像の Intent Data はあるか確認
        if (uris == null || uris.isEmpty()) {
            Uri singleUri = getIntent().getData();
            if (singleUri != null) {
                uris = new ArrayList<>();
                uris.add(singleUri);
                Log.i(TAG, "Fallback to single image from Intent Data");
            }
        }

        if (uris != null && !uris.isEmpty()) {
            loadSessionPhotos(uris);
        } else {
            Log.w(TAG, "No photos to show");
            finish();
            return;
        }

        // 4. 画面OFFで自動終了
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

    private void loadSessionPhotos(List<Uri> uris) {
        executor.execute(() -> {
            for (Uri uri : uris) {
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    if (bitmap != null) {
                        runOnUiThread(() -> addPhotoView(bitmap));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to load: " + uri, e);
                }
            }
            // 全画像読み込み後、最新の画像までスクロール
            runOnUiThread(() -> {
                if (photoContainer.getChildCount() > 0) {
                    photoContainer.post(() -> {
                        View lastChild = photoContainer.getChildAt(photoContainer.getChildCount() - 1);
                        // 右端へスクロール
                        ((HorizontalScrollView) lastChild.getParent()).fullScroll(View.FOCUS_RIGHT);
                    });
                }
            });
        });
    }

    private void addPhotoView(Bitmap bitmap) {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER); // 画像全体を表示
        
        // 画像間のスペース
        int padding = 10; 
        iv.setPadding(padding, padding, padding, padding);

        // 幅：画面幅 (widthPixels)
        // 高さ：画像のアスペクト比に合わせる (WRAP_CONTENT)
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                screenWidth, 
                LinearLayout.LayoutParams.WRAP_CONTENT);
        iv.setLayoutParams(params);
        
        iv.setImageBitmap(bitmap);
        iv.setBackgroundColor(0xFF000000); // 背景は黒
        
        // タップで終了
        iv.setOnClickListener(v -> finish());
        
        photoContainer.addView(iv);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (screenOffReceiver != null) {
            try { unregisterReceiver(screenOffReceiver); } catch (Exception ignored) {}
        }
        executor.shutdownNow();
    }
}
