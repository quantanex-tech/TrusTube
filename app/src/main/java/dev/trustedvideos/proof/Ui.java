// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import dev.trustedvideos.proof.core.VideoEntry;

final class Ui {
    static final int INK = Color.BLACK, PURPLE = Color.rgb(126, 10, 229), SKY = Color.rgb(239, 250, 255);
    private static ImageCache images;
    static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    static GradientDrawable background(int color, int radius, Context context) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(context, radius));
        return shape;
    }
    static TextView text(Context context, String value, int size, boolean bold) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(size); view.setTextColor(INK);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }
    static Button button(Context context, String label, Runnable action) {
        Button button = new com.google.android.material.button.MaterialButton(context); button.setText(label); button.setAllCaps(false);
        button.setOnClickListener(view -> action.run()); return button;
    }
    static ImageButton icon(Context context, int resource, String label, int color, Runnable action) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(resource); button.setImageTintList(ColorStateList.valueOf(color));
        button.setContentDescription(label); button.setMinimumWidth(dp(context, 48));
        button.setMinimumHeight(dp(context, 48)); button.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        android.util.TypedValue value = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true);
        button.setBackgroundResource(value.resourceId);
        button.setOnClickListener(view -> action.run()); return button;
    }
    static String duration(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        return seconds >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
    static void error(Activity activity, Throwable error) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        String message = error instanceof SecurityException || error instanceof IllegalArgumentException
                || error instanceof IllegalStateException ? error.getMessage()
                : "Could not load this content. Check the connection and try again. (" + error.getClass().getSimpleName() + ")";
        new AlertDialog.Builder(activity).setTitle("Unable to continue").setMessage(message)
                .setPositiveButton("OK", null).show();
    }
    static AlertDialog parent(Activity activity, ProofState state, Runnable action) {
        final long epoch = state.parentEpoch();
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("Parent PIN");
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Parents")
                .setView(input).setPositiveButton("Unlock", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (state.pinWaitMs() > 0) { input.setError("Try again in " + (state.pinWaitMs() / 1000 + 1) + " seconds."); return; }
            String pin = input.getText().toString();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            state.credentials.execute(() -> {
                boolean valid = state.verifyPin(pin);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed() || !dialog.isShowing()) return;
                    if (epoch != state.parentEpoch()) { dialog.dismiss(); return; }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    if (valid) { dialog.dismiss(); action.run(); }
                    else input.setError("PIN not recognised.");
                });
            });
        }));
        dialog.show();
        return dialog;
    }
    static void blockMenu(Activity activity, ProofState state, View anchor, VideoEntry entry) {
        videoMenu(activity, state, anchor, entry, null, null);
    }
    static PopupMenu videoMenu(Activity activity, ProofState state, View anchor, VideoEntry entry,
                               Runnable blocked, Runnable dismissed) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add(state.isFavorite(entry.id) ? "Remove favorite" : "Add favorite")
                .setOnMenuItemClickListener(item -> { state.toggleFavorite(entry.id); return true; });
        menu.getMenu().add("Block this video").setOnMenuItemClickListener(item -> {
            confirmBlock(activity, state, entry, false, blocked); return true;
        });
        menu.getMenu().add("Block this channel").setOnMenuItemClickListener(item -> {
            confirmBlock(activity, state, entry, true, blocked); return true;
        });
        if (dismissed != null) menu.setOnDismissListener(ignored -> dismissed.run());
        menu.show();
        return menu;
    }
    static AlertDialog confirmBlock(Activity activity, ProofState state, VideoEntry entry, boolean channel, Runnable blocked) {
        return new AlertDialog.Builder(activity).setTitle(channel ? "Block this channel?" : "Block this video?")
                .setMessage((channel ? entry.channelName : entry.title) + " will be hidden. Only a parent can restore it.")
                .setNegativeButton("Cancel", null).setPositiveButton("Block", (dialog, which) -> {
                    if (channel) state.blockChannel(entry.channelId); else state.blockVideo(entry.id);
                    if (blocked != null) blocked.run();
                }).show();
    }
    static LinearLayout card(Activity activity, ProofState state, VideoEntry entry, boolean compact, Runnable play) {
        LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, compact ? 0 : 8), dp(activity, compact ? 0 : 8), dp(activity, compact ? 0 : 8), dp(activity, compact ? 0 : 5));
        card.setBackground(background(compact ? Color.TRANSPARENT : Color.WHITE, 12, activity));
        FrameLayout imageFrame = new FrameLayout(activity);
        ImageView thumbnail = new androidx.appcompat.widget.AppCompatImageView(activity) {
            private final android.graphics.Path clip = new android.graphics.Path();
            @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
                super.onSizeChanged(width, height, oldWidth, oldHeight);
                clip.reset(); clip.addRoundRect(new android.graphics.RectF(0, 0, width, height),
                        dp(activity, 12), dp(activity, 12), android.graphics.Path.Direction.CW);
            }
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                int saved = canvas.save(); canvas.clipPath(clip); super.onDraw(canvas); canvas.restoreToCount(saved);
            }
            @Override protected void onMeasure(int width, int height) {
                int measuredWidth = View.MeasureSpec.getSize(width);
                super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(Math.max(1,
                        compact ? dp(activity, 78) : Math.round(measuredWidth * 106f / 240f)), View.MeasureSpec.EXACTLY));
            }
        };
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setContentDescription(entry.title); thumbnail.setClipToOutline(true);
        thumbnail.setBackground(background(Color.rgb(196, 196, 196), 12, activity));
        thumbnail.setImageResource(R.drawable.ic_video_placeholder);
        imageFrame.addView(thumbnail, new FrameLayout.LayoutParams(-1, -2));
        ImageButton more = icon(activity, R.drawable.ic_more, "Options for " + entry.title, Color.WHITE, () -> {});
        more.setOnClickListener(view -> blockMenu(activity, state, more, entry));
        if (!compact) imageFrame.addView(more, new FrameLayout.LayoutParams(dp(activity, 48), dp(activity, 48), Gravity.TOP | Gravity.END));
        card.addView(imageFrame, new LinearLayout.LayoutParams(-1, -2));
        image(activity, state, thumbnail, entry.thumbnail);
        TextView title = text(activity, entry.title, 12, false);
        title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, dp(activity, 3), 0, 0);
        if (!compact) card.addView(title);
        card.setContentDescription(entry.title + " · " + entry.channelName);
        card.setFocusable(true); card.setClickable(true); card.setOnClickListener(view -> play.run());
        card.setOnLongClickListener(view -> { blockMenu(activity, state, card, entry); return true; });
        return card;
    }
    static void image(Activity activity, ProofState state, ImageView view, String url) {
        view.setTag(url);
        if (images == null) images = new ImageCache(activity.getApplicationContext());
        java.lang.ref.WeakReference<Activity> owner = new java.lang.ref.WeakReference<>(activity);
        java.lang.ref.WeakReference<ImageView> target = new java.lang.ref.WeakReference<>(view);
        images.load(url, bitmap -> {
            Activity current = owner.get(); ImageView image = target.get();
            if (current != null && image != null && !current.isDestroyed() && url.equals(image.getTag()))
                image.setImageBitmap(bitmap);
        });
    }
}
