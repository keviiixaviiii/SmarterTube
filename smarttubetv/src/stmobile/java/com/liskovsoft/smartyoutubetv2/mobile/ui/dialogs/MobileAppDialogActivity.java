package com.liskovsoft.smartyoutubetv2.mobile.ui.dialogs;

import android.os.Bundle;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.mobile.ui.base.MobileActivity;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Phone-native host for the settings dialog. Replaces the TV
 * {@code tv.ui.dialogs.AppDialogActivity} (Leanback infinite-scroll preferences) for the
 * stmobile flavor — wired in
 * {@link com.liskovsoft.smartyoutubetv2.mobile.ui.main.MobileApplication}.
 */
public class MobileAppDialogActivity extends MobileActivity {
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

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Consume a pending one-shot suppression: if armed and still fresh, this dialog is
        // the redundant post-sign-in account picker — finish before showing anything.
        long deadline = sSuppressDialogUntil;
        sSuppressDialogUntil = 0;
        if (deadline != 0 && System.currentTimeMillis() < deadline) {
            finish();
            return;
        }

        setContentView(R.layout.mobile_app_dialog_activity);

        if (getSupportFragmentManager().findFragmentById(R.id.mobile_app_dialog_root) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.mobile_app_dialog_root, new MobileAppDialogFragment())
                    .commit();
        }
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
    }
}
