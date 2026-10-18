// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import dev.trustedvideos.proof.core.VideoEntry;
import java.util.List;

/** One overlay owns the header, rail and timeline, so their fade state stays in sync. */
final class PlayerControls extends FrameLayout {
    interface Actions {
        void back(); void togglePlay(); void favorite(); void menu(View anchor);
        void seek(long positionMs); void play(VideoEntry entry); void more();
    }
    private final Activity activity;
    private final ProofState state;
    private final Actions actions;
    private final FrameLayout overlay;
    private final ImageView avatar;
    private final ImageButton play, star;
    private final TextView title, remaining;
    private final SeekBar timeline;
    private final HorizontalScrollView railScroll;
    private final LinearLayout rail;
    private boolean menuOpen;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean playing, showing = true, scrubbing;
    private long duration;
    private String videoId = "";
    private final Runnable autoHide = new Runnable() {
        @Override public void run() {
            if (!playing || scrubbing) return;
            if (!activity.hasWindowFocus() || !isInTouchMode() && overlay.hasFocus()) {
                handler.postDelayed(this, 1000); return;
            }
            hide();
        }
    };
    PlayerControls(Activity activity, ProofState state, Actions actions) {
        super(activity); this.activity = activity; this.state = state; this.actions = actions;
        View tapSurface = new View(activity);
        tapSurface.setContentDescription("Show playback controls");
        tapSurface.setOnClickListener(view -> { if (showing && playing) hide(); else show(); });
        addView(tapSurface, new FrameLayout.LayoutParams(-1, -1));
        overlay = new FrameLayout(activity); addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout header = new LinearLayout(activity); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(activity, 12), 0, Ui.dp(activity, 12), 0);
        header.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB0000000, 0x00000000}));
        header.addView(Ui.icon(activity, R.drawable.ic_back, "Back to listings", Color.WHITE, actions::back), square(48));
        avatar = new ImageView(activity); avatar.setImageResource(R.drawable.ic_channels);
        avatar.setBackground(Ui.background(Color.WHITE, 18, activity)); avatar.setClipToOutline(true);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP); header.addView(avatar, square(36));
        title = Ui.text(activity, "", 14, false); title.setTextColor(Color.WHITE);
        title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END); title.setPadding(Ui.dp(activity, 10), 0, Ui.dp(activity, 8), 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton menu = Ui.icon(activity, R.drawable.ic_more, "Video options", Color.WHITE, () -> {});
        menu.setOnClickListener(view -> { show(); actions.menu(menu); }); header.addView(menu, square(48));
        overlay.addView(header, new FrameLayout.LayoutParams(-1, Ui.dp(activity, 60), Gravity.TOP));
        star = Ui.icon(activity, R.drawable.ic_favorite, "Add favorite", Color.WHITE, () -> { show(); actions.favorite(); });
        FrameLayout.LayoutParams starPosition = new FrameLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48), Gravity.TOP | Gravity.END);
        starPosition.topMargin = Ui.dp(activity, 62); starPosition.rightMargin = Ui.dp(activity, 12); overlay.addView(star, starPosition);
        LinearLayout footer = new LinearLayout(activity); footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(Ui.dp(activity, 12), 0, Ui.dp(activity, 18), 0);
        footer.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB0000000, 0x00000000}));
        play = Ui.icon(activity, R.drawable.ic_play, "Play", Color.WHITE, () -> { show(); actions.togglePlay(); });
        footer.addView(play, square(56));
        timeline = new SeekBar(activity); timeline.setMax(10000); timeline.setContentDescription("Video position");
        timeline.setProgressTintList(ColorStateList.valueOf(Ui.PURPLE));
        timeline.setProgressBackgroundTintList(ColorStateList.valueOf(Color.WHITE));
        timeline.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        footer.addView(timeline, new LinearLayout.LayoutParams(0, Ui.dp(activity, 48), 1));
        remaining = Ui.text(activity, "0:00", 14, false); remaining.setTextColor(Color.WHITE);
        footer.addView(remaining); overlay.addView(footer, new FrameLayout.LayoutParams(-1, Ui.dp(activity, 56), Gravity.BOTTOM));
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar seek) { scrubbing = true; show(); }
            public void onStopTrackingTouch(SeekBar seek) { scrubbing = false; show(); }
            public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
                if (fromUser && duration > 0) { actions.seek(duration * progress / 10000); show(); }
            }
        });
        railScroll = new HorizontalScrollView(activity); railScroll.setHorizontalScrollBarEnabled(false);
        railScroll.setPadding(Ui.dp(activity, 68), 0, Ui.dp(activity, 12), 0); railScroll.setClipToPadding(false);
        rail = new LinearLayout(activity); rail.setOrientation(LinearLayout.HORIZONTAL); railScroll.addView(rail);
        FrameLayout.LayoutParams railPosition = new FrameLayout.LayoutParams(-1, Ui.dp(activity, 86), Gravity.BOTTOM);
        railPosition.bottomMargin = Ui.dp(activity, 64); overlay.addView(railScroll, railPosition);
        railScroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            show();
            if (x > oldX && x + railScroll.getWidth() >= rail.getWidth() - Ui.dp(activity, 240)) actions.more();
        });
        setOnApplyWindowInsetsListener((view, insets) -> {
            setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
    }
    private LinearLayout.LayoutParams square(int size) { return new LinearLayout.LayoutParams(Ui.dp(activity, size), Ui.dp(activity, size)); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && showing) show();
        return super.dispatchTouchEvent(event);
    }
    void update(VideoEntry entry, long position, long total, boolean favorite) {
        duration = Math.max(0, total);
        if (entry != null) {
            title.setText(entry.title + " · " + entry.channelName);
            if (!videoId.equals(entry.id)) {
                videoId = entry.id; avatar.setImageResource(R.drawable.ic_channels);
                Ui.image(activity, state, avatar, state.avatar(entry.channelId));
                avatar.setContentDescription(entry.channelName);
            }
        }
        star.setImageResource(favorite ? R.drawable.ic_favorite_filled : R.drawable.ic_favorite);
        star.setContentDescription(favorite ? "Remove favorite" : "Add favorite"); star.setSelected(favorite);
        if (!scrubbing) timeline.setProgress(duration == 0 ? 0 : (int) Math.min(10000, position * 10000 / duration));
        remaining.setText(Ui.duration(Math.max(0, duration - position)));
        remaining.setContentDescription("Video time remaining " + remaining.getText());
    }
    void setVideos(List<VideoEntry> entries) {
        int x = railScroll.getScrollX(); rail.removeAllViews();
        for (VideoEntry entry : entries) {
            if (entry.id.equals(videoId)) continue;
            LinearLayout card = Ui.card(activity, state, entry, true, () -> actions.play(entry));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(activity, 142), -2);
            params.setMargins(0, 0, Ui.dp(activity, 20), 0); rail.addView(card, params);
        }
        if (state.canLoadMore(null)) rail.addView(Ui.button(activity, "More videos", actions::more), new LinearLayout.LayoutParams(Ui.dp(activity, 130), Ui.dp(activity, 78)));
        railScroll.post(() -> railScroll.scrollTo(x, 0));
    }
    void setPlaying(boolean value) {
        if (playing == value) return;
        playing = value; play.setImageResource(value ? R.drawable.ic_pause : R.drawable.ic_play);
        play.setContentDescription(value ? "Pause" : "Play"); show();
    }
    void show() {
        showing = true; overlay.animate().cancel(); overlay.setVisibility(View.VISIBLE);
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        overlay.animate().alpha(1f).setDuration(150).start();
        handler.removeCallbacks(autoHide); if (playing && !scrubbing && !menuOpen) handler.postDelayed(autoHide, 5000);
    }
    void holdMenu(boolean open) { menuOpen = open; show(); }
    private void hide() {
        if (!playing || scrubbing || menuOpen) return;
        showing = false; overlay.animate().cancel();
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        overlay.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            if (!showing) overlay.setVisibility(View.INVISIBLE);
        }).start();
    }
    void stop() { handler.removeCallbacksAndMessages(null); overlay.animate().cancel(); }
    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
}
