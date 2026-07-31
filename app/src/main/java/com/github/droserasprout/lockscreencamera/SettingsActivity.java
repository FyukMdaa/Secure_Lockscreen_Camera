package com.github.droserasprout.lockscreencamera;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.github.droserasprout.lockscreencamera.util.ModulePrefs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * モジュールの設定画面。
 * LSPosed マネージャーから起動され、対象カメラパッケージと
 * SecureViewer のオン/オフ・除外設定を管理する。
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, new SettingsFragment())
                    .commit();
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        private static final String TAG = "LockscreenCamera.Settings";

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            // 初回起動時は自動検出を実行
            if (ModulePrefs.isFirstRun(requireContext())) {
                autoDetectAndSave();
                ModulePrefs.markFirstRunDone(requireContext());
            }

            setupAutoDetect();
            setupPackageList();
            setupViewerSettings();
        }

        // ---- 自動検出 ----

        private void setupAutoDetect() {
            Preference autoDetect = findPreference("pref_auto_detect");
            if (autoDetect != null) {
                autoDetect.setOnPreferenceClickListener(pref -> {
                    autoDetectAndSave();
                    return true;
                });
            }
        }

        /**
         * システムにインストール済みのカメラアプリを検出し、
         * 既存の選択状態をマージして保存する。
         */
        private void autoDetectAndSave() {
            Set<String> detected = detectCameraPackages();

            // 既存の選択をマージ（ユーザーが手動追加したものも保持）
            Set<String> current = ModulePrefs.getPrefs(requireContext())
                    .getStringSet(ModulePrefs.KEY_ENABLED_PACKAGES, new HashSet<>());
            Set<String> merged = new HashSet<>(current);
            merged.addAll(detected);

            ModulePrefs.setEnabledPackages(requireContext(), merged);

            // リストを再構築
            refreshPackageList(merged);

            String msg = getString(R.string.auto_detect_result, detected.size());
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
            Log.i(TAG, "Auto-detected cameras: " + detected);
        }

        /**
         * PackageManager を使ってカメラアプリを検出する。
         * 1. INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE / STILL_IMAGE_CAMERA を resolve
         * 2. 既知のパッケージリストと照合してインストール済みのものを追加
         */
        private Set<String> detectCameraPackages() {
            Set<String> found = new HashSet<>();
            PackageManager pm = requireContext().getPackageManager();

            // resolve で見つかるもの
            String[] actions = {
                    MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE,
                    MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
            };

            for (String action : actions) {
                try {
                    List<ResolveInfo> list = pm.queryIntentActivities(
                            new Intent(action), PackageManager.MATCH_DEFAULT_ONLY);
                    for (ResolveInfo ri : list) {
                        if (ri.activityInfo != null) {
                            addIfInstalled(found, ri.activityInfo.packageName, pm);
                        }
                    }
                } catch (Exception ignored) {}
            }

            // 既知のカメラパッケージでインストール済みのもの
            for (String pkg : ModulePrefs.getKnownCameraPackages()) {
                addIfInstalled(found, pkg, pm);
            }

            return found;
        }

        private void addIfInstalled(Set<String> target, String pkg, PackageManager pm) {
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, 0);
                if ((pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        || (pi.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) != 0) {
                    target.add(pkg);
                }
            } catch (PackageManager.NameNotFoundException ignored) {
                // 未インストール
            }
        }

        // ---- パッケージ一覧 ----

        private void setupPackageList() {
            MultiSelectListPreference pref = findPreference("pref_enabled_packages");
            if (pref == null) return;
            pref.setOnPreferenceChangeListener((preference, newValue) -> {
                @SuppressWarnings("unchecked")
                Set<String> selected = (Set<String>) newValue;
                ModulePrefs.setEnabledPackages(requireContext(), selected);
                // サマリー更新
                pref.setSummary(buildSummary(selected));
                // Viewer 除外リストも最新化
                refreshViewerExclusions(selected);
                return true;
            });

            // 初期表示
            Set<String> current = ModulePrefs.getPrefs(requireContext())
                    .getStringSet(ModulePrefs.KEY_ENABLED_PACKAGES, new HashSet<>());
            refreshPackageList(current);
        }

        private void refreshPackageList(Set<String> selected) {
            MultiSelectListPreference pref = findPreference("pref_enabled_packages");
            if (pref == null) return;

            PackageManager pm = requireContext().getPackageManager();
            List<String> entries = new ArrayList<>();
            List<String> values = new ArrayList<>();

            // 選択済み + 既知パッケージ + 自動検出結果をまとめて表示
            Set<String> allCandidates = new HashSet<>(selected);
            allCandidates.addAll(ModulePrefs.getKnownCameraPackages());
            allCandidates.addAll(detectCameraPackages());

            for (String pkg : allCandidates) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    String label = pm.getApplicationLabel(ai).toString();
                    entries.add(label + " (" + pkg + ")");
                    values.add(pkg);
                } catch (PackageManager.NameNotFoundException e) {
                    // アンインストール済みでも選択済みなら表示
                    if (selected.contains(pkg)) {
                        entries.add(pkg);
                        values.add(pkg);
                    }
                }
            }

            Collections.sort(entries);
            // values も entries に合わせてソート
            List<String> sortedValues = new ArrayList<>();
            for (String entry : entries) {
                // "label (pkg)" 形式から pkg を抽出
                int lastParen = entry.lastIndexOf("(");
                String pkg = lastParen >= 0
                        ? entry.substring(lastParen + 1, entry.length() - 1)
                        : entry;
                sortedValues.add(pkg);
            }

            pref.setEntries(entries.toArray(new String[0]));
            pref.setEntryValues(sortedValues.toArray(new String[0]));
            pref.setValues(selected);
            pref.setSummary(buildSummary(selected));
        }

        private String buildSummary(Set<String> selected) {
            if (selected == null || selected.isEmpty()) {
                return "未選択（デフォルト使用）";
            }
            return selected.size() + " 個のアプリを選択中";
        }

        // ---- SecureViewer 設定 ----

        private void setupViewerSettings() {
            // メインスイッチ
            SwitchPreferenceCompat viewerToggle = findPreference("pref_secure_viewer_enabled");
            if (viewerToggle != null) {
                viewerToggle.setOnPreferenceChangeListener((preference, newValue) -> {
                    ModulePrefs.setSecureViewerEnabled(requireContext(), (Boolean) newValue);
                    return true;
                });
            }

            // 除外リスト
            MultiSelectListPreference exclusions = findPreference("pref_secure_viewer_exclusions");
            if (exclusions != null) {
                Set<String> enabledPkgs = ModulePrefs.getPrefs(requireContext())
                        .getStringSet(ModulePrefs.KEY_ENABLED_PACKAGES, new HashSet<>());
                refreshViewerExclusions(enabledPkgs);

                exclusions.setOnPreferenceChangeListener((preference, newValue) -> {
                    @SuppressWarnings("unchecked")
                    Set<String> selected = (Set<String>) newValue;
                    ModulePrefs.setSecureViewerExclusions(requireContext(), selected);
                    exclusions.setSummary(buildExclusionSummary(selected));
                    return true;
                });
            }
        }

        private void refreshViewerExclusions(Set<String> enabledPackages) {
            MultiSelectListPreference exclusions = findPreference("pref_secure_viewer_exclusions");
            if (exclusions == null) return;

            PackageManager pm = requireContext().getPackageManager();
            List<String> entries = new ArrayList<>();
            List<String> values = new ArrayList<>();

            Set<String> currentExclusions = ModulePrefs.getPrefs(requireContext())
                    .getStringSet(ModulePrefs.KEY_SECURE_VIEWER_EXCLUSIONS, new HashSet<>());

            for (String pkg : enabledPackages) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    String label = pm.getApplicationLabel(ai).toString();
                    entries.add(label + " (" + pkg + ")");
                    values.add(pkg);
                } catch (PackageManager.NameNotFoundException e) {
                    if (currentExclusions.contains(pkg)) {
                        entries.add(pkg);
                        values.add(pkg);
                    }
                }
            }

            exclusions.setEntries(entries.toArray(new String[0]));
            exclusions.setEntryValues(values.toArray(new String[0]));
            exclusions.setValues(currentExclusions);
            exclusions.setSummary(buildExclusionSummary(currentExclusions));
        }

        private String buildExclusionSummary(Set<String> exclusions) {
            if (exclusions == null || exclusions.isEmpty()) {
                return "全カメラアプリで有効";
            }
            return exclusions.size() + " 個のアプリで無効化";
        }
    }
}