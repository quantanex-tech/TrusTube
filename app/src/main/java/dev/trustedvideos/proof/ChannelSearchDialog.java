// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.trustedvideos.proof.source.SourceRepository.ChannelResult;
import dev.trustedvideos.proof.source.SourceRepository.SearchBatch;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Parent-only discovery. Closing, editing or relocking invalidates in-flight results. */
final class ChannelSearchDialog {
    interface Discovery { SearchBatch search(String term, SearchBatch previous) throws Exception; }
    private final Activity activity;
    private final ProofState state;
    private final BooleanSupplier authorized;
    private final Discovery discovery;
    private final Consumer<String> add;
    private final long epoch;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> shown = new HashSet<>();
    private AlertDialog dialog;
    private EditText input;
    private TextView status;
    private LinearLayout results;
    private Button search, more;
    private SearchBatch batch;
    private Future<?> pending;
    private long generation, selectionGeneration;

    ChannelSearchDialog(Activity activity, ProofState state, BooleanSupplier authorized,
                        Discovery discovery, Consumer<String> add) {
        this.activity = activity; this.state = state; this.authorized = authorized;
        this.discovery = discovery; this.add = add; epoch = state.parentEpoch();
    }
    AlertDialog show() {
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(activity, 20), 0, Ui.dp(activity, 20), 0);
        input = new EditText(activity); input.setSingleLine();
        input.setHint("Name, @handle or channel URL"); input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setContentDescription("Find a trusted channel"); body.addView(input);
        search = Ui.button(activity, "Search channels", () -> search(false)); body.addView(search);
        status = Ui.text(activity, "Search YouTube, then choose a channel to add.", 14, false); body.addView(status);
        ScrollView scroll = new ScrollView(activity);
        results = new LinearLayout(activity); results.setOrientation(LinearLayout.VERTICAL); scroll.addView(results);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 300)));
        more = Ui.button(activity, "More channels", () -> search(true)); more.setVisibility(View.GONE); body.addView(more);
        dialog = new AlertDialog.Builder(activity).setTitle("Add a trusted channel").setView(body)
                .setNegativeButton("Cancel", null).create();
        dialog.setOnDismissListener(ignored -> { generation++; if (pending != null) pending.cancel(true); });
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                generation++; selectionGeneration++; if (pending != null) pending.cancel(true);
                batch = null; shown.clear(); results.removeAllViews(); more.setVisibility(View.GONE);
                search.setEnabled(true); status.setText("Search YouTube, then choose a channel to add.");
            }
            public void afterTextChanged(Editable text) {}
        });
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            search(false); return true;
        });
        dialog.show(); return dialog;
    }
    private boolean current(long serial) {
        return serial == generation && dialog.isShowing() && !activity.isDestroyed()
                && state.parentEpoch() == epoch && authorized.getAsBoolean();
    }
    private void search(boolean append) {
        if (!current(generation)) return;
        String term = input.getText().toString().trim();
        if (term.isEmpty() || term.length() > 200) { input.setError("Use 1–200 characters."); return; }
        if (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) instanceof android.view.inputmethod.InputMethodManager keyboard)
            keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        if (pending != null) pending.cancel(true);
        long serial = ++generation;
        SearchBatch previous = append ? batch : null;
        if (!append) { selectionGeneration++; shown.clear(); results.removeAllViews(); batch = null; }
        search.setEnabled(false); more.setEnabled(false); status.setText("Searching YouTube…");
        pending = state.worker.submit(() -> {
            try {
                SearchBatch found = discovery.search(term, previous);
                main.post(() -> {
                    if (!current(serial)) return;
                    batch = found; search.setEnabled(true); more.setEnabled(true);
                    for (ChannelResult channel : found.channels) if (shown.add(channel.id)) addResult(channel, selectionGeneration);
                    status.setText(shown.isEmpty() ? "No channels found. Try another name or a channel URL."
                            : "Choose Add for the channel you trust.");
                    more.setVisibility(found.hasMore ? View.VISIBLE : View.GONE);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (!current(serial)) return;
                    search.setEnabled(true); more.setEnabled(true);
                    status.setText("Couldn’t search YouTube. Check your connection and try again.");
                });
            }
        });
    }
    private void addResult(ChannelResult channel, long serial) {
        LinearLayout row = new LinearLayout(activity); row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(activity, 12), 0, Ui.dp(activity, 12));
        LinearLayout heading = new LinearLayout(activity); heading.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(activity); icon.setImageResource(R.drawable.ic_channels);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP); icon.setBackground(Ui.background(Ui.SKY, 24, activity));
        icon.setClipToOutline(true); Ui.image(activity, state, icon, channel.avatar);
        heading.addView(icon, new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)));
        TextView name = Ui.text(activity, channel.name, 17, true); name.setPadding(Ui.dp(activity, 10), 0, 0, 0);
        heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(heading);
        row.addView(Ui.text(activity, channel.url, 12, false));
        if (channel.description != null && !channel.description.isEmpty()) {
            TextView description = Ui.text(activity, channel.description, 13, false);
            description.setMaxLines(3); row.addView(description);
        }
        boolean subscribed = state.policy.channels().contains(channel.id);
        Button choose = Ui.button(activity, subscribed ? "Already subscribed" : "Add " + channel.name, () -> {
            if (serial != selectionGeneration || !current(generation)) return;
            dialog.dismiss(); add.accept(channel.url);
        });
        choose.setEnabled(!subscribed); row.addView(choose); results.addView(row);
    }
}
