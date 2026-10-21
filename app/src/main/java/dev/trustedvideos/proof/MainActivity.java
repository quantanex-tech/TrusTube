// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.trustedvideos.proof.core.VideoEntry;
import dev.trustedvideos.proof.core.ChannelTransfer;
import dev.trustedvideos.proof.source.SourceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends androidx.appcompat.app.AppCompatActivity {
    enum Tab { HOME, LIBRARY, SEARCH, CHANNELS, PARENT }
    enum LibrarySection { FAVORITES, HISTORY }
    private ProofState state;
    private LinearLayout cards, content, navigation;
    private ScrollView scroll;
    private TextView status, measurements;
    private EditText search;
    private AlertDialog parentDialog;
    private TimerPage timers;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean active, parentUnlocked;
    private Tab tab = Tab.HOME;
    private LibrarySection librarySection = LibrarySection.FAVORITES;
    private String selectedChannel;
    private String notice = "";
    private static final int EXPORT_CHANNELS = 101, IMPORT_CHANNELS = 102;
    private String pendingExport;
    private List<ChannelTransfer.Channel> pendingImport;
    private boolean expectingImport;
    private long importRequest;
    private final Runnable changed = this::render;
    private final Runnable clock = new Runnable() {
        @Override public void run() {
            if (!active) return;
            state.updateUsage(true, false);
            if (measurements != null) measurements.setText(state.measurements());
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        state = ((ProofApplication) getApplication()).state();
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.SKY);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        navigation = new LinearLayout(this); navigation.setGravity(Gravity.CENTER);
        navigation.setBackground(Ui.background(Color.WHITE, 20, this));
        root.addView(navigation, new LinearLayout.LayoutParams(-1, Ui.dp(this, 64)));
        addTab(Tab.HOME, "Home", R.drawable.ic_home);
        addTab(Tab.LIBRARY, "Library", R.drawable.ic_library);
        addTab(Tab.SEARCH, "Search", R.drawable.ic_search);
        addTab(Tab.CHANNELS, "Channels", R.drawable.ic_channels);
        addTab(Tab.PARENT, "Parent", R.drawable.ic_parent);
        setContentView(root); buildPage();
        if (android.os.Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    () -> { if (!navigateBack()) finish(); });
        if (!state.pinSet()) handler.post(this::setupPin);
    }
    private void addTab(Tab value, String label, int icon) {
        LinearLayout button = new LinearLayout(this); button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER); button.setFocusable(true); button.setClickable(true);
        button.setContentDescription(label); button.setTag(value); button.setMinimumHeight(Ui.dp(this, 48));
        ImageView image = new ImageView(this); image.setImageResource(icon);
        button.addView(image, new LinearLayout.LayoutParams(Ui.dp(this, 25), Ui.dp(this, 25)));
        TextView text = Ui.text(this, label, 11, false); text.setGravity(Gravity.CENTER);
        button.addView(text); button.setOnClickListener(view -> selectTab(value));
        navigation.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
    }
    private void selectTab(Tab next) {
        if (next == tab) return;
        state.invalidateParentActions(); parentUnlocked = false;
        closeTimers();
        if (parentDialog != null) parentDialog.dismiss();
        tab = next; selectedChannel = null; notice = ""; buildPage();
        if (tab == Tab.PARENT) unlockParents();
    }
    private void buildPage() {
        content.removeAllViews(); search = null; measurements = null;
        if (tab == Tab.SEARCH) {
            search = new EditText(this); search.setSingleLine(); search.setTextSize(16);
            search.setHint("Search your videos and channels");
            search.setInputType(InputType.TYPE_CLASS_TEXT);
            search.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
            search.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) { render(); }
                public void afterTextChanged(Editable s) {}
            });
            content.addView(search);
        }
        status = Ui.text(this, "", 13, false);
        status.setPadding(Ui.dp(this, 18), Ui.dp(this, 6), Ui.dp(this, 18), Ui.dp(this, 6));
        content.addView(status);
        scroll = new ScrollView(this); scroll.setFillViewport(true);
        cards = new LinearLayout(this); cards.setOrientation(LinearLayout.VERTICAL);
        cards.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 12));
        cards.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) handler.post(this::render);
        });
        scroll.addView(cards);
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            if (y > oldY && y + scroll.getHeight() >= cards.getHeight() - Ui.dp(this, 180)) loadMore();
        });
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        render();
    }
    @Override protected void onResume() {
        super.onResume(); active = true; state.subscribe(changed); render(); clock.run();
        LazyImages.setActive(this, true);
        // Network refresh is opt-in through real channel data, never for an empty setup.
        state.refreshAllowedIfNeeded();
    }
    @Override protected void onPause() {
        LazyImages.setActive(this, false);
        active = false; handler.removeCallbacks(clock); state.updateUsage(false, false);
        state.unsubscribe(changed); state.invalidateParentActions(); parentUnlocked = false;
        if (parentDialog != null) parentDialog.dismiss();
        closeTimers();
        super.onPause();
    }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    private boolean navigateBack() {
        if (timers != null) {
            if (!timers.back()) closeTimers();
            render(); return true;
        }
        if (selectedChannel != null) { selectedChannel = null; buildPage(); return true; }
        if (tab != Tab.HOME) { selectTab(Tab.HOME); return true; }
        return false;
    }
    @android.annotation.SuppressLint("GestureBackNavigation") // API 33+ uses the registered dispatcher above.
    @Override public void onBackPressed() {
        if (!navigateBack()) super.onBackPressed();
    }
    private void render() {
        if (cards == null) return;
        int scrollY = scroll.getScrollY(); cards.removeAllViews(); measurements = null;
        for (int i = 0; i < navigation.getChildCount(); i++) {
            View item = navigation.getChildAt(i); boolean selected = item.getTag() == tab;
            item.setSelected(selected); item.setBackground(selected ? Ui.background(0x157E0AE5, 16, this) : null);
        }
        String message = state.expired() && tab != Tab.PARENT ? "Viewing time is up. Ask a parent for help." : notice;
        status.setText(message); status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        if (tab == Tab.PARENT) { renderParents(); return; }
        if (state.appExpired()) {
            cards.addView(Ui.text(this, "App time is up for today", 24, true));
            cards.addView(Ui.text(this, "Ask a parent to review your limits. Your weekly schedule starts again tomorrow.", 17, false));
            cards.addView(Ui.button(this, "Ask a parent", this::parents));
            return;
        }
        if (tab == Tab.LIBRARY) renderLibraryHeader();
        String query = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (selectedChannel != null && !state.policy.allowsChannel(selectedChannel)) selectedChannel = null;
        if (tab == Tab.CHANNELS && selectedChannel != null) {
            cards.addView(Ui.button(this, "← Channels", () -> { selectedChannel = null; buildPage(); }));
            cards.addView(Ui.text(this, state.channels().getOrDefault(selectedChannel, "Channel"), 21, true));
        }
        if (tab == Tab.CHANNELS && selectedChannel == null || tab == Tab.SEARCH) renderChannels(query);
        if (tab == Tab.CHANNELS && selectedChannel == null) return;
        boolean history = tab == Tab.LIBRARY && librarySection == LibrarySection.HISTORY;
        List<VideoEntry> entries = tab == Tab.LIBRARY
                ? history ? state.historyEntries() : state.favoriteEntries() : state.allowedEntries();
        entries.removeIf(entry -> selectedChannel != null && !selectedChannel.equals(entry.channelId)
                || !query.isEmpty() && !entry.title.toLowerCase(Locale.ROOT).contains(query)
                && !entry.channelName.toLowerCase(Locale.ROOT).contains(query));
        int width = cards.getWidth() > 0 ? cards.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int columns = Math.max(1, Math.min(3, width / Ui.dp(this, 320)));
        LinearLayout row = null;
        for (int i = 0; i < entries.size(); i++) {
            if (i % columns == 0) { row = new LinearLayout(this); cards.addView(row); }
            VideoEntry entry = entries.get(i);
            LinearLayout card = Ui.card(this, state, entry, false, () -> play(entry));
            if (history) card.addView(Ui.text(this, entry.channelName, 12, false));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 10)); row.addView(card, params);
        }
        if (row != null && entries.size() % columns != 0)
            for (int i = entries.size() % columns; i < columns; i++) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (entries.isEmpty()) cards.addView(Ui.text(this, tab == Tab.LIBRARY
                ? history ? "Videos you watch will appear here." : "Tap a video's star to save it here."
                : state.policy.channels().isEmpty() ? "Ask a parent to add your first channel."
                : state.loadingMore() ? "Loading your channels…"
                : "No videos to show yet. Try loading more from your channels.", 17, false));
        if (tab != Tab.LIBRARY && state.canLoadMore(selectedChannel))
            cards.addView(Ui.button(this, state.loadingMore() ? "Loading…" : "Load more videos", this::loadMore));
        scroll.post(() -> { if (!isDestroyed()) scroll.scrollTo(0, scrollY); });
    }
    private void renderLibraryHeader() {
        cards.addView(Ui.text(this, "Library", 24, true));
        LinearLayout sections = new LinearLayout(this);
        sections.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 12));
        for (LibrarySection section : LibrarySection.values()) {
            boolean selected = librarySection == section;
            TextView button = Ui.text(this, section == LibrarySection.FAVORITES ? "Favorites" : "History", 16, true);
            button.setGravity(Gravity.CENTER); button.setMinimumHeight(Ui.dp(this, 48));
            button.setFocusable(true); button.setClickable(true); button.setTag(section); button.setSelected(selected);
            button.setTextColor(selected ? Color.WHITE : Ui.INK);
            button.setBackground(Ui.background(selected ? Ui.PURPLE : Color.WHITE, 16, this));
            button.setOnClickListener(view -> {
                if (librarySection == section) return;
                librarySection = section; scroll.scrollTo(0, 0); render();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0); sections.addView(button, params);
        }
        cards.addView(sections);
        if (librarySection == LibrarySection.HISTORY) {
            TextView description = Ui.text(this, "Your last " + ProofState.WATCH_HISTORY_LIMIT
                    + " watched videos · newest first", 13, false);
            description.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 12)); cards.addView(description);
        }
    }
    private void renderChannels(String query) {
        LinearLayout row = null; int shown = 0;
        for (Map.Entry<String, String> channel : state.channels().entrySet()) {
            if (!state.policy.allowsChannel(channel.getKey()) || !query.isEmpty()
                    && !channel.getValue().toLowerCase(Locale.ROOT).contains(query)) continue;
            if (shown % 2 == 0) { row = new LinearLayout(this); cards.addView(row); }
            LinearLayout tile = new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER); tile.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10));
            tile.setFocusable(true); tile.setClickable(true); tile.setContentDescription("Open " + channel.getValue());
            ImageView avatar = new ImageView(this); avatar.setImageResource(R.drawable.ic_channels);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP); avatar.setBackground(Ui.background(Color.WHITE, 44, this));
            avatar.setClipToOutline(true); tile.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(this, 88), Ui.dp(this, 88)));
            Ui.image(this, state, avatar, state.avatar(channel.getKey()));
            TextView name = Ui.text(this, channel.getValue(), 14, false); name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END); name.setGravity(Gravity.CENTER); tile.addView(name);
            tile.setOnClickListener(view -> { tab = Tab.CHANNELS; selectedChannel = channel.getKey(); buildPage(); });
            row.addView(tile, new LinearLayout.LayoutParams(0, -2, 1)); shown++;
        }
        if (shown % 2 != 0) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (shown == 0 && tab == Tab.CHANNELS) cards.addView(Ui.text(this, "Your subscribed channels will appear here.", 17, false));
    }
    private void loadMore() {
        if (state.appExpired() || tab == Tab.PARENT || tab == Tab.LIBRARY || state.loadingMore()
                || tab == Tab.CHANNELS && selectedChannel == null) return;
        state.loadMoreAllowed(selectedChannel, text -> {
            if (!isDestroyed()) { notice = text; render(); }
        });
    }
    private void play(VideoEntry entry) {
        if (!state.pinSet() || state.expired() || !state.policy.allows(entry.id, entry.channelId)) {
            notice = "Ask a parent to unlock viewing before this video can play."; render(); return;
        }
        startActivityForResult(new Intent(this, PlayerActivity.class).putExtra("videoId", entry.id), 1);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EXPORT_CHANNELS) {
            final String snapshot = pendingExport; pendingExport = null;
            if (result != RESULT_OK || data == null || data.getData() == null || snapshot == null) return;
            final android.net.Uri uri = data.getData();
            state.worker.execute(() -> {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new java.io.IOException("Could not open the selected file.");
                    out.write(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    runOnUiThread(() -> { notice = "Channels exported. Share this file with your other device."; if (!isDestroyed()) render(); });
                } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed()) Ui.error(this, error); }); }
            });
            return;
        }
        if (request == IMPORT_CHANNELS) {
            boolean expected = expectingImport; expectingImport = false;
            if (!expected || result != RESULT_OK || data == null || data.getData() == null) return;
            final android.net.Uri uri = data.getData(); final long token = importRequest;
            state.worker.execute(() -> {
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new java.io.IOException("Could not read the selected file.");
                    List<ChannelTransfer.Channel> rows = ChannelTransfer.read(in);
                    runOnUiThread(() -> {
                        if (isDestroyed() || token != importRequest) return;
                        pendingImport = rows; notice = "Channel file ready. Unlock Parents to review and import it."; render();
                    });
                } catch (Exception error) { runOnUiThread(() -> { if (!isDestroyed() && token == importRequest) Ui.error(this, error); }); }
            });
            return;
        }
        if (request == 1 && result == RESULT_OK && data != null && data.getBooleanExtra("returnHome", false)) {
            if (tab != Tab.HOME) selectTab(Tab.HOME); else { selectedChannel = null; buildPage(); }
        }
    }
    private void setupPin() {
        if (isFinishing() || state.pinSet()) return;
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL);
        EditText first = new EditText(this), second = new EditText(this);
        for (EditText field : new EditText[]{first, second})
            field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        first.setHint("Choose six digits"); second.setHint("Enter the PIN again");
        fields.addView(first); fields.addView(second);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Parent PIN setup")
                .setMessage("Choose a PIN before adding channels. Keep it somewhere safe; recovery is not included in this test build.")
                .setView(fields).setCancelable(false).setPositiveButton("Save PIN", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String pin = first.getText().toString();
            if (!pin.matches("[0-9]{6}") || !pin.equals(second.getText().toString())) {
                second.setError("Enter the same six-digit PIN in both fields."); return;
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            state.credentials.execute(() -> {
                try {
                    state.setPin(pin);
                    runOnUiThread(() -> { if (!isDestroyed()) { dialog.dismiss(); notice = "PIN saved. Add your first trusted channel in Parents."; render(); } });
                } catch (Exception error) { runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        Ui.error(this, error);
                    }
                }); }
            });
        }));
        dialog.show();
    }
    private void parents() { selectTab(Tab.PARENT); }
    private void unlockParents() {
        if (!state.pinSet()) { setupPin(); return; }
        final long epoch = state.parentEpoch();
        parentDialog = Ui.parent(this, state, () -> {
            if (!active || tab != Tab.PARENT || state.parentEpoch() != epoch) return;
            parentUnlocked = true; render();
        });
    }
    private void renderParents() {
        if (parentUnlocked && timers != null) { timers.render(cards); return; }
        cards.addView(Ui.text(this, "Parents", 24, true));
        if (!parentUnlocked) {
            cards.addView(Ui.text(this, "Enter your parent PIN to open settings.", 16, false));
            cards.addView(Ui.button(this, "Unlock parent settings", this::unlockParents));
            return;
        }
        cards.addView(Ui.text(this, "Settings lock as soon as you leave this tab.", 14, false));
        cards.addView(Ui.button(this, "Add a trusted channel", this::addChannel));
        cards.addView(Ui.button(this, "Export subscribed channels", this::exportChannels));
        cards.addView(Ui.button(this, "Import subscribed channels", this::pickChannelImport));
        if (pendingImport != null) cards.addView(Ui.button(this, "Review channel import (" + pendingImport.size() + ")", this::confirmChannelImport));
        cards.addView(Ui.button(this, "Refresh / load another page", () -> chooseChannel(false)));
        cards.addView(Ui.button(this, "Remove a channel", () -> chooseChannel(true)));
        cards.addView(Ui.button(this, "Restore blocked content", this::restoreBlocks));
        cards.addView(Ui.button(this, "Manage timers", () -> {
            final long epoch = state.parentEpoch();
            timers = new TimerPage(this, state.timers,
                    () -> active && tab == Tab.PARENT && parentUnlocked && epoch == state.parentEpoch(),
                    this::render, () -> { closeTimers(); render(); });
            render();
        }));
        cards.addView(Ui.button(this, "Clear watch history", this::confirmClearHistory));
        cards.addView(Ui.button(this, "Set a short test timer", this::timer));
        cards.addView(Ui.button(this, "Reset proof measurements", state::resetUsage));
        cards.addView(Ui.button(this, (state.forceSplit() ? "Disable" : "Enable") + " separate audio/video test",
                () -> { state.forceSplit(!state.forceSplit()); render(); }));
        cards.addView(Ui.button(this, "Device information", this::deviceInfo));
        cards.addView(Ui.button(this, "Open-source notices", this::openNotices));
        measurements = Ui.text(this, state.measurements(), 13, false); cards.addView(measurements);
        cards.addView(Ui.text(this, "One local profile · Daily schedules are managed separately from proof timers.", 13, false));
    }
    private void closeTimers() { if (timers != null) { timers.close(); timers = null; } }
    private boolean authorizedParent() { return active && tab == Tab.PARENT && parentUnlocked; }
    private void exportChannels() {
        if (!authorizedParent()) return;
        try {
            pendingExport = state.exportChannels();
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, "TrusTube-channels.json"), EXPORT_CHANNELS);
        } catch (Exception error) { pendingExport = null; Ui.error(this, error); }
    }
    private void pickChannelImport() {
        if (!authorizedParent()) return;
        pendingImport = null; importRequest++; expectingImport = true;
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"}), IMPORT_CHANNELS);
        } catch (Exception error) { expectingImport = false; Ui.error(this, error); }
    }
    private void confirmChannelImport() {
        if (!authorizedParent() || pendingImport == null) return;
        final List<ChannelTransfer.Channel> rows = pendingImport;
        final long epoch = state.parentEpoch();
        StringBuilder preview = new StringBuilder("Merge " + rows.size() + " channels. Existing blocks, timers and favourites stay in place.\n\n");
        for (int i = 0; i < Math.min(20, rows.size()); i++) {
            ChannelTransfer.Channel row = rows.get(i);
            preview.append(row.name.isBlank() ? row.id : row.name).append(row.blocked ? " (blocked)" : "").append('\n');
        }
        if (rows.size() > 20) preview.append("… and ").append(rows.size() - 20).append(" more");
        parentDialog = new AlertDialog.Builder(this).setTitle("Import subscribed channels?").setMessage(preview)
                .setNegativeButton("Cancel", (d, w) -> { pendingImport = null; render(); })
                .setPositiveButton("Merge channels", (d, w) -> {
                    if (!authorizedParent() || epoch != state.parentEpoch() || pendingImport != rows) return;
                    try {
                        int added = state.importChannels(rows); pendingImport = null;
                        notice = "Imported " + rows.size() + " channels (" + added + " new). Loading their videos…";
                        render(); state.refreshAllowedIfNeeded();
                    } catch (IllegalArgumentException error) { Ui.error(this, error); }
                }).show();
    }
    private void confirmClearHistory() {
        final long epoch = state.parentEpoch();
        parentDialog = new AlertDialog.Builder(this).setTitle("Clear watch history?")
                .setMessage("Remove all recently watched videos from Library? New videos you watch will appear there again.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear history", (dialog, which) -> {
                    if (!active || tab != Tab.PARENT || !parentUnlocked || state.parentEpoch() != epoch) return;
                    state.clearHistory(); notice = "Watch history cleared."; render();
                }).show();
    }
    private void addChannel() {
        parentDialog = new ChannelSearchDialog(this, state,
                () -> active && tab == Tab.PARENT && parentUnlocked,
                state.sources::searchChannels, channel -> load(channel, false)).show();
    }
    private void load(String channel, boolean more) {
        final long revision = state.policy.revision();
        final long epoch = state.parentEpoch();
        notice = "Loading channel videos…"; render();
        state.worker.execute(() -> {
            try {
                SourceRepository.ChannelBatch first = more ? state.sources.loadMore(channel) : state.sources.loadChannel(channel);
                SourceRepository.ChannelBatch second = !more && first.hasMore ? state.sources.loadMore(first.id) : null;
                runOnUiThread(() -> {
                    if (!active || tab != Tab.PARENT || !parentUnlocked || state.parentEpoch() != epoch) return;
                    if (state.policy.revision() != revision) {
                        notice = "Approvals changed while loading. A parent can retry.";
                        if (!isDestroyed()) render();
                        return;
                    }
                    state.add(first); if (second != null) state.add(second);
                    notice = "Loaded " + (first.videos.size() + (second == null ? 0 : second.videos.size()))
                            + " videos from " + first.name + ".";
                    if (!isDestroyed()) render();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!active || tab != Tab.PARENT || !parentUnlocked || state.parentEpoch() != epoch) return;
                    notice = "Loading failed. Your existing approvals are unchanged.";
                    if (!isDestroyed()) { render(); Ui.error(this, error); }
                });
            }
        });
    }
    private void chooseChannel(boolean remove) {
        List<String> ids = new ArrayList<>(state.policy.channels());
        if (ids.isEmpty()) { notice = "Add a channel first."; render(); return; }
        String[] names = ids.stream().map(state::label).toArray(String[]::new);
        parentDialog = new AlertDialog.Builder(this).setTitle(remove ? "Remove a channel" : "Refresh a channel")
                .setItems(names, (dialog, index) -> {
                    String id = ids.get(index);
                    if (remove) state.removeChannel(id);
                    else parentDialog = new AlertDialog.Builder(this).setTitle(state.label(id))
                            .setItems(new String[]{"Refresh first two pages", "Load another page"},
                                    (choice, option) -> load(id, option == 1)).show();
                }).show();
    }
    private void restoreBlocks() {
        List<String> videos = new ArrayList<>(state.policy.blockedVideos());
        List<String> channels = new ArrayList<>(state.policy.blockedChannels());
        List<String> labels = new ArrayList<>();
        videos.forEach(id -> labels.add("Video: " + state.label(id)));
        channels.forEach(id -> labels.add("Channel: " + state.label(id)));
        if (labels.isEmpty()) { notice = "Nothing is blocked."; render(); return; }
        parentDialog = new AlertDialog.Builder(this).setTitle("Restore blocked content")
                .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                    if (index < videos.size()) state.restoreVideo(videos.get(index));
                    else state.restoreChannel(channels.get(index - videos.size()));
                }).show();
    }
    private void timer() {
        parentDialog = new AlertDialog.Builder(this).setTitle("Proof timer mode")
                .setItems(new String[]{"Watching time", "App foreground time", "Disable test timer"}, (dialog, mode) -> {
                    if (mode == 2) { state.testLimit(true, 0); return; }
                    EditText input = new EditText(this); input.setInputType(InputType.TYPE_CLASS_NUMBER); input.setText("30");
                    parentDialog = new AlertDialog.Builder(this).setTitle("Test duration in seconds (1–600)")
                            .setView(input).setPositiveButton("Start fresh test", (choice, which) -> {
                                try {
                                    int seconds = Integer.parseInt(input.getText().toString());
                                    if (seconds < 1 || seconds > 600) throw new IllegalArgumentException("Use 1 to 600 seconds.");
                                    state.testLimit(mode == 0, seconds);
                                } catch (Exception error) { Ui.error(this, error); }
                            }).setNegativeButton("Cancel", null).show();
                }).show();
    }
    private void openNotices() {
        try (java.io.InputStream stream = getAssets().open("NOTICE.txt")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            TextView text = Ui.text(this, bytes.toString("UTF-8"), 14, false);
            text.setTextIsSelectable(true); text.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
            ScrollView scroll = new ScrollView(this); scroll.addView(text);
            parentDialog = new AlertDialog.Builder(this).setTitle("Open-source notices")
                    .setView(scroll).setPositiveButton("Close", null).show();
        } catch (java.io.IOException error) { Ui.error(this, error); }
    }
    private void deviceInfo() {
        parentDialog = new AlertDialog.Builder(this).setTitle("Test device")
                .setMessage(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                        + "\nAndroid " + android.os.Build.VERSION.RELEASE + " · API " + android.os.Build.VERSION.SDK_INT
                        + "\nABIs: " + String.join(", ", android.os.Build.SUPPORTED_ABIS)
                        + "\n" + state.measurements()).setPositiveButton("OK", null).show();
    }
}
