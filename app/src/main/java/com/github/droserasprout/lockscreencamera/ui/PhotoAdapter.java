package com.github.droserasprout.lockscreencamera.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.github.chrisbanes.photoview.PhotoView;
import com.github.droserasprout.lockscreencamera.util.BitmapDecoder;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * セッション中の写真 URI 一覧をズーム可能な PhotoView として表示するアダプタ。
 * Activity への強参照は持たず、WeakReference 経由でのみアクセスする。
 */
public class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder> {

    private final List<Uri> uris;
    private final WeakReference<Activity> activityRef;
    private final ExecutorService executor;
    private final ConcurrentHashMap<Integer, Future<?>> pendingTasks;

    public PhotoAdapter(
            List<Uri> uris,
            Activity activity,
            ExecutorService executor,
            ConcurrentHashMap<Integer, Future<?>> pendingTasks) {
        this.uris = uris;
        this.activityRef = new WeakReference<>(activity);
        this.executor = executor;
        this.pendingTasks = pendingTasks;
    }

    @NonNull
    @Override
    public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        PhotoView photoView = new PhotoView(parent.getContext());
        photoView.setBackgroundColor(0xFF000000);
        photoView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        photoView.setZoomable(true);
        return new PhotoViewHolder(photoView);
    }

    @Override
    public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
        holder.photoView.setImageDrawable(null);

        // 同じポジションの前回タスクをキャンセルして競合を防ぐ
        Future<?> prev = pendingTasks.remove(position);
        if (prev != null) prev.cancel(true);

        Activity activity = activityRef.get();
        if (activity == null || activity.isDestroyed()) return;

        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        // Context のみ渡し、アクティビティへの強参照をラムダに持ち込まない
        Context appContext = activity.getApplicationContext();
        Uri uri = uris.get(position);

        Future<?> task = executor.submit(() -> {
            Bitmap bitmap = BitmapDecoder.decodeSampledBitmapFromUri(
                    appContext, uri, screenWidth, screenHeight);
            if (bitmap == null) return;

            Activity act = activityRef.get();
            if (act == null || act.isDestroyed()) {
                bitmap.recycle();
                return;
            }

            act.runOnUiThread(() -> {
                pendingTasks.remove(position);
                // バインド時のポジションと現在のポジションが一致する場合のみ反映
                if (holder.getAdapterPosition() == position) {
                    holder.photoView.setImageBitmap(bitmap);
                } else {
                    bitmap.recycle();
                }
            });
        });

        pendingTasks.put(position, task);
    }

    @Override
    public void onViewRecycled(@NonNull PhotoViewHolder holder) {
        super.onViewRecycled(holder);
        holder.photoView.setImageDrawable(null);
    }

    @Override
    public int getItemCount() {
        return uris.size();
    }

    static class PhotoViewHolder extends RecyclerView.ViewHolder {
        final PhotoView photoView;

        PhotoViewHolder(PhotoView pv) {
            super(pv);
            photoView = pv;
        }
    }
}
