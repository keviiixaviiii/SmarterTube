package com.liskovsoft.smartyoutubetv2.mobile.ui.base;

import android.content.res.Resources;
import android.os.Bundle;
import android.util.DisplayMetrics;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Base for phone (stmobile) activities.
 *
 * Applies the AppCompat phone theme instead of MotherActivity's Leanback TV theme, and
 * restores the device's true display density (MotherActivity scales density for 10-foot
 * TV layouts, which makes everything tiny on a phone).
 */
public abstract class MobileActivity extends MotherActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoreRealDensity();
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreRealDensity();
        // Register in the ViewManager activity stack (the TV LeanbackActivity does the same).
        // Without this the stack only tracks TV/Leanback activities, so the player's
        // startParentView() can't see a phone screen as its caller and falls back to Home.
        getViewManager().addTop(this);
    }

    @Override
    protected void initTheme() {
        setTheme(R.style.Theme_SmarterTube_Mobile);
    }

    protected void restoreRealDensity() {
        DisplayMetrics real = Resources.getSystem().getDisplayMetrics();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        dm.density = real.density;
        dm.scaledDensity = real.scaledDensity;
        dm.densityDpi = real.densityDpi;
    }
}
