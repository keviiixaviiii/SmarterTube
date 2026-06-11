package com.liskovsoft.smartyoutubetv2.mobile.ui.dialogs;

import android.graphics.PorterDuff;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Phone-native comments list. Renders {@link CommentItem}s YouTube-style (avatar, author, date,
 * message, like and reply counts). Drives the shared {@code CommentsReceiver} via callbacks: a row
 * tap opens that comment's replies; the like area toggles a like. Note {@code isEmpty()} means
 * "has no replies" (it's the controller's open-replies guard), so it must NOT be used to filter
 * rows — nested replies and reply-less top-level comments are all isEmpty().
 */
public class MobileCommentsAdapter extends RecyclerView.Adapter<MobileCommentsAdapter.Holder> {
    public interface OnComment {
        void onComment(CommentItem item);
    }

    private final List<CommentItem> mItems = new ArrayList<>();
    private final OnComment mReplyClick;
    private final OnComment mLikeClick;

    public MobileCommentsAdapter(OnComment replyClick, OnComment likeClick) {
        mReplyClick = replyClick;
        mLikeClick = likeClick;
    }

    /** Append a page of comments. Returns the number actually added. */
    public int addComments(List<CommentItem> comments) {
        if (comments == null) {
            return 0;
        }
        int start = mItems.size();
        int added = 0;
        for (CommentItem item : comments) {
            if (hasContent(item)) {
                mItems.add(item);
                added++;
            }
        }
        if (added > 0) {
            notifyItemRangeInserted(start, added);
        }
        return added;
    }

    public void setComments(List<CommentItem> comments) {
        mItems.clear();
        if (comments != null) {
            for (CommentItem item : comments) {
                if (hasContent(item)) {
                    mItems.add(item);
                }
            }
        }
        notifyDataSetChanged();
    }

    public List<CommentItem> getComments() {
        return new ArrayList<>(mItems);
    }

    /**
     * Some items in a comments response carry no renderable fields at all (ghost rows whose
     * renderer sits at a JSON path the parser doesn't read). Skip those; do NOT use
     * {@code isEmpty()} here — it means "has no replies", which describes every nested reply.
     */
    private static boolean hasContent(CommentItem item) {
        return item != null && (item.getMessage() != null || item.getAuthorName() != null);
    }

    /** Replace an item in place (e.g. after a like toggle) and refresh its row. */
    public void update(CommentItem item) {
        if (item == null) {
            return;
        }
        for (int i = 0; i < mItems.size(); i++) {
            if (Helpers.equals(mItems.get(i).getId(), item.getId())) {
                mItems.set(i, item);
                notifyItemChanged(i);
                return;
            }
        }
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.mobile_comment_item, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        CommentItem item = mItems.get(position);

        String author = item.getAuthorName() != null ? item.getAuthorName() : "";
        String date = item.getPublishedDate();
        h.authorDate.setText(TextUtils.isEmpty(date) ? author : author + "  ·  " + date);
        h.message.setText(item.getMessage() != null ? item.getMessage() : "");

        String likeCount = item.getLikeCount();
        h.likeCount.setText(likeCount != null ? likeCount : "");

        int likeTint = ContextCompat.getColor(h.itemView.getContext(),
                item.isLiked() ? R.color.mobile_accent : R.color.mobile_text_secondary);
        h.likeIcon.setColorFilter(likeTint, PorterDuff.Mode.SRC_IN);
        h.likeCount.setTextColor(likeTint);

        String replyCount = item.getReplyCount();
        boolean hasReplies = !TextUtils.isEmpty(replyCount) && item.getNestedCommentsKey() != null;
        if (hasReplies) {
            h.replyCount.setVisibility(View.VISIBLE);
            h.replyCount.setText(replyCount);
        } else {
            h.replyCount.setVisibility(View.GONE);
        }

        // Avatar background (set in the item layout) shows through as the placeholder until the
        // circle-cropped photo loads.
        Glide.with(h.itemView.getContext())
                .load(item.getAuthorPhoto())
                .circleCrop()
                .into(h.avatar);

        h.itemView.setOnClickListener(v -> {
            if (mReplyClick != null && hasReplies) {
                mReplyClick.onComment(item);
            }
        });

        View.OnClickListener likeListener = v -> {
            if (mLikeClick != null) {
                mLikeClick.onComment(item);
            }
        };
        h.likeIcon.setOnClickListener(likeListener);
        h.likeCount.setOnClickListener(likeListener);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView authorDate;
        final TextView message;
        final ImageView likeIcon;
        final TextView likeCount;
        final TextView replyCount;

        Holder(View v) {
            super(v);
            avatar = v.findViewById(R.id.comment_avatar);
            authorDate = v.findViewById(R.id.comment_author_date);
            message = v.findViewById(R.id.comment_message);
            likeIcon = v.findViewById(R.id.comment_like_icon);
            likeCount = v.findViewById(R.id.comment_like_count);
            replyCount = v.findViewById(R.id.comment_reply_count);
        }
    }
}
