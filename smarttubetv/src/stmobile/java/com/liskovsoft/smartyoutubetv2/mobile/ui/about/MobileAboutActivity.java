package com.liskovsoft.smartyoutubetv2.mobile.ui.about;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.smartyoutubetv2.tv.R;

import okhttp3.Response;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * About screen — the fork-attribution surface. Shows app name, current version (from
 * the stmobile flavor block in build.gradle, NOT upstream defaultConfig), and explicit
 * credit + links to upstream SmartTube and this fork. This is the in-app "this is a
 * fork, not original code" disclosure.
 *
 * "Check for updates" does a real check: it queries the fork's latest GitHub release and
 * compares the tag against the installed version, rather than just opening the Releases
 * page (the upstream AppUpdateChecker points at SmartTube's own release URLs — the wrong
 * app for this fork — so it can't be reused).
 */
public class MobileAboutActivity extends AppCompatActivity {
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private boolean mChecking;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.mobile_about_activity);

        ((TextView) findViewById(R.id.about_version))
                .setText(getString(R.string.mobile_about_version, BuildConfig.VERSION_NAME));

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        findViewById(R.id.link_upstream).setOnClickListener(v ->
                openUrl(getString(R.string.mobile_about_url_upstream)));
        findViewById(R.id.link_fork).setOnClickListener(v ->
                openUrl(getString(R.string.mobile_about_url_fork)));

        findViewById(R.id.btn_check_updates).setOnClickListener(v -> checkForUpdates());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mExecutor.shutdownNow();
    }

    /**
     * Fetch the latest GitHub release tag on a worker thread and compare it to the
     * installed version. All result handling marshals back to the main thread and bails if
     * the activity is finishing. Never a dead end: a network/parse failure still offers to
     * open the Releases page.
     */
    private void checkForUpdates() {
        if (mChecking) {
            return;
        }
        mChecking = true;
        ((TextView) findViewById(R.id.btn_check_updates))
                .setText(R.string.mobile_about_checking);

        mExecutor.execute(() -> {
            String latestTag = fetchLatestTag();
            runOnUiThread(() -> {
                mChecking = false;
                if (isFinishing()) {
                    return;
                }
                ((TextView) findViewById(R.id.btn_check_updates))
                        .setText(R.string.mobile_about_check_updates);
                showResult(latestTag);
            });
        });
    }

    private void showResult(@Nullable String latestTag) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        if (latestTag == null) {
            builder.setMessage(R.string.mobile_about_check_failed)
                    .setPositiveButton(R.string.mobile_about_open_releases,
                            (d, w) -> openUrl(getString(R.string.mobile_about_url_releases)))
                    .setNegativeButton(android.R.string.cancel, null);
        } else if (latestTag.equals(BuildConfig.VERSION_NAME)) {
            builder.setMessage(getString(R.string.mobile_about_up_to_date, BuildConfig.VERSION_NAME))
                    .setPositiveButton(android.R.string.ok, null);
        } else {
            builder.setMessage(getString(R.string.mobile_about_update_available, latestTag))
                    .setPositiveButton(R.string.mobile_about_open_releases,
                            (d, w) -> openUrl(getString(R.string.mobile_about_url_releases)))
                    .setNegativeButton(android.R.string.cancel, null);
        }
        builder.show();
    }

    /**
     * @return the latest release's tag_name, or null on any network/parse failure.
     *
     * Routed through the app's shared {@link OkHttpManager} client rather than a raw
     * HttpURLConnection so it inherits the same tuned DNS/TLS/cipher/timeout setup the rest
     * of the app uses to reach YouTube — a plain HttpURLConnection is a separate, weaker
     * path that can fail on networks where the OkHttp stack succeeds.
     */
    @Nullable
    private String fetchLatestTag() {
        try (Response response = OkHttpManager.instance(false)
                .doGetRequest(getString(R.string.mobile_about_url_releases_api))) {
            if (response == null || !response.isSuccessful() || response.body() == null) {
                return null;
            }
            String tag = new JSONObject(response.body().string()).optString("tag_name", null);
            return (tag == null || tag.isEmpty()) ? null : tag;
        } catch (Exception e) {
            return null;
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }
}
