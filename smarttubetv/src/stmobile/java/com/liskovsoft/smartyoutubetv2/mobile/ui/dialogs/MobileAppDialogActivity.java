package com.liskovsoft.smartyoutubetv2.mobile.ui.dialogs;

import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.mobile.ui.base.MobileActivity;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Phone-native host for the settings dialog. Replaces the TV
 * {@code tv.ui.dialogs.AppDialogActivity} (Leanback infinite-scroll preferences) for the
 * stmobile flavor — wired in
 * {@link com.liskovsoft.smartyoutubetv2.mobile.ui.main.MobileApplication}.
 */
public class MobileAppDialogActivity extends MobileActivity {
    private boolean mPanelMode;

    // One-shot suppression deadline (epoch millis). When set and still fresh, the NEXT
    // dialog launch finishes itself immediately instead of showing. Used by the sign-in
    // flow to swallow the redundant single-account picker that YTSignInPresenter force-
    // shows after a fresh sign-in (see MobileSignInFragment.close()). A timestamp deadline
    // (not a bare boolean) self-expires, so a stale flag can never eat a later legitimate
    // dialog if the expected picker never launches.
    private static volatile long sSuppressDialogUntil;
    private static final long SUPPRESS_WINDOW_MS = 4000;

    /** Arm a one-shot suppression of the next dialog launched within the next few seconds. */
    public static void suppressNextDialog() {
        sSuppressDialogUntil = System.currentTimeMillis() + SUPPRESS_WINDOW_MS;
    }

    /**
     * In comments panel mode, apply the translucent panel theme as the LAST setTheme of the
     * super.onCreate chain. MotherActivity.onCreate calls initTheme(), and MobileActivity
     * overrides it to set the opaque brand theme — if we only setTheme() before super.onCreate
     * that opaque theme wins and kills windowIsTranslucent (black fill above the comments).
     * Setting it here ensures translucency survives. (FitSystemWindows, applied after this by
     * MotherActivity, only touches the status/nav bars, not windowIsTranslucent/background.)
     */
    @Override
    protected void initTheme() {
        if (mPanelMode) {
            setTheme(R.style.Theme_SmarterTube_Mobile_TranslucentPanel);
        } else {
            super.initTheme();
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Comments over the portrait player: translucent window inset below the 16:9 video
        // strip, so the video stays visible (and keeps playing — the player activity only
        // pauses under a translucent window instead of stopping). Must be decided before
        // super.onCreate: windowIsTranslucent can't change after the window exists, and the
        // actual setTheme happens in our initTheme() override (called from super.onCreate),
        // which runs AFTER MobileActivity.initTheme() would otherwise re-apply the opaque
        // brand theme and clobber translucency.
        mPanelMode = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT
                && AppDialogPresenter.instance(this).isComments();

        super.onCreate(savedInstanceState);

        if (mPanelMode) {
            // Kill the default slide-in/out; the panel should appear in place over the player.
            overridePendingTransition(0, 0);
        }

        // Consume a pending one-shot suppression: if armed and still fresh, this dialog is
        // the redundant post-sign-in account picker — finish before showing anything.
        long deadline = sSuppressDialogUntil;
        sSuppressDialogUntil = 0;
        if (deadline != 0 && System.currentTimeMillis() < deadline) {
            finish();
            return;
        }

        setContentView(R.layout.mobile_app_dialog_activity);

        if (mPanelMode) {
            applyPanelInsets();
        }

        if (getSupportFragmentManager().findFragmentById(R.id.mobile_app_dialog_root) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.mobile_app_dialog_root, new MobileAppDialogFragment())
                    .commit();
        }
    }

    /**
     * Panel mode: the dialog content starts below the player's 16:9 strip; the transparent
     * area above it shows the still-playing video, and tapping it closes the comments.
     */
    private void applyPanelInsets() {
        View container = findViewById(R.id.mobile_app_dialog_root);
        if (container == null) {
            return;
        }

        // The container is already inset below the status bar (fitsSystemWindows pushes the
        // activity content down by the status-bar height), so its top already sits at the bar's
        // bottom. The portrait player draws the video as a 16:9 strip starting just below the
        // status bar, so the panel only needs to drop by the strip's HEIGHT — adding the status
        // bar again would double-count it and leave the player header peeking through the gap.
        // width * 9 / 16 = strip height.
        int stripHeight = getResources().getDisplayMetrics().widthPixels * 9 / 16;

        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) container.getLayoutParams();
        lp.topMargin = stripHeight;
        container.setLayoutParams(lp);

        // Tap on the visible video above the panel = close the comments.
        View content = findViewById(android.R.id.content);
        if (content != null) {
            content.setOnClickListener(v -> finish());
        }
    }

    @Override
    public void onBackPressed() {
        // A nested comments replies thread is one back-level inside the fragment: restore the
        // top-level comments instead of closing the dialog.
        MobileAppDialogFragment fragment = (MobileAppDialogFragment)
                getSupportFragmentManager().findFragmentById(R.id.mobile_app_dialog_root);
        if (fragment != null && fragment.canGoBack()) {
            fragment.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void finish() {
        // Mirror TV AppDialogActivity: fire the presenter's onFinish callbacks before the
        // real finish, regardless of whether finish() came from back or from a programmatic
        // closeDialog(). onViewDestroyed() will run afterwards via the fragment's onDestroy.
        MobileAppDialogFragment fragment = (MobileAppDialogFragment)
                getSupportFragmentManager().findFragmentById(R.id.mobile_app_dialog_root);
        if (fragment != null) {
            fragment.onFinishCallback();
        }
        super.finish();
        if (mPanelMode) {
            // Match the no-animation entrance: panel closes in place, no slide-out.
            overridePendingTransition(0, 0);
        }
    }
}
