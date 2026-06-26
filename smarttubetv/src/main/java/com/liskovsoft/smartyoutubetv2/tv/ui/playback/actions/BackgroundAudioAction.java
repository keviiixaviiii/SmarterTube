package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.Action;
import com.liskovsoft.smartyoutubetv2.tv.R;

public class BackgroundAudioAction extends Action {
    public BackgroundAudioAction(Context context) {
        super(R.id.action_background_audio);
        Drawable uncoloredDrawable = ContextCompat.getDrawable(context, R.drawable.action_background_audio);
        setIcon(uncoloredDrawable);
        setLabel1(context.getString(R.string.action_background_audio));
    }
}
