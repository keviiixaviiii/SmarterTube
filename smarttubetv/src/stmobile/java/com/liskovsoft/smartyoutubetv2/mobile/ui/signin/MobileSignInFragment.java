package com.liskovsoft.smartyoutubetv2.mobile.ui.signin;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.fragment.app.Fragment;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.SignInPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.YTSignInPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SignInView;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.mobile.ui.browse.MobileBrowseActivity;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Single-phone sign-in screen. Implements {@link SignInView}, driven directly by
 * {@link YTSignInPresenter} (the OAuth device-code flow).
 *
 * The device-code flow is mandatory — it is the only flow that yields InnerTube-compatible
 * credentials, and Google blocks OAuth inside embedded WebViews. To make it seamless on a
 * single phone, the code is pre-filled into the URL and opened in an in-app Chrome Custom
 * Tab: the user signs in and approves without leaving the app, then the presenter's poll
 * detects completion and closes this screen automatically.
 */
public class MobileSignInFragment extends Fragment implements SignInView {
    private SignInPresenter mPresenter;
    private View mInstructionsView;
    private View mCodeLabelView;
    private TextView mCodeView;
    private Button mBrowserButton;
    private Button mDoneButton;
    private LinearLayout mErrorBlock;
    private TextView mErrorBody;
    private String mFullSignInUrl;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Use YTSignInPresenter directly. The base SignInPresenter is only a dispatcher:
        // it drives nothing unless a sub-presenter was pre-"armed" by a start() call,
        // which never happens when the screen is opened via startView(). Going direct
        // runs the OAuth device-code flow (updateUserCode) immediately.
        mPresenter = YTSignInPresenter.instance(getContext());
        mPresenter.setView(this);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.mobile_signin_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mInstructionsView = view.findViewById(R.id.signin_instructions);
        mCodeLabelView = view.findViewById(R.id.signin_code_label);
        mCodeView = view.findViewById(R.id.signin_code);
        mBrowserButton = view.findViewById(R.id.signin_browser_button);
        mDoneButton = view.findViewById(R.id.signin_done_button);
        mErrorBlock = view.findViewById(R.id.signin_error_block);
        mErrorBody = view.findViewById(R.id.signin_error_body);

        // Disabled until the device code arrives (mFullSignInUrl is set in showCode()).
        mBrowserButton.setEnabled(false);
        mBrowserButton.setOnClickListener(v -> openInBrowser());
        // Manual escape hatch: if the user completed sign-in in the Custom Tab and the
        // polling-detected auto-return is slow, this brings the app's task forward.
        // Polling continues; close() still fires on success.
        mDoneButton.setOnClickListener(v -> returnToApp());

        view.findViewById(R.id.signin_switch_dns_button).setOnClickListener(v -> switchDnsAndRestart());
        view.findViewById(R.id.signin_retry_button).setOnClickListener(v -> retrySignIn());

        mPresenter.onViewInitialized();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mPresenter != null) {
            mPresenter.onViewDestroyed();
        }
    }

    @Override
    public void showCode(String userCode, String signInUrl) {
        showCode(userCode, signInUrl, null);
    }

    @Override
    public void showCode(String userCode, String signInUrl, String fullSignInUrl) {
        if (!isAdded()) {
            return;
        }

        // YTSignInPresenter routes errors through this same callback with an empty
        // signInUrl and the error message in the userCode slot (see
        // YTSignInPresenter.updateUserCode -> error -> showCode(error.getMessage(), "")).
        // Treat that as the failure path and surface a recovery UI instead of a code.
        if (TextUtils.isEmpty(signInUrl) && fullSignInUrl == null) {
            showError(userCode);
            return;
        }
        if (TextUtils.isEmpty(userCode)) {
            return;
        }

        hideError();
        // YTSignInPresenter supplies a ready-to-open activation URL (code pre-filled) as
        // fullSignInUrl. Fall back to hand-building it from signInUrl + user_code only if
        // the presenter ever calls the 2-arg overload.
        mFullSignInUrl = fullSignInUrl != null
                ? fullSignInUrl
                : signInUrl + "?user_code=" + userCode.replace(" ", "-");
        mCodeView.setText(userCode);
        mBrowserButton.setEnabled(true);
    }

    private void showError(@Nullable String message) {
        String safeMessage = message != null ? message : "";
        boolean looksLikeDns =
                safeMessage.contains("UnknownHostException")
                        || safeMessage.contains("Unable to resolve host")
                        || safeMessage.contains("No address associated with hostname");

        mErrorBody.setText(getString(
                looksLikeDns ? R.string.mobile_signin_error_dns : R.string.mobile_signin_error_generic,
                safeMessage));
        mErrorBlock.setVisibility(View.VISIBLE);

        // Hide the happy-path elements so the user is not staring at an empty code +
        // a "Continue" button that would just open the activation page with no code.
        if (mInstructionsView != null) mInstructionsView.setVisibility(View.GONE);
        if (mCodeLabelView != null) mCodeLabelView.setVisibility(View.GONE);
        mCodeView.setVisibility(View.GONE);
        mBrowserButton.setVisibility(View.GONE);
        mDoneButton.setVisibility(View.GONE);
    }

    private void hideError() {
        if (mErrorBlock == null || mErrorBlock.getVisibility() == View.GONE) {
            return;
        }
        mErrorBlock.setVisibility(View.GONE);
        if (mInstructionsView != null) mInstructionsView.setVisibility(View.VISIBLE);
        if (mCodeLabelView != null) mCodeLabelView.setVisibility(View.VISIBLE);
        mCodeView.setVisibility(View.VISIBLE);
        mBrowserButton.setVisibility(View.VISIBLE);
    }

    /**
     * Escalate the OkHttp DNS preference to Google DNS and restart the app. Mirrors
     * the auto-recovery in {@code BrowsePresenter} for "No address associated with
     * hostname" failures: OkHttp builds its client lazily and caches it, so a process
     * restart is the only reliable way to pick up the new resolver.
     */
    private void switchDnsAndRestart() {
        if (getContext() == null) {
            return;
        }
        PlayerTweaksData.instance(getContext()).setPreferredDnsType(PlayerTweaksData.DNS_TYPE_GOOGLE);
        Utils.restartTheApp(getContext());
    }

    /**
     * Re-arm the device-code observable from scratch. The previous attempt errored
     * and the observable terminated; calling {@code onViewInitialized()} disposes the
     * dead disposable and calls {@code updateUserCode()} again, yielding a fresh code.
     */
    private void retrySignIn() {
        if (mPresenter == null) {
            return;
        }
        hideError();
        mBrowserButton.setEnabled(false);
        mCodeView.setText("");
        mPresenter.onViewInitialized();
    }

    @Override
    public void close() {
        returnToApp();
        Activity activity = getActivity();
        if (activity != null) {
            activity.finish();
        }
    }

    /**
     * Bring the app's task to the foreground. The Custom Tab opens YouTube's
     * activation page in its OWN task (Chrome's), so {@code CLEAR_TOP} alone does not
     * cover it — {@code REORDER_TO_FRONT} pulls our task on top of Chrome's. Called
     * both on polling success (via {@link #close()}) and from the manual "Done? Return
     * to app" escape hatch.
     */
    private void returnToApp() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        Intent intent = new Intent(activity, MobileBrowseActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        activity.startActivity(intent);
    }

    /**
     * Opens the activation URL in an in-app Chrome Custom Tab. Falls back to an external
     * browser if no Custom Tabs provider is available. Reveals the "Done? Return to app"
     * escape hatch once the tab is launched.
     */
    private void openInBrowser() {
        if (mFullSignInUrl == null || getContext() == null) {
            return;
        }
        try {
            new CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                    .launchUrl(getContext(), Uri.parse(mFullSignInUrl));
        } catch (Exception e) {
            Utils.openLinkExt(getContext(), mFullSignInUrl);
        }
        if (mDoneButton != null) {
            mDoneButton.setVisibility(View.VISIBLE);
        }
    }
}
