package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.session.MediaSessionCompat;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

public class RemoteControlService extends Service {
    private static final String TAG = RemoteControlService.class.getSimpleName();
    private static final int NOTIFICATION_ID = RemoteControlService.class.hashCode();
    public static final String EXTRA_SESSION_TOKEN = "media_session_token";

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind: %s", Helpers.toString(intent));

        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // https://stackoverflow.com/questions/46445265/android-8-0-java-lang-illegalstateexception-not-allowed-to-start-service-inten
        // NOTE: it's impossible to hide notification on Android 9 and above
        // https://stackoverflow.com/questions/10962418/how-to-startforeground-without-showing-notification
        try {
            startForeground(NOTIFICATION_ID, createNotification());
        } catch (Exception e) {
            // NullPointerException: Attempt to read from field 'int com.android.server.am.UidRecord.curProcState' on a null object reference
            // ForegroundServiceStartNotAllowedException: Service.startForeground() not allowed due to mAllowStartForeground false (Android 14)
            e.printStackTrace();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand: %s", Helpers.toString(intent));

        PlaybackPresenter.instance(getApplicationContext()); // init RemoteControlListener
        StreamReminderService.instance(getApplicationContext()).startStop(); // init reminder service

        // Phone flavor passes a MediaSession token so we can post a MediaStyle notification
        // that Android surfaces on the lockscreen. TV omits the extra → plain notification.
        MediaSessionCompat.Token token = intent != null
                ? (MediaSessionCompat.Token) intent.getParcelableExtra(EXTRA_SESSION_TOKEN)
                : null;
        if (token != null) {
            startForeground(NOTIFICATION_ID, createMediaStyleNotification(token));
        }

        return START_STICKY;
    }

    private Notification createNotification() {
        String remoteControl = getString(R.string.settings_remote_control);
        String serviceStarted = getString(R.string.background_service_started);

        return Utils.createNotification(
                getApplicationContext(),
                getApplicationInfo().icon,
                String.format("%s: %s", remoteControl, serviceStarted),
                ViewManager.instance(getApplicationContext()).getRootActivity());
    }

    private Notification createMediaStyleNotification(MediaSessionCompat.Token token) {
        String channelId = getPackageName() + "_media";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    channelId, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }

        // Fall back to the app name only when no video is loaded yet; the MediaSession metadata
        // drives the lockscreen media control, but these fields fill the notification-shade header.
        Video video = PlaybackPresenter.instance(getApplicationContext()).getVideo();
        String title = video != null && video.getTitle() != null ? video.getTitle() : getString(R.string.app_name);
        String author = video != null ? video.getAuthor() : null;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(title)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setStyle(new MediaStyle()
                        .setMediaSession(token)
                        .setShowActionsInCompactView());

        if (author != null) {
            builder.setContentText(author);
        }

        return builder.build();
    }
}
