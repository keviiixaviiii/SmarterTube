package com.liskovsoft.smartyoutubetv2.mobile.ui.playback;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Handler;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.SuggestionsController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerUI;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackFragment;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.other.VideoPlayerGlue;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

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
    /** Applied layout: 0 = full-screen, 1 = regular 16:9 strip, 2 = Shorts 9:16 strip. */
    private int mLayoutState;
    private String mLastVideoId;
    private VideoPlayerGlue mLastGlue;
    private boolean mLastCompact;

    // Shorts-specific chrome (action rail + back button + info bar below video).
    private View mShortsActionRail;
    private ImageButton mShortsBackBtn;
    private LinearLayout mShortsInfoBar;
    private TextView mShortsTitleView;
    private TextView mShortsChannelView;
    // Action rail buttons — resolved lazily from within the included layout.
    private ImageView mShortsLikeBtn;
    private TextView mShortsLikeCount;
    private ImageView mShortsDislikeBtn;
    private ImageView mShortsCommentsBtn;
    private TextView mShortsCommentsCount;
    private ImageView mShortsChannelBtn;

    // Centred play/pause indicator (Shorts only — visual only, not a button).
    private ImageView mShortsPlayPauseBtn;

    // Bottom navigation bar and "You" profile sheet (Shorts only).
    private View mShortsNavBar;
    private View mShortsProfileScrim;
    private LinearLayout mShortsProfileSheet;

    // Full-page swipe pager: all views that translate together as one Shorts page.
    // Nav bar is intentionally excluded — it stays fixed at the bottom like YT Shorts.
    private View[] mVideoPageViews;
    // Filmstrip posters: full-screen thumbnails of the prev/next Short, parked one screen above
    // / below and slid in with the drag so the adjacent video is visible while scrolling.
    private ImageView mShortsNextPoster;
    private ImageView mShortsPrevPoster;
    // +1 = swipe-up / next, -1 = swipe-down / prev, 0 = no pending animation.
    private int mLastSwipeDirection;

    // Raw touch drag tracking for the Shorts pager (ACTION_DOWN/MOVE/UP in interceptPlayerTouch).
    private float   mSwipeRawStartY;
    private float   mSwipeRawStartX;
    private long    mTouchDownTime;
    private boolean mShortsSwipeDragging;
    private int     mDragThresholdPx; // initialised to 15dp in initShortsViews

    // While committing a swipe, the incoming poster stays on screen covering the (loading) surface
    // until the new video is actually rendering — eliminates the black flash. A timeout is the
    // safety net in case the play signal never arrives (e.g. unplayable video).
    private boolean mAwaitingShortsFrame;
    private String  mSwipeFromVideoId;
    private static final int SHORTS_FRAME_POLL_MS = 50;
    private static final int SHORTS_FRAME_TIMEOUT_MS = 2500;
    // Tint applied to the rail like/dislike icon when active (YouTube blue).
    private static final int SHORTS_ACTIVE_TINT = 0xFF3EA6FF;

    // Auto-hide for the Shorts overlay chrome (rail + back button).
    private final Handler mChromeHandler = new Handler();
    private final Runnable mHideShortsChr = () -> setShortsChrome(false);

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
    public void onPause() {
        super.onPause();
        mChromeHandler.removeCallbacks(mHideShortsChr);
        mChromeHandler.removeCallbacks(mShortsFramePoll);
        mChromeHandler.removeCallbacks(mShortsFrameTimeout);
        mAwaitingShortsFrame = false;
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

        // Committed Short arrived: the exit animation parked the incoming poster at screen-centre
        // showing this video's thumbnail. Snap the live surface back to centre *underneath* the
        // poster and keep the poster up — it covers the load until the video actually renders
        // (see awaitShortsFrame), so there's no black flash.
        if (mLayoutState == 2 && mLastSwipeDirection != 0) {
            resetPageViewsToZero();
            ImageView keep = mLastSwipeDirection > 0 ? mShortsNextPoster : mShortsPrevPoster;
            ImageView drop = mLastSwipeDirection > 0 ? mShortsPrevPoster : mShortsNextPoster;
            if (drop != null) {
                drop.animate().cancel();
                drop.setVisibility(View.INVISIBLE);
                drop.setImageDrawable(null);
            }
            if (keep != null) {
                keep.animate().cancel();
                keep.setTranslationY(0); // hold centred, covering the loading surface
            }
            mLastSwipeDirection = 0;
        }
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

        bindShortsChrome(video);
    }

    private void bindShortsChrome(Video video) {
        if (mShortsTitleView != null) {
            mShortsTitleView.setText(video.getTitle() != null ? video.getTitle() : "");
        }
        if (mShortsChannelView != null) {
            mShortsChannelView.setText(video.getAuthor() != null ? video.getAuthor() : "");
        }
        if (mShortsLikeCount != null) {
            mShortsLikeCount.setText(video.likeCount != null ? video.likeCount : "");
        }
        // Reflect the like/dislike state if metadata is already known (async updates come through
        // the setButtonState override).
        syncLikeDislikeTint();
    }

    /** Tint the rail like/dislike icons to match the current button state. */
    private void syncLikeDislikeTint() {
        if (mShortsLikeBtn != null) {
            tintRailButton(mShortsLikeBtn, getButtonState(R.id.action_thumbs_up) == PlayerUI.BUTTON_ON);
        }
        if (mShortsDislikeBtn != null) {
            tintRailButton(mShortsDislikeBtn, getButtonState(R.id.action_thumbs_down) == PlayerUI.BUTTON_ON);
        }
    }

    private void tintRailButton(ImageView btn, boolean active) {
        if (active) {
            btn.setColorFilter(SHORTS_ACTIVE_TINT);
        } else {
            btn.clearColorFilter();
        }
    }

    private int currentButtonState(int buttonId) {
        return getButtonState(buttonId) == PlayerUI.BUTTON_ON ? PlayerUI.BUTTON_ON : PlayerUI.BUTTON_OFF;
    }

    /** Mirror Leanback button-state changes (metadata load + click toggles) onto the rail icons. */
    @Override
    public void setButtonState(int buttonId, int buttonState) {
        super.setButtonState(buttonId, buttonState);
        if (buttonId == R.id.action_thumbs_up && mShortsLikeBtn != null) {
            tintRailButton(mShortsLikeBtn, buttonState == PlayerUI.BUTTON_ON);
        } else if (buttonId == R.id.action_thumbs_down && mShortsDislikeBtn != null) {
            tintRailButton(mShortsDislikeBtn, buttonState == PlayerUI.BUTTON_ON);
        }
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
        // Touches below the 16:9 player strip belong to the up-next panel — ignore them.
        View playerView = getView();
        if (mStripMode && playerView != null && event.getY() > playerView.getBottom()) {
            return;
        }
        // In Shorts mode all touch is managed by interceptPlayerTouch (drag pager + tap-toggle).
        // Returning here prevents Leanback's double-tap seek from firing in Shorts.
        if (mLayoutState == 2) {
            return;
        }
        super.onDispatchTouchEvent(event);
    }

    /**
     * Touch fix for the faded overlay: Leanback "hides" the controls by fading them out, leaving
     * every button VISIBLE and hit-testable (fine on TV — no touchscreen). A tap on the supposedly
     * empty video could press an invisible control. In Shorts mode this also drives the vertical
     * pager drag and the tap-to-toggle play/pause behavior.
     * Called from the activity's dispatchTouchEvent; returns true to consume the event.
     */
    public boolean interceptPlayerTouch(MotionEvent event) {
        View playerView = getView();
        if (playerView == null) return false;

        // Shorts: all touch is managed here (drag pager + tap-to-toggle).
        if (mLayoutState == 2) {
            return handleShortsTouchEvent(event);
        }

        // Non-Shorts: original phantom-tap guard.
        if (isOverlayShown()) return false;
        if (event.getY() > playerView.getBottom()) return false;
        onDispatchTouchEvent(event); // overlay tickle + double-tap seek
        return true;
    }

    private boolean handleShortsTouchEvent(MotionEvent event) {
        // Profile sheet open: taps on the sheet reach its row / handle listeners; a tap anywhere
        // else dismisses it. The pager and play-toggle stay disabled while the sheet is up.
        if (mShortsProfileSheet != null && mShortsProfileSheet.getVisibility() == View.VISIBLE) {
            if (viewContainsRaw(mShortsProfileSheet, event.getRawX(), event.getRawY())) {
                return false; // let the sheet handle it
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                closeShortsProfileSheet();
            }
            return true; // consume everything outside the sheet
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mSwipeRawStartY = event.getY();
                mSwipeRawStartX = event.getX();
                mTouchDownTime = android.os.SystemClock.uptimeMillis();
                mShortsSwipeDragging = false;
                return false; // let DOWN propagate so button OnClickListeners still work

            case MotionEvent.ACTION_MOVE: {
                float dy = event.getY() - mSwipeRawStartY;
                if (!mShortsSwipeDragging && Math.abs(dy) > mDragThresholdPx) {
                    mShortsSwipeDragging = true;
                    mChromeHandler.removeCallbacks(mHideShortsChr);
                    setShortsChrome(false);
                    // Abandon any in-flight transition from a previous swipe before starting fresh.
                    mAwaitingShortsFrame = false;
                    mChromeHandler.removeCallbacks(mShortsFramePoll);
                    mChromeHandler.removeCallbacks(mShortsFrameTimeout);
                    prepareShortsPosters(); // load prev/next thumbnails for the filmstrip
                }
                if (mShortsSwipeDragging) {
                    setShortsPageTranslation(dy);
                    return true;
                }
                return false;
            }

            case MotionEvent.ACTION_UP: {
                if (mShortsSwipeDragging) {
                    mShortsSwipeDragging = false;
                    onShortsSwipeReleased(event.getY() - mSwipeRawStartY);
                    return true;
                }
                float dY = Math.abs(event.getY() - mSwipeRawStartY);
                float dX = Math.abs(event.getX() - mSwipeRawStartX);
                long dur = android.os.SystemClock.uptimeMillis() - mTouchDownTime;
                if (dY < mDragThresholdPx && dX < mDragThresholdPx && dur < 350) {
                    if (touchHitsButton(event.getRawX(), event.getRawY())) {
                        return false; // let normal dispatch fire the button's onClick
                    }
                    onShortsTap();
                    revealShortsChrome();
                    return true;
                }
                return false;
            }

            case MotionEvent.ACTION_CANCEL:
                if (mShortsSwipeDragging) {
                    mShortsSwipeDragging = false;
                    animateShortsPageTo(0, this::hideShortsPosters);
                }
                return false;
        }
        return false;
    }

    private boolean touchHitsButton(float rawX, float rawY) {
        return viewContainsRaw(mShortsActionRail, rawX, rawY)
            || viewContainsRaw(mShortsBackBtn, rawX, rawY)
            || viewContainsRaw(mShortsNavBar, rawX, rawY)
            || (mShortsProfileSheet != null
                && mShortsProfileSheet.getVisibility() == View.VISIBLE
                && viewContainsRaw(mShortsProfileSheet, rawX, rawY));
    }

    private boolean viewContainsRaw(View v, float rawX, float rawY) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX <= loc[0] + v.getWidth()
            && rawY >= loc[1] && rawY <= loc[1] + v.getHeight();
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

        // Strip mode now also covers Shorts: the player becomes a top-aligned aspect-ratio strip
        // instead of a vertically-centered full-screen surface. Regular videos get a 16:9 strip with
        // the up-next panel below; a Short gets a 9:16 strip with the Shorts info bar + action rail.
        boolean strip = portrait && !inPip && video != null;
        boolean showPanel = strip && !isShorts;
        String ratio = isShorts ? "H,9:16" : "H,16:9";

        syncCompactControls(strip);

        applyOverlayDecorVisibility(strip);

        // Keyed on the 3-value state (not just the boolean) so a regular<->Shorts switch — both of
        // which are "strip" — still re-applies the new dimension ratio.
        int layoutState = !strip ? 0 : (isShorts ? 2 : 1);
        if (layoutState == mLayoutState) {
            return;
        }
        mLayoutState = layoutState;
        mStripMode = strip;

        // Hide auto-hide chrome and dismiss profile sheet immediately on any layout change.
        mChromeHandler.removeCallbacks(mHideShortsChr);
        setShortsChrome(false);
        closeShortsProfileSheet();
        // Reset any in-progress swipe animation / pending frame wait.
        mAwaitingShortsFrame = false;
        mChromeHandler.removeCallbacks(mShortsFramePoll);
        mChromeHandler.removeCallbacks(mShortsFrameTimeout);
        setShortsPageTranslation(0);
        hideShortsPosters();

        // Show/hide the Shorts info bar vs. the regular panel.
        if (mShortsInfoBar != null) {
            mShortsInfoBar.setVisibility(isShorts && strip ? View.VISIBLE : View.GONE);
        }
        if (mShortsNavBar != null) {
            mShortsNavBar.setVisibility(isShorts && strip ? View.VISIBLE : View.GONE);
        }

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
            set.setDimensionRatio(R.id.playback_controls_fragment, ratio);
            set.setVisibility(R.id.mobile_below_video_panel, showPanel ? View.VISIBLE : View.GONE);
            set.setVisibility(R.id.mobile_shorts_info_bar, isShorts ? View.VISIBLE : View.GONE);
            set.setVisibility(R.id.mobile_shorts_nav_bar, isShorts ? View.VISIBLE : View.GONE);
        } else {
            set.connect(R.id.playback_controls_fragment, ConstraintSet.BOTTOM,
                    ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);
            set.setDimensionRatio(R.id.playback_controls_fragment, null);
            set.setVisibility(R.id.mobile_below_video_panel, View.GONE);
            set.setVisibility(R.id.mobile_shorts_info_bar, View.GONE);
            set.setVisibility(R.id.mobile_shorts_nav_bar, View.GONE);
        }
        set.applyTo(mRoot);

        // For Shorts: hide the Leanback transport buttons (play/pause/skip) while keeping the
        // seek bar. For regular/full-screen: restore them.
        applyLeanbackControlsVisibility(isShorts && strip);
    }

    @Override
    public void showControlsOverlay(boolean runAnimation) {
        super.showControlsOverlay(runAnimation);
        // The overlay row views are created lazily on the first reveal — re-apply here so the
        // strip tweaks land no matter when the views appear.
        applyOverlayDecorVisibility(mStripMode);
        // Re-hide the Leanback buttons for Shorts after the lazy inflate.
        applyLeanbackControlsVisibility(mLayoutState == 2);
    }

    /**
     * Hide the Leanback transport buttons (play/pause/skip/time) while keeping the seek bar
     * visible for Shorts. Controls are inflated lazily — null-checks are intentional; this is
     * also called from showControlsOverlay() to catch the first inflate.
     */
    private void applyLeanbackControlsVisibility(boolean hideForShorts) {
        if (getView() == null) return;
        int vis = hideForShorts ? View.GONE : View.VISIBLE;
        for (int id : new int[]{R.id.controls_dock, R.id.secondary_controls_dock,
                                 R.id.time_info}) {
            View v = getView().findViewById(id);
            if (v != null) v.setVisibility(vis);
        }
    }

    /** Show or hide the Shorts overlay chrome (action rail + back button only). */
    private void setShortsChrome(boolean visible) {
        int vis = visible ? View.VISIBLE : View.INVISIBLE;
        if (mShortsActionRail != null) mShortsActionRail.setVisibility(vis);
        if (mShortsBackBtn != null) mShortsBackBtn.setVisibility(vis);
    }

    /** Reveal the Shorts overlay chrome for 3 seconds, then auto-hide. No-op outside Shorts. */
    private void revealShortsChrome() {
        if (mLayoutState != 2) return;
        setShortsChrome(true);
        mChromeHandler.removeCallbacks(mHideShortsChr);
        mChromeHandler.postDelayed(mHideShortsChr, 3000);
    }

    /** Tap on the video area: toggle play/pause and flash the indicator icon. */
    private void onShortsTap() {
        boolean wasPlaying = isPlaying();
        // setPlayWhenReady drives the ExoPlayer engine directly (same path the landscape
        // transport play/pause button uses) — onPlayClicked/onPauseClicked only notify
        // listeners and don't actually toggle the engine.
        setPlayWhenReady(!wasPlaying);
        showShortsPlayPauseIcon(!wasPlaying);
    }

    /** Flash the play/pause indicator icon briefly, then fade it out. */
    private void showShortsPlayPauseIcon(boolean isPlay) {
        if (mShortsPlayPauseBtn == null) return;
        mShortsPlayPauseBtn.setImageResource(
                isPlay ? R.drawable.ic_shorts_play : R.drawable.ic_shorts_pause);
        mShortsPlayPauseBtn.setAlpha(1f);
        mShortsPlayPauseBtn.setVisibility(View.VISIBLE);
        mShortsPlayPauseBtn.animate().cancel();
        mShortsPlayPauseBtn.animate()
                .alpha(0f).setDuration(600).setStartDelay(600)
                .withEndAction(() -> {
                    mShortsPlayPauseBtn.setVisibility(View.INVISIBLE);
                    mShortsPlayPauseBtn.setAlpha(1f);
                }).start();
    }

    /** Navigate to a browse section and close the Shorts player. */
    private void navigateToSection(int sectionType) {
        BrowsePresenter.instance(getContext()).selectSection(sectionType);
        requireActivity().onBackPressed();
    }

    /** Slide the "You" profile sheet up from the bottom. */
    private void openShortsProfileSheet() {
        if (mShortsProfileSheet == null || mShortsProfileScrim == null) return;
        if (mShortsProfileSheet.getVisibility() == View.VISIBLE) return;

        // Populate account header.
        try {
            Account account = YouTubeServiceManager.instance().getSignInService().getSelectedAccount();
            TextView nameView = mShortsProfileSheet.findViewById(R.id.profile_name);
            TextView emailView = mShortsProfileSheet.findViewById(R.id.profile_email);
            ImageView avatarView = mShortsProfileSheet.findViewById(R.id.profile_avatar);
            if (account != null) {
                if (nameView != null)
                    nameView.setText(account.getName() != null ? account.getName() : "Account");
                if (emailView != null)
                    emailView.setText(account.getEmail() != null ? account.getEmail() : "");
                if (avatarView != null && account.getAvatarImageUrl() != null) {
                    Glide.with(this).load(account.getAvatarImageUrl()).circleCrop().into(avatarView);
                }
            }
        } catch (Exception ignored) {}

        mShortsProfileScrim.setAlpha(0f);
        mShortsProfileScrim.setVisibility(View.VISIBLE);
        mShortsProfileScrim.animate().alpha(1f).setDuration(200).start();

        mShortsProfileSheet.setVisibility(View.VISIBLE);
        mShortsProfileSheet.post(() -> {
            int h = mShortsProfileSheet.getHeight();
            if (h > 0) {
                mShortsProfileSheet.setTranslationY(h);
                mShortsProfileSheet.animate().translationY(0).setDuration(250).start();
            }
        });
    }

    /** Slide the "You" profile sheet back down and hide it. */
    private void closeShortsProfileSheet() {
        if (mShortsProfileSheet == null || mShortsProfileScrim == null) return;
        if (mShortsProfileSheet.getVisibility() != View.VISIBLE) return;

        mShortsProfileScrim.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> mShortsProfileScrim.setVisibility(View.GONE)).start();

        int h = mShortsProfileSheet.getHeight();
        mShortsProfileSheet.animate().translationY(h > 0 ? h : 500).setDuration(200)
                .withEndAction(() -> {
                    mShortsProfileSheet.setVisibility(View.GONE);
                    mShortsProfileSheet.setTranslationY(0);
                }).start();
    }

    /** Full-screen height used as the page stride and poster park distance. */
    private int shortsPageHeight() {
        if (mRoot != null && mRoot.getHeight() > 0) return mRoot.getHeight();
        return getView() != null ? getView().getHeight() : 0;
    }

    /**
     * Apply a logical drag offset {@code d} to the whole pager (null-safe):
     * the current page sits at {@code d}, the next poster one screen below at {@code screenH + d},
     * the previous poster one screen above at {@code -screenH + d}.
     */
    private void setShortsPageTranslation(float d) {
        int screenH = shortsPageHeight();
        if (mVideoPageViews != null) {
            for (View v : mVideoPageViews) {
                if (v == null) continue;
                v.animate().cancel(); // a snap must win over any in-flight commit animation
                v.setTranslationY(d);
            }
        }
        if (mShortsNextPoster != null) {
            mShortsNextPoster.animate().cancel();
            mShortsNextPoster.setTranslationY(screenH + d);
        }
        if (mShortsPrevPoster != null) {
            mShortsPrevPoster.animate().cancel();
            mShortsPrevPoster.setTranslationY(-screenH + d);
        }
    }

    /** Animate the whole pager to logical drag offset {@code d}. Callback fires once, at the end. */
    private void animateShortsPageTo(float d, Runnable onEnd) {
        int screenH = shortsPageHeight();
        boolean firedCallback = false;
        if (mVideoPageViews != null) {
            for (View v : mVideoPageViews) {
                if (v == null) continue;
                v.animate().cancel();
                android.view.ViewPropertyAnimator anim = v.animate().translationY(d).setDuration(250);
                if (onEnd != null && !firedCallback) {
                    anim.withEndAction(onEnd);
                    firedCallback = true;
                }
                anim.start();
            }
        }
        animatePoster(mShortsNextPoster, screenH + d);
        animatePoster(mShortsPrevPoster, -screenH + d);
    }

    private void animatePoster(View poster, float target) {
        if (poster == null) return;
        poster.animate().cancel();
        poster.animate().translationY(target).setDuration(250).start();
    }

    /** Load the prev/next Short thumbnails and reveal the posters for the duration of a drag. */
    private void prepareShortsPosters() {
        SuggestionsController sc =
                PlaybackPresenter.instance(getContext()).getController(SuggestionsController.class);
        loadPoster(mShortsNextPoster, sc != null ? sc.getNext() : null);
        loadPoster(mShortsPrevPoster, sc != null ? sc.getPrevious() : null);
    }

    private void loadPoster(ImageView poster, Video video) {
        if (poster == null) return;
        String url = video != null ? video.getCardImageUrl() : null;
        if (url != null) {
            poster.animate().cancel();
            poster.setAlpha(1f);
            poster.setVisibility(View.VISIBLE);
            Glide.with(this).load(url).into(poster);
        } else {
            poster.setVisibility(View.INVISIBLE);
            poster.setImageDrawable(null);
        }
    }

    private void hideShortsPosters() {
        if (mShortsNextPoster != null) {
            mShortsNextPoster.setVisibility(View.INVISIBLE);
            mShortsNextPoster.setImageDrawable(null);
        }
        if (mShortsPrevPoster != null) {
            mShortsPrevPoster.setVisibility(View.INVISIBLE);
            mShortsPrevPoster.setImageDrawable(null);
        }
    }

    /** On finger release, commit to next/prev or spring back based on drag distance. */
    private void onShortsSwipeReleased(float dy) {
        int screenH = shortsPageHeight();
        float thresh = screenH * 0.15f;
        if (dy < -thresh) {
            mLastSwipeDirection = 1;
            animateShortsPageTo(-screenH, null); // current page exits up, next poster fills screen
            commitShortsNavigation(true);
        } else if (dy > thresh) {
            mLastSwipeDirection = -1;
            animateShortsPageTo(screenH, null); // current page exits down, prev poster fills screen
            commitShortsNavigation(false);
        } else {
            // Below threshold: spring back and drop the posters once we're home.
            animateShortsPageTo(0, this::hideShortsPosters);
        }
    }

    private void commitShortsNavigation(boolean next) {
        Video current = PlaybackPresenter.instance(getContext()).getVideo();
        mSwipeFromVideoId = current != null ? current.videoId : null;
        if (next) {
            PlaybackPresenter.instance(getContext()).onNextClicked();
        } else {
            PlaybackPresenter.instance(getContext()).onPreviousClicked();
        }
        awaitShortsFrame();
    }

    /** Snap the page views (video surface + chrome) back to centre — posters are left untouched. */
    private void resetPageViewsToZero() {
        if (mVideoPageViews == null) return;
        for (View v : mVideoPageViews) {
            if (v == null) continue;
            v.animate().cancel();
            v.setTranslationY(0);
        }
    }

    /**
     * Keep the incoming poster covering the loading surface until the new Short is actually
     * playing (ExoPlayer reports isPlaying only once the first frame is rendered), then reveal
     * the live video. A timeout guards against a play signal that never comes.
     */
    private void awaitShortsFrame() {
        mAwaitingShortsFrame = true;
        mChromeHandler.removeCallbacks(mShortsFramePoll);
        mChromeHandler.removeCallbacks(mShortsFrameTimeout);
        mChromeHandler.postDelayed(mShortsFramePoll, SHORTS_FRAME_POLL_MS);
        mChromeHandler.postDelayed(mShortsFrameTimeout, SHORTS_FRAME_TIMEOUT_MS);
    }

    private final Runnable mShortsFramePoll = new Runnable() {
        @Override
        public void run() {
            if (!mAwaitingShortsFrame) return;
            Video v = PlaybackPresenter.instance(getContext()).getVideo();
            boolean switched = v != null && v.videoId != null
                    && !v.videoId.equals(mSwipeFromVideoId);
            if (switched && isPlaying()) {
                finishShortsTransition();
            } else {
                mChromeHandler.postDelayed(this, SHORTS_FRAME_POLL_MS);
            }
        }
    };

    private final Runnable mShortsFrameTimeout = this::finishShortsTransition;

    /** New Short is rendering (or timed out): fade the covering poster away and warm the next one. */
    private void finishShortsTransition() {
        mAwaitingShortsFrame = false;
        mChromeHandler.removeCallbacks(mShortsFramePoll);
        mChromeHandler.removeCallbacks(mShortsFrameTimeout);
        fadeOutPoster(mShortsNextPoster);
        fadeOutPoster(mShortsPrevPoster);
        warmNextShort();
    }

    private void fadeOutPoster(ImageView poster) {
        if (poster == null || poster.getVisibility() != View.VISIBLE) return;
        poster.animate().cancel();
        poster.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            poster.setVisibility(View.INVISIBLE);
            poster.setImageDrawable(null);
            poster.setAlpha(1f);
        }).start();
    }

    /**
     * Pre-fetch the next Short's format info so the following swipe loads faster. The format-info
     * cache is single-slot, so we only warm the most-likely direction (next).
     */
    private void warmNextShort() {
        SuggestionsController sc =
                PlaybackPresenter.instance(getContext()).getController(SuggestionsController.class);
        Video next = sc != null ? sc.getNext() : null;
        if (next != null) {
            MediaServiceManager.instance().loadFormatInfo(next, info -> {});
        }
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
        // Long descriptions scroll inside the text view instead of pushing the list away.
        mDescriptionView.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        mDescriptionView.setOnClickListener(v -> setDescriptionExpanded(false));

        mUpNextAdapter = new UpNextRowAdapter(
                video -> PlaybackPresenter.instance(getContext()).onSuggestionItemClicked(video));
        mUpNextList.setLayoutManager(new LinearLayoutManager(getContext()));
        mUpNextList.setAdapter(mUpNextAdapter);

        initShortsViews(activity);

        return true;
    }

    private void initShortsViews(Activity activity) {
        mShortsActionRail = activity.findViewById(R.id.mobile_shorts_action_rail);
        mShortsBackBtn = activity.findViewById(R.id.mobile_shorts_back_btn);
        mShortsInfoBar = activity.findViewById(R.id.mobile_shorts_info_bar);
        mShortsTitleView = activity.findViewById(R.id.shorts_bar_title);
        mShortsChannelView = activity.findViewById(R.id.shorts_bar_channel);

        if (mShortsActionRail != null) {
            mShortsLikeBtn = mShortsActionRail.findViewById(R.id.mobile_shorts_like_btn);
            mShortsLikeCount = mShortsActionRail.findViewById(R.id.mobile_shorts_like_count);
            mShortsDislikeBtn = mShortsActionRail.findViewById(R.id.mobile_shorts_dislike_btn);
            mShortsCommentsBtn = mShortsActionRail.findViewById(R.id.mobile_shorts_comments_btn);
            mShortsCommentsCount = mShortsActionRail.findViewById(R.id.mobile_shorts_comments_count);
            mShortsChannelBtn = mShortsActionRail.findViewById(R.id.mobile_shorts_channel_btn);

            if (mShortsLikeBtn != null) {
                // Pass the CURRENT state so the controller toggles correctly (BUTTON_ON -> remove).
                mShortsLikeBtn.setOnClickListener(v ->
                        PlaybackPresenter.instance(getContext())
                                .onButtonClicked(R.id.action_thumbs_up, currentButtonState(R.id.action_thumbs_up)));
            }
            if (mShortsDislikeBtn != null) {
                mShortsDislikeBtn.setOnClickListener(v ->
                        PlaybackPresenter.instance(getContext())
                                .onButtonClicked(R.id.action_thumbs_down, currentButtonState(R.id.action_thumbs_down)));
            }
            if (mShortsCommentsBtn != null) {
                // Comments are triggered via the chat action (CommentsController listens for action_chat).
                mShortsCommentsBtn.setOnClickListener(v ->
                        PlaybackPresenter.instance(getContext()).onButtonClicked(R.id.action_chat, 0));
            }
            if (mShortsChannelBtn != null) {
                mShortsChannelBtn.setOnClickListener(v ->
                        PlaybackPresenter.instance(getContext()).onButtonClicked(R.id.action_channel, 0));
            }
        }

        // Back button: dismiss the profile sheet if open, otherwise exit the player.
        if (mShortsBackBtn != null) {
            mShortsBackBtn.setOnClickListener(v -> {
                if (mShortsProfileSheet != null
                        && mShortsProfileSheet.getVisibility() == View.VISIBLE) {
                    closeShortsProfileSheet();
                } else {
                    requireActivity().onBackPressed();
                }
            });
        }

        // Centred play/pause indicator (ImageView — visual only, not tappable directly).
        mShortsPlayPauseBtn = activity.findViewById(R.id.mobile_shorts_play_pause_btn);

        // Bottom navigation bar.
        mShortsNavBar = activity.findViewById(R.id.mobile_shorts_nav_bar);
        if (mShortsNavBar != null) {
            mShortsNavBar.findViewById(R.id.shorts_nav_home).setOnClickListener(v ->
                    navigateToSection(MediaGroup.TYPE_HOME));
            mShortsNavBar.findViewById(R.id.shorts_nav_shorts).setOnClickListener(v ->
                    navigateToSection(MediaGroup.TYPE_SHORTS));
            mShortsNavBar.findViewById(R.id.shorts_nav_subscriptions).setOnClickListener(v ->
                    navigateToSection(MediaGroup.TYPE_SUBSCRIPTIONS));
            mShortsNavBar.findViewById(R.id.shorts_nav_you).setOnClickListener(v ->
                    openShortsProfileSheet());
        }

        // "You" profile sheet and scrim.
        mShortsProfileScrim = activity.findViewById(R.id.mobile_shorts_profile_scrim);
        mShortsProfileSheet = activity.findViewById(R.id.mobile_shorts_profile_sheet);
        if (mShortsProfileScrim != null) {
            mShortsProfileScrim.setOnClickListener(v -> closeShortsProfileSheet());
        }
        if (mShortsProfileSheet != null) {
            mShortsProfileSheet.findViewById(R.id.profile_sheet_handle).setOnClickListener(v ->
                    closeShortsProfileSheet());
            mShortsProfileSheet.findViewById(R.id.profile_row_channel).setOnClickListener(v -> {
                closeShortsProfileSheet();
                PlaybackPresenter.instance(getContext()).onButtonClicked(R.id.action_channel, 0);
            });
            mShortsProfileSheet.findViewById(R.id.profile_row_history).setOnClickListener(v ->
                    navigateToSection(MediaGroup.TYPE_HISTORY));
            mShortsProfileSheet.findViewById(R.id.profile_row_playlists).setOnClickListener(v ->
                    navigateToSection(MediaGroup.TYPE_USER_PLAYLISTS));
            mShortsProfileSheet.findViewById(R.id.profile_row_settings).setOnClickListener(v ->
                    navigateToSection(MediaGroup.TYPE_SETTINGS));
        }

        // Filmstrip posters for the swipe pager.
        mShortsNextPoster = activity.findViewById(R.id.mobile_shorts_next_poster);
        mShortsPrevPoster = activity.findViewById(R.id.mobile_shorts_prev_poster);

        // Drag threshold: 15dp before a MOVE is classified as a page drag (not a tap).
        mDragThresholdPx = (int) (15 * getResources().getDisplayMetrics().density);

        // All views that translate together as one Shorts page.
        // Nav bar is excluded — it stays fixed at the bottom of the screen.
        mVideoPageViews = new View[]{
            getView(),          // video surface + Leanback overlay
            mShortsActionRail,
            mShortsBackBtn,
            mShortsPlayPauseBtn,
            mShortsInfoBar
        };
    }
}
