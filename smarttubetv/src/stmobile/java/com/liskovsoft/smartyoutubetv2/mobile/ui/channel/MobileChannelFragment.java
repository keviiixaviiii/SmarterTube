package com.liskovsoft.smartyoutubetv2.mobile.ui.channel;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelView;
import com.liskovsoft.smartyoutubetv2.mobile.ui.browse.ShelfAdapter;
import com.liskovsoft.smartyoutubetv2.mobile.ui.browse.VideoCardAdapter;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Native portrait Channel screen. Implements {@link ChannelView} and is driven by the
 * existing {@link ChannelPresenter} unchanged — group loading, video clicks, sort options
 * and the menu dialog all flow through the presenter. The view layer reuses the Home
 * shelves ({@link ShelfAdapter}) — a channel is just a stack of titled shelves.
 *
 * Horizontal-shelf pagination is deferred (the Home shelves don't paginate either).
 */
public class MobileChannelFragment extends Fragment implements ChannelView {
    private ChannelPresenter mPresenter;
    private RecyclerView mList;
    private SwipeRefreshLayout mSwipeRefresh;
    private ProgressBar mProgressBar;
    private TextView mTitleView;
    private ShelfAdapter mShelfAdapter;
    private int mShelfCardWidth;
    private boolean mSwipeRefreshing;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.mobile_channel_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mList = view.findViewById(R.id.channel_list);
        mSwipeRefresh = view.findViewById(R.id.swipe_refresh);
        mProgressBar = view.findViewById(R.id.progress_bar);
        mTitleView = view.findViewById(R.id.channel_title);

        view.findViewById(R.id.btn_back).setOnClickListener(v -> {
            if (getActivity() != null) {
                getActivity().finish();
            }
        });

        mShelfCardWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.42f);
        mShelfAdapter = new ShelfAdapter(mShelfCardWidth, mVideoClick, mVideoLongClick,
                last -> {
                    if (mPresenter != null && last != null) {
                        mPresenter.onScrollEnd(last);
                    }
                });
        mList.setLayoutManager(new LinearLayoutManager(getContext()));
        mList.setAdapter(mShelfAdapter);

        mSwipeRefresh.setColorSchemeResources(R.color.brand_accent);
        mSwipeRefresh.setProgressBackgroundColorSchemeResource(R.color.mobile_surface);
        mSwipeRefresh.setOnRefreshListener(() -> {
            // ChannelPresenter has no public refresh(); openChannel(id) is the reload path
            // (clears the view and re-fetches the channel rows).
            String channelId = mPresenter != null ? mPresenter.getChannelId() : null;
            if (channelId != null) {
                mSwipeRefreshing = true;
                mPresenter.openChannel(channelId);
            } else {
                stopSwipeRefresh();
            }
        });

        mPresenter = ChannelPresenter.instance(getContext());
        mPresenter.setView(this);

        Video channel = mPresenter.getChannel();
        if (channel != null && channel.getTitle() != null) {
            mTitleView.setText(channel.getTitle());
        }

        mPresenter.onViewInitialized();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mPresenter != null) {
            mPresenter.onViewDestroyed();
        }
    }

    // ----- ChannelView -----

    @Override
    public void update(VideoGroup group) {
        if (group == null || mShelfAdapter == null) {
            return;
        }
        switch (group.getAction()) {
            case VideoGroup.ACTION_REPLACE:
                // Some flows (sort change, in-channel search) emit a replace; wipe shelves
                // before appending. A "replace" carrying a single group ends up as one shelf.
                mShelfAdapter.clear();
                if (!group.isEmpty()) {
                    mShelfAdapter.appendGroup(group);
                }
                break;
            case VideoGroup.ACTION_REMOVE:
            case VideoGroup.ACTION_REMOVE_AUTHOR:
                mShelfAdapter.removeVideos(group.getVideos());
                break;
            case VideoGroup.ACTION_SYNC:
                // Percent-watched markers only; not rendered natively yet.
                break;
            default: // ACTION_APPEND / ACTION_PREPEND
                if (!group.isEmpty()) {
                    mShelfAdapter.appendGroup(group);
                }
                break;
        }
    }

    @Override
    public void setPosition(int index) {
        // Touch UI: no D-pad focus to restore. A scroll-to-row could go here if the
        // in-channel search ever lands natively; not needed for the basic flow.
    }

    @Override
    public void showProgressBar(boolean show) {
        // While pull-to-refresh is active the SwipeRefreshLayout spinner stands in for
        // the centre bar; don't show both.
        if (mProgressBar != null) {
            mProgressBar.setVisibility(show && !mSwipeRefreshing ? View.VISIBLE : View.GONE);
        }
        if (!show) {
            stopSwipeRefresh();
        }
    }

    private void stopSwipeRefresh() {
        mSwipeRefreshing = false;
        if (mSwipeRefresh != null) {
            mSwipeRefresh.setRefreshing(false);
        }
    }

    @Override
    public void clear() {
        if (mShelfAdapter != null) {
            mShelfAdapter.clear();
        }
    }

    // ----- callbacks -----

    private final VideoCardAdapter.OnVideoAction mVideoClick = video -> {
        if (mPresenter != null) {
            mPresenter.onVideoItemSelected(video);
            mPresenter.onVideoItemClicked(video);
        }
    };

    private final VideoCardAdapter.OnVideoAction mVideoLongClick = video -> {
        if (mPresenter != null) {
            mPresenter.onVideoItemLongClicked(video);
        }
    };
}
