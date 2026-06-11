package com.liskovsoft.smartyoutubetv2.mobile.ui.playback;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackFragment;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.other.VideoPlayerGlue;

import java.util.ArrayList;
import java.util.List;

/**
 * Phone player fragment: adds the upright/portrait layout on top of the shared
 * {@link PlaybackFragment}. Swapped in via the stmobile override of {@code fragment_playback.xml}
 * (the activity looks the fragment up by tag and only checks {@code instanceof PlaybackFragment},
 * so the TV code is untouched).
 *
 * In portrait with a regular (non-Shorts) video the player is constrained to a 16:9 strip at the
 * top and a below-video panel (title + description + channel/stats + up-next) fills the rest; in
 * landscape, for Shorts, and in PiP the player stays full-screen exactly like the TV build. While
 * in the strip the control rows are trimmed to the core actions ({@link
 * VideoPlayerGlue#setCompactControls}) — the full set returns in landscape. The up-next list
 * mirrors the same {@link VideoGroup}s the Leanback suggestions rows receive, and a row tap goes
 * through the standard {@link PlaybackPresenter#onSuggestionItemClicked(Video)} path.
 */
public class MobilePlaybackFragment extends PlaybackFragment {
    private ConstraintLayout mRoot;
    private LinearLayout mPanel;
    private TextView mTitleView;
    private TextView mExpandView;
    private TextView mDescriptionView;
    private TextView mChannelView;
    private TextView mSubsView;
    private TextView mViewsView;
    private TextView mStatsView;
    private RecyclerView mUpNextList;
    private UpNextRowAdapter mUpNextAdapter;
    private boolean mStripMode;
    private String mLastVideoId;
    private VideoPlayerGlue mLastGlue;
    private boolean mLastCompact;
    /**
     * Suggestion groups in arrival order. In strip mode the Leanback suggestion rows are kept out
     * of the rows adapter entirely (they render inside the 16:9 strip and their cards remain
     * hit-testable under the video), so this cache replays them into the rows when the player
     * returns to full-screen.
     */
    private final List<VideoGroup> mSuggestionGroups = new ArrayList<>();

    @Override
    public void onResume() {
        super.onResume();
        applyMobileLayout();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyMobileLayout();
    }

    @Override
    public void onPIPChanged(boolean isInPIP) {
        super.onPIPChanged(isInPIP);
        applyMobileLayout();
    }

    @Override
    public void setVideo(Video video) {
        super.setVideo(video);

        if (initPanelViews()) {
            bindHeader(video);
        }
        applyMobileLayout();
    }

    private void bindHeader(Video video) {
        if (video == null) {
            return;
        }

        // New video: start with the description collapsed (but don't collapse on the periodic
        // metadata refreshes of the same video).
        if (!TextUtils.equals(mLastVideoId, video.videoId)) {
            mLastVideoId = video.videoId;
            setDescriptionExpanded(false);
        }

        mTitleView.setText(video.getTitle() != null ? video.getTitle() : "");
        mDescriptionView.setText(video.description != null ? video.description : "");
        mExpandView.setVisibility(TextUtils.isEmpty(video.description) ? View.GONE : View.VISIBLE);

        mChannelView.setText(video.getAuthor() != null ? video.getAuthor() : "");
        mSubsView.setText(video.subscriberCount != null ? video.subscriberCount : "");
        mViewsView.setText(extractViews(video));
        mStatsView.setText(buildLikes(video));
    }

    /** Views/date: the first non-author segment of "Author • views • date". */
    private String extractViews(Video video) {
        String second = Helpers.toString(video.getSecondTitle());
        String author = video.getAuthor();
        if (second != null) {
            for (String segment : second.split(Video.TERTIARY_TEXT_DELIM)) {
                segment = segment.trim();
                if (!segment.isEmpty() && (author == null || !segment.equals(author.trim()))) {
                    return segment;
                }
            }
        }
        return "";
    }

    /** "👍 1.1K • 👎 13" (whatever is available). */
    private CharSequence buildLikes(Video video) {
        List<String> parts = new ArrayList<>();
        if (video.likeCount != null) {
            parts.add(Helpers.THUMB_UP + " " + video.likeCount);
        }
        if (video.dislikeCount != null) {
            parts.add(Helpers.THUMB_DOWN + " " + video.dislikeCount);
        }
        return TextUtils.join(" " + Video.TERTIARY_TEXT_DELIM + " ", parts);
    }

    private void setDescriptionExpanded(boolean expanded) {
        if (mDescriptionView != null) {
            mDescriptionView.setVisibility(expanded ? View.VISIBLE : View.GONE);
            mExpandView.setText(expanded ? "▴" : "▾");
        }
    }

    @Override
    public void updateSuggestions(VideoGroup group) {
        // In strip mode the rows stay empty — see mSuggestionGroups.
        if (!mStripMode) {
            super.updateSuggestions(group);
        }

        if (group == null || group.isEmpty() || group.getAction() == VideoGroup.ACTION_SYNC) {
            return; // SYNC = metadata refresh of existing items; the rows don't show live counters
        }

        mSuggestionGroups.add(group);

        // Chapters and Shorts shelves are rows-only content — the panel is a plain up-next list.
        if (group.isShorts() || group.isChapters()) {
            return;
        }

        if (initPanelViews()) {
            if (group.getAction() == VideoGroup.ACTION_REPLACE) {
                mUpNextAdapter.clear();
            }
            mUpNextAdapter.appendVideos(filterCurrent(group.getVideos()));
        }
    }

    @Override
    public void removeSuggestions(VideoGroup group) {
        super.removeSuggestions(group);

        if (group != null) {
            mSuggestionGroups.remove(group);
            if (initPanelViews()) {
                mUpNextAdapter.remove(group.getVideos());
            }
        }
    }

    @Override
    public void clearSuggestions() {
        super.clearSuggestions();

        mSuggestionGroups.clear();
        if (initPanelViews()) {
            mUpNextAdapter.clear();
        }
    }

    /** The suggestions usually lead with the video that is already playing — skip it. */
    private List<Video> filterCurrent(List<Video> videos) {
        Video current = PlaybackPresenter.instance(getContext()).getVideo();
        if (videos == null || current == null || current.videoId == null) {
            return videos;
        }

        List<Video> result = new ArrayList<>(videos.size());
        for (Video video : videos) {
            if (video != null && !current.videoId.equals(video.videoId)) {
                result.add(video);
            }
        }
        return result;
    }

    @Override
    public void onDispatchTouchEvent(MotionEvent event) {
        // In portrait the activity forwards every touch here (overlay tickle / double-tap seek).
        // Touches below the 16:9 player strip belong to the up-next panel — ignore them so
        // scrolling the list doesn't keep waking the player overlay.
        View playerView = getView();
        if (mStripMode && playerView != null && event.getY() > playerView.getBottom()) {
            return;
        }

        super.onDispatchTouchEvent(event);
    }

    /**
     * Touch fix for the faded overlay: Leanback "hides" the controls by fading them out, leaving
     * every button VISIBLE and hit-testable (fine on TV — no touchscreen). A tap on the supposedly
     * empty video could press an invisible control (verified on device: a center tap fired the
     * skip-next button's performClick, jumping to the next video). YouTube semantics instead: while
     * the overlay is hidden a tap only reveals it. Called from the activity's dispatchTouchEvent;
     * returns true to consume the event (tickle/double-tap handling still runs via
     * {@link #onDispatchTouchEvent}).
     */
    public boolean interceptPlayerTouch(MotionEvent event) {
        View playerView = getView();
        if (playerView == null || isOverlayShown()) {
            return false;
        }

        // Below the player strip = the up-next panel; let it have the touch.
        if (event.getY() > playerView.getBottom()) {
            return false;
        }

        onDispatchTouchEvent(event); // overlay tickle + double-tap seek
        return true;
    }

    /** Re-evaluate and apply strip vs full-screen layout. Safe to call at any time. */
    public void applyMobileLayout() {
        if (!initPanelViews()) {
            return;
        }

        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        Video video = PlaybackPresenter.instance(getContext()).getVideo();
        boolean isShorts = video != null && video.isShorts;
        boolean inPip = isInPipMode();

        boolean strip = portrait && !isShorts && !inPip && video != null;

        syncCompactControls(strip);

        applyOverlayDecorVisibility(strip);

        if (strip == mStripMode) {
            return;
        }
        mStripMode = strip;

        // Leanback suggestion rows: removed while in the strip (they draw inside the small video
        // area and stay clickable underneath it), replayed from the cache on return to full-screen.
        if (strip) {
            super.clearSuggestions();
        } else {
            for (VideoGroup group : mSuggestionGroups) {
                super.updateSuggestions(group);
            }
        }

        ConstraintSet set = new ConstraintSet();
        set.clone(mRoot);
        if (strip) {
            set.clear(R.id.playback_controls_fragment, ConstraintSet.BOTTOM);
            set.setDimensionRatio(R.id.playback_controls_fragment, "H,16:9");
            set.setVisibility(R.id.mobile_below_video_panel, View.VISIBLE);
        } else {
            set.connect(R.id.playback_controls_fragment, ConstraintSet.BOTTOM,
                    ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);
            set.setDimensionRatio(R.id.playback_controls_fragment, null);
            set.setVisibility(R.id.mobile_below_video_panel, View.GONE);
        }
        set.applyTo(mRoot);
    }

    @Override
    public void showControlsOverlay(boolean runAnimation) {
        super.showControlsOverlay(runAnimation);
        // The overlay row views are created lazily on the first reveal — re-apply here so the
        // strip tweaks land no matter when the views appear.
        applyOverlayDecorVisibility(mStripMode);
    }

    /**
     * The overlay's metadata block (title/views/date over the video) duplicates the header below
     * the strip, and the quality badge + clock collide with the buttons in the narrow strip —
     * hide all three there; landscape full-screen keeps them (no header exists).
     */
    private void applyOverlayDecorVisibility(boolean strip) {
        if (getActivity() == null) {
            return;
        }
        for (int id : new int[]{R.id.controls_card, R.id.quality_info, R.id.date_time}) {
            View view = getActivity().findViewById(id);
            if (view != null) {
                view.setVisibility(strip ? View.GONE : View.VISIBLE);
            }
        }
    }

    /**
     * Trim/restore the control rows for the current mode. The glue is recreated with the full
     * action set on every engine init, so re-apply whenever the instance or the mode changes.
     */
    private void syncCompactControls(boolean compact) {
        VideoPlayerGlue glue = getPlayerGlue();
        if (glue != null && (glue != mLastGlue || compact != mLastCompact)) {
            glue.setCompactControls(compact);
            mLastGlue = glue;
            mLastCompact = compact;
        }
    }

    private boolean isInPipMode() {
        Activity activity = getActivity();
        return VERSION.SDK_INT >= VERSION_CODES.N && activity != null && activity.isInPictureInPictureMode();
    }

    /**
     * The panel views are siblings of this fragment in the activity layout and don't exist yet
     * while the fragment itself is being inflated — resolve them lazily.
     */
    private boolean initPanelViews() {
        if (mPanel != null) {
            return true;
        }

        Activity activity = getActivity();
        if (activity == null) {
            return false;
        }

        mRoot = activity.findViewById(R.id.mobile_playback_root);
        mPanel = activity.findViewById(R.id.mobile_below_video_panel);
        mTitleView = activity.findViewById(R.id.mobile_video_title);
        mExpandView = activity.findViewById(R.id.mobile_video_expand);
        mDescriptionView = activity.findViewById(R.id.mobile_video_description);
        mChannelView = activity.findViewById(R.id.mobile_video_channel);
        mSubsView = activity.findViewById(R.id.mobile_video_subs);
        mViewsView = activity.findViewById(R.id.mobile_video_views);
        mStatsView = activity.findViewById(R.id.mobile_video_stats);
        mUpNextList = activity.findViewById(R.id.mobile_up_next_list);

        if (mPanel == null || mRoot == null) {
            mPanel = null;
            return false;
        }

        View titleRow = activity.findViewById(R.id.mobile_video_title_row);
        titleRow.setOnClickListener(v ->
                setDescriptionExpanded(mDescriptionView.getVisibility() != View.VISIBLE));

        mUpNextAdapter = new UpNextRowAdapter(
                video -> PlaybackPresenter.instance(getContext()).onSuggestionItemClicked(video));
        mUpNextList.setLayoutManager(new LinearLayoutManager(getContext()));
        mUpNextList.setAdapter(mUpNextAdapter);

        return true;
    }
}
