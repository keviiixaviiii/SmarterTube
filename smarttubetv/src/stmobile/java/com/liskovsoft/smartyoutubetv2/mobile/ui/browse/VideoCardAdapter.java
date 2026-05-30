package com.liskovsoft.smartyoutubetv2.mobile.ui.browse;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Flat list of video cards. Used both for grid sections and (horizontally) inside shelves.
 * The card width is fixed per adapter; the thumbnail keeps a 16:9 ratio.
 *
 * Public so the phone Search screen ({@code mobile.ui.search}) can reuse the same card.
 */
public class VideoCardAdapter extends RecyclerView.Adapter<VideoCardAdapter.ViewHolder> {
    public interface OnVideoAction {
        void onVideo(Video video);
    }

    private int mCardWidth;
    private final OnVideoAction mClick;
    private final OnVideoAction mLongClick;
    private final List<Video> mVideos = new ArrayList<>();

    public VideoCardAdapter(int cardWidth, OnVideoAction click, OnVideoAction longClick) {
        mCardWidth = cardWidth;
        mClick = click;
        mLongClick = longClick;
    }

    /**
     * Update the per-card width (e.g. after an orientation change, where the grid span
     * and therefore the column width change). The host activities declare
     * {@code configChanges="orientation|..."} so they are NOT recreated on rotation -
     * the fragment re-reads the span and calls this to keep card width == column width.
     */
    public void setCardWidth(int cardWidth) {
        if (mCardWidth != cardWidth) {
            mCardWidth = cardWidth;
            notifyDataSetChanged();
        }
    }

    public void setVideos(List<Video> videos) {
        mVideos.clear();
        if (videos != null) {
            mVideos.addAll(videos);
        }
        notifyDataSetChanged();
    }

    public void clear() {
        mVideos.clear();
        notifyDataSetChanged();
    }

    public void remove(List<Video> videos) {
        if (videos != null && mVideos.removeAll(videos)) {
            notifyDataSetChanged();
        }
    }

    public Video getLast() {
        return mVideos.isEmpty() ? null : mVideos.get(mVideos.size() - 1);
    }

    @Override
    public int getItemCount() {
        return mVideos.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.mobile_video_card, parent, false);
        if (view.getLayoutParams() != null) {
            view.getLayoutParams().width = mCardWidth;
        }
        ViewHolder holder = new ViewHolder(view);
        holder.thumb.getLayoutParams().height = mCardWidth * 9 / 16;
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        // Re-apply width on every bind so setCardWidth() (orientation change) resizes
        // recycled cards, keeping each card the width of its grid column.
        if (holder.itemView.getLayoutParams() != null) {
            holder.itemView.getLayoutParams().width = mCardWidth;
        }
        holder.thumb.getLayoutParams().height = mCardWidth * 9 / 16;

        Video video = mVideos.get(position);
        holder.title.setText(video.getTitle());
        String author = video.getAuthor();
        holder.author.setText(author != null ? author : "");

        Glide.with(holder.itemView.getContext())
                .load(video.getCardImageUrl())
                .into(holder.thumb);

        holder.itemView.setOnClickListener(v -> {
            if (mClick != null) {
                mClick.onVideo(video);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (mLongClick != null) {
                mLongClick.onVideo(video);
            }
            return true;
        });
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title;
        final TextView author;

        ViewHolder(View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.card_thumb);
            title = itemView.findViewById(R.id.card_title);
            author = itemView.findViewById(R.id.card_author);
        }
    }
}
