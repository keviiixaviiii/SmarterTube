package com.liskovsoft.smartyoutubetv2.mobile.ui.dialogs;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.mediaserviceinterfaces.data.CommentGroup;
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.CommentsReceiver;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.CommentsReceiver.Backup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.AppDialogView;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.List;

/**
 * Phone-native settings dialog. Implements {@link AppDialogView} and is driven by the
 * existing {@link AppDialogPresenter} unchanged — category list, radio/check/switch state
 * and the on-select callbacks are all reused from the TV code; only the view layer is new.
 *
 * Categories are rendered flat in one vertical list: each non-null category title becomes
 * a section header followed by its option rows. There is no nested back stack — back
 * always finishes the dialog (the underlying TV {@code AppDialogPresenter} drives a fresh
 * dialog instance per category on the TV build's nested screens; on phone the same flow
 * just opens a new {@code MobileAppDialogActivity}).
 */
public class MobileAppDialogFragment extends Fragment implements AppDialogView {
    private AppDialogPresenter mPresenter;
    private TextView mTitleView;
    private RecyclerView mList;
    private TextView mStatusView;
    private MobileAppDialogAdapter mAdapter;
    private MobileCommentsAdapter mCommentsAdapter;
    private CommentsReceiver mCommentsReceiver;
    private CommentsState mParentComments;
    private CommentGroup mCurrentGroup;
    private boolean mLoadingMore;
    private boolean mIsTransparent;
    private boolean mIsOverlay;
    private boolean mIsPaused;
    private int mId;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.mobile_app_dialog_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mTitleView = view.findViewById(R.id.dialog_title);
        mStatusView = view.findViewById(R.id.options_status);
        mList = view.findViewById(R.id.options_list);
        mList.setLayoutManager(new LinearLayoutManager(getContext()));
        mAdapter = new MobileAppDialogAdapter();
        mList.setAdapter(mAdapter);

        view.findViewById(R.id.btn_back).setOnClickListener(v -> {
            if (getActivity() != null) {
                getActivity().onBackPressed();
            }
        });

        mPresenter = AppDialogPresenter.instance(getContext());
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    @Override
    public void onPause() {
        super.onPause();
        mIsPaused = true;
    }

    @Override
    public void onResume() {
        super.onResume();
        mIsPaused = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Hand the loaded comments back to the controller (enables instant restore on reopen) and
        // clear the receiver's callback so it doesn't retain this fragment's views.
        if (mCommentsReceiver != null) {
            mCommentsReceiver.onFinish(new CommentsBackup(
                    mCommentsAdapter != null ? mCommentsAdapter.getComments() : null, mCurrentGroup));
            mCommentsReceiver = null;
        }

        // Closed while a replies thread was on screen: the top-level state still needs to reach
        // the controller's backup (only the top-level receiver's onFinish stores anything).
        if (mParentComments != null) {
            mParentComments.receiver.onFinish(
                    new CommentsBackup(mParentComments.items, mParentComments.currentGroup));
            mParentComments = null;
        }

        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }
    }

    /** Called by the activity when it is finishing, to fire the presenter's onFinish callbacks. */
    void onFinishCallback() {
        if (mPresenter != null) {
            mPresenter.onFinish();
        }
    }

    // ----- AppDialogView -----

    @Override
    public void show(List<OptionCategory> categories, CharSequence title, boolean isExpandable,
                     boolean isTransparent, boolean isOverlay, int id) {
        mIsTransparent = isTransparent;
        mIsOverlay = isOverlay;
        mId = id;

        CharSequence resolvedTitle = title;
        // Expandable single-category: drop the wrapping title and use the category's name
        // so the screen jumps straight into the options (matches TV behavior).
        if (isExpandable && categories != null && categories.size() == 1
                && categories.get(0).title != null) {
            resolvedTitle = categories.get(0).title;
        }
        if (mTitleView != null) {
            mTitleView.setText(TextUtils.isEmpty(resolvedTitle) ? "" : resolvedTitle);
        }

        // Comments arrive as a single TYPE_COMMENTS category carrying a CommentsReceiver. Render
        // them in a native YouTube-style list instead of the generic option rows.
        if (categories != null && categories.size() == 1
                && categories.get(0).type == OptionCategory.TYPE_COMMENTS) {
            setupComments(categories.get(0));
            return;
        }

        if (mAdapter != null) {
            mAdapter.setCategories(categories, isExpandable);
        }
    }

    // ----- comments -----

    private void setupComments(OptionCategory category) {
        OptionItem option = category.options != null && !category.options.isEmpty()
                ? category.options.get(0) : null;
        final CommentsReceiver receiver = option != null ? option.getCommentsReceiver() : null;
        if (receiver == null || mList == null) {
            return;
        }

        // A comments view is already active and a different receiver arrives: this is a nested
        // replies thread replacing the top-level list (the presenter re-enters the live dialog).
        // Save the top-level state so back restores it, and detach the old callback so a late
        // top-level page can't append into the replies adapter.
        if (mCommentsReceiver != null && mCommentsReceiver != receiver && mCommentsAdapter != null) {
            mCommentsReceiver.setCallback(null);
            mParentComments = new CommentsState(mCommentsReceiver, mCommentsAdapter.getComments(), mCurrentGroup);
        }

        mCommentsReceiver = receiver;
        mCurrentGroup = null;
        mLoadingMore = false;

        // This comments screen is a separate opaque activity that stops the player. On stop the
        // player would release its engine, which fires CommentsController.onEngineReleased() and
        // disposes the in-flight comments request before it returns — leaving the list stuck on
        // "Loading". Block the engine first (this runs before the player's onStop): the request
        // survives and playback keeps going underneath, YouTube-style. The player clears the block
        // in its own onResume() when we return.
        PlaybackView player = PlaybackPresenter.instance(getContext()).getPlayer();
        if (player != null) {
            player.blockEngine(true);
        }

        mCommentsAdapter = new MobileCommentsAdapter(
                item -> mCommentsReceiver.onCommentClicked(item),
                item -> mCommentsReceiver.onCommentLongClicked(item));
        mList.setAdapter(mCommentsAdapter);
        // setupComments re-runs on the live fragment when a replies thread opens — remove first
        // so the listener isn't registered twice.
        mList.removeOnScrollListener(mPaginationListener);
        mList.addOnScrollListener(mPaginationListener);

        showStatus(receiver.getLoadingMessage());

        attachCommentsCallback(receiver);

        receiver.onStart();
    }

    private void attachCommentsCallback(final CommentsReceiver receiver) {
        receiver.setCallback(new CommentsReceiver.Callback() {
            @Override
            public void onCommentGroup(CommentGroup commentGroup) {
                mLoadingMore = false;

                if (commentGroup == null || commentGroup.getComments() == null) {
                    if (mCommentsAdapter.getItemCount() == 0) {
                        showStatus(receiver.getErrorMessage());
                    }
                    return;
                }

                mCommentsAdapter.addComments(commentGroup.getComments());
                mCurrentGroup = commentGroup;
                showStatus(mCommentsAdapter.getItemCount() == 0 ? receiver.getErrorMessage() : null);

                // Short replies list that still has a continuation: onLoadMore won't fire on a
                // list that doesn't scroll, so page it in eagerly (matches the TV behavior).
                if (mCommentsAdapter.getItemCount() <= 10 && commentGroup.getNextCommentsKey() != null) {
                    mLoadingMore = true;
                    receiver.onLoadMore(commentGroup);
                }
            }

            @Override
            public void onBackup(Backup backup) {
                if (backup instanceof CommentsBackup) {
                    CommentsBackup b = (CommentsBackup) backup;
                    mCommentsAdapter.setComments(b.items);
                    mCurrentGroup = b.currentGroup;
                    showStatus(mCommentsAdapter.getItemCount() == 0 ? receiver.getErrorMessage() : null);
                }
            }

            @Override
            public void onSync(CommentItem commentItem) {
                mCommentsAdapter.update(commentItem);
            }
        });
    }

    /** Restore the saved top-level comments after a nested replies thread (back press). */
    private void restoreParentComments() {
        CommentsState parent = mParentComments;
        mParentComments = null;

        if (mCommentsReceiver != null) {
            mCommentsReceiver.setCallback(null); // drop the replies receiver
        }

        mCommentsReceiver = parent.receiver;
        mCurrentGroup = parent.currentGroup;
        mLoadingMore = false;

        mCommentsAdapter = new MobileCommentsAdapter(
                item -> mCommentsReceiver.onCommentClicked(item),
                item -> mCommentsReceiver.onCommentLongClicked(item));
        mCommentsAdapter.setComments(parent.items);
        mList.setAdapter(mCommentsAdapter);

        attachCommentsCallback(mCommentsReceiver);
        showStatus(mCommentsAdapter.getItemCount() == 0 ? mCommentsReceiver.getErrorMessage() : null);
    }

    private final RecyclerView.OnScrollListener mPaginationListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
            if (dy <= 0 || mCommentsReceiver == null || mLoadingMore
                    || mCurrentGroup == null || mCurrentGroup.getNextCommentsKey() == null) {
                return;
            }
            RecyclerView.LayoutManager lm = rv.getLayoutManager();
            if (lm instanceof LinearLayoutManager) {
                LinearLayoutManager llm = (LinearLayoutManager) lm;
                if (llm.findLastVisibleItemPosition() >= llm.getItemCount() - 3) {
                    mLoadingMore = true;
                    mCommentsReceiver.onLoadMore(mCurrentGroup);
                }
            }
        }
    };

    private void showStatus(String text) {
        if (mStatusView == null) {
            return;
        }
        if (TextUtils.isEmpty(text)) {
            mStatusView.setVisibility(View.GONE);
        } else {
            mStatusView.setText(text);
            mStatusView.setVisibility(View.VISIBLE);
        }
    }

    /** Snapshot of the loaded comments so reopening the same video restores instantly. */
    private static final class CommentsBackup implements Backup {
        final List<CommentItem> items;
        final CommentGroup currentGroup;

        CommentsBackup(List<CommentItem> items, CommentGroup currentGroup) {
            this.items = items;
            this.currentGroup = currentGroup;
        }
    }

    /** Saved top-level comments state while a nested replies thread is displayed. */
    private static final class CommentsState {
        final CommentsReceiver receiver;
        final List<CommentItem> items;
        final CommentGroup currentGroup;

        CommentsState(CommentsReceiver receiver, List<CommentItem> items, CommentGroup currentGroup) {
            this.receiver = receiver;
            this.items = items;
            this.currentGroup = currentGroup;
        }
    }

    @Override
    public void finish() {
        if (getActivity() != null) {
            getActivity().finish();
        }
    }

    @Override
    public void goBack() {
        // The only nested level is a comments replies thread; otherwise goBack == finish.
        if (mParentComments != null) {
            restoreParentComments();
        } else {
            finish();
        }
    }

    @Override
    public void clearBackstack() {
        mParentComments = null;
    }

    @Override
    public boolean canGoBack() {
        return mParentComments != null;
    }

    @Override
    public boolean isShown() {
        return isVisible() && getUserVisibleHint();
    }

    @Override
    public boolean isTransparent() {
        return mIsTransparent;
    }

    @Override
    public boolean isOverlay() {
        return mIsOverlay;
    }

    @Override
    public boolean isPaused() {
        return mIsPaused;
    }

    @Override
    public int getViewId() {
        return mId;
    }
}
