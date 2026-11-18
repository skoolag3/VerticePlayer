package com.oneshot.surround;

import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

/**
 * High-performance RecyclerView Adapter for large libraries (900+ tracks at 60 FPS).
 * Only binds the ~12 visible rows on screen and recycles views cleanly with safe URI tag checks.
 */
final class LibraryRecyclerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    static final int TYPE_TRACK = 0;
    static final int TYPE_GROUP = 1;
    static final int TYPE_HEADER_VIEW = 2;

    static final class Item {
        final int type;
        final int trackIndex;
        final TrackInfo track;
        final String title;
        final String subtitle;
        final String groupKey;
        final View customView;

        private Item(int type, int trackIndex, TrackInfo track, String title, String subtitle, String groupKey, View customView) {
            this.type = type;
            this.trackIndex = trackIndex;
            this.track = track;
            this.title = title;
            this.subtitle = subtitle;
            this.groupKey = groupKey;
            this.customView = customView;
        }

        static Item track(int index, TrackInfo track, String subtitle) {
            return new Item(TYPE_TRACK, index, track, track.title, subtitle, null, null);
        }

        static Item group(String title, String subtitle, String groupKey, TrackInfo coverTrack) {
            return new Item(TYPE_GROUP, -1, coverTrack, title, subtitle, groupKey, null);
        }

        static Item headerView(View view) {
            return new Item(TYPE_HEADER_VIEW, -1, null, null, null, null, view);
        }
    }

    interface Callbacks {
        void onTrackClick(int trackIndex);
        void onTrackTagClick(TrackInfo track);
        void onGroupClick(String groupKey);
    }

    private final MainActivity host;
    private final Callbacks callbacks;
    private final ArrayList<Item> items = new ArrayList<>();

    LibraryRecyclerAdapter(MainActivity host, Callbacks callbacks) {
        this.host = host;
        this.callbacks = callbacks;
    }

    void submitList(List<Item> nextItems) {
        items.clear();
        if (nextItems != null) items.addAll(nextItems);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER_VIEW) {
            LinearLayout container = host.column();
            container.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new HeaderHolder(container);
        }

        if (viewType == TYPE_GROUP) {
            LinearLayout card = host.card();
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10));
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = host.dp(4);
            lp.bottomMargin = host.dp(4);
            card.setLayoutParams(lp);

            ImageView cover = new ImageView(host);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(host.background(host.SURFACE_TOP, 10));
            cover.setClipToOutline(true);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(host.dp(52), host.dp(52));
            cLp.rightMargin = host.dp(14);
            card.addView(cover, cLp);

            LinearLayout details = host.column();
            card.addView(details, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView title = host.label(details, "", 15, host.TEXT, true);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            TextView sub = host.label(details, "", 12, host.MUTED, false);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);

            return new GroupHolder(card, cover, title, sub);
        }

        // TYPE_TRACK
        LinearLayout row = host.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(host.dp(8), host.dp(7), host.dp(8), host.dp(7));
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = host.dp(2);
        row.setLayoutParams(lp);

        ImageView thumb = new ImageView(host);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(host.background(host.BACKGROUND, 10));
        thumb.setClipToOutline(true);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(host.dp(48), host.dp(48));
        tLp.rightMargin = host.dp(12);
        row.addView(thumb, tLp);

        LinearLayout details = host.column();
        row.addView(details, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView title = host.label(details, "", 14, host.TEXT, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView sub = host.label(details, "", 11.5f, host.MUTED, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);

        Button tagBtn = host.pill("🏷", false);
        tagBtn.setTextSize(11);
        tagBtn.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(host.dp(38), host.dp(34));
        tagLp.leftMargin = host.dp(6);
        row.addView(tagBtn, tagLp);

        return new TrackHolder(row, thumb, title, sub, tagBtn);
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        Item item = items.get(position);
        if (holder instanceof HeaderHolder) {
            HeaderHolder hh = (HeaderHolder) holder;
            hh.container.removeAllViews();
            if (item.customView != null) {
                if (item.customView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) item.customView.getParent()).removeView(item.customView);
                }
                hh.container.addView(item.customView);
            }
            return;
        }

        if (holder instanceof GroupHolder) {
            GroupHolder gh = (GroupHolder) holder;
            gh.title.setText(item.title);
            gh.sub.setText(item.subtitle);
            bindCover(gh.cover, item.track);
            gh.itemView.setOnClickListener(v -> callbacks.onGroupClick(item.groupKey));
            return;
        }

        if (holder instanceof TrackHolder) {
            TrackHolder th = (TrackHolder) holder;
            TrackInfo track = item.track;
            boolean current = item.trackIndex == host.currentIndex;
            th.itemView.setBackground(current ? host.borderedBackground(host.SURFACE_TOP, host.ACCENT, 14) : null);
            th.title.setText(item.title);
            th.title.setTypeface(null, current ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);

            String uriStr = track.uri.toString();
            String tagBadge = TrackTagDatabase.get(host).formatTrackTagsBadge(uriStr);
            String fullSub = tagBadge.isEmpty() ? item.subtitle : (item.subtitle + "  •  " + tagBadge);
            th.sub.setText(fullSub);
            th.sub.setTextColor(tagBadge.isEmpty() ? host.MUTED : MainActivity.TECH_CYAN);

            th.tagBtn.setText(tagBadge.isEmpty() ? "🏷" : "🏷✓");
            MaterialUi.buttonState(th.tagBtn, !tagBadge.isEmpty());

            bindCover(th.thumb, track);

            th.itemView.setOnClickListener(v -> callbacks.onTrackClick(item.trackIndex));
            th.itemView.setOnLongClickListener(v -> {
                callbacks.onTrackTagClick(track);
                return true;
            });
            th.tagBtn.setOnClickListener(v -> callbacks.onTrackTagClick(track));
        }
    }

    private void bindCover(ImageView view, TrackInfo track) {
        if (track == null || track.uri == null) {
            view.setTag(null);
            view.setImageResource(R.drawable.ic_app);
            return;
        }
        String key = track.uri.toString();
        view.setTag(key);
        Bitmap cached = track.artwork != null && !track.artwork.isRecycled()
            ? track.artwork : CoverArtLoader.peek(track.uri);
        if (cached != null && !cached.isRecycled()) {
            track.artwork = cached;
            view.setImageBitmap(cached);
            return;
        }
        view.setImageResource(R.drawable.ic_app);
        CoverArtLoader.load(host, track.uri, bitmap -> {
            if (bitmap != null && !bitmap.isRecycled()) {
                track.artwork = bitmap;
                host.runOnUiThread(() -> {
                    if (key.equals(view.getTag())) {
                        view.setImageBitmap(bitmap);
                    }
                });
            }
        });
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final LinearLayout container;
        HeaderHolder(LinearLayout container) { super(container); this.container = container; }
    }

    private static final class GroupHolder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title, sub;
        GroupHolder(View itemView, ImageView cover, TextView title, TextView sub) {
            super(itemView); this.cover = cover; this.title = title; this.sub = sub;
        }
    }

    private static final class TrackHolder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title, sub;
        final Button tagBtn;
        TrackHolder(View itemView, ImageView thumb, TextView title, TextView sub, Button tagBtn) {
            super(itemView); this.thumb = thumb; this.title = title; this.sub = sub; this.tagBtn = tagBtn;
        }
    }
}
