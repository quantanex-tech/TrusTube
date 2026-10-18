// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.materialswitch.MaterialSwitch;
import dev.trustedvideos.proof.core.TimerSchedule;
import dev.trustedvideos.proof.core.TimerSchedule.Type;
import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Parent-tab subpages: authentication remains owned by MainActivity, never by a launch intent. */
final class TimerPage {
    private final Activity activity;
    private final DailyTimers timers;
    private final BooleanSupplier authorized;
    private final Runnable redraw, exit;
    private BottomSheetDialog sheet;
    private AlertDialog exactDialog;
    private Draft draft;
    private Type selectedType = Type.WATCHING;
    private boolean todayPage;
    private String screen = "";

    private static final class Draft {
        String id, name;
        Type type;
        boolean enabled;
        int[] days;
        Draft(TimerSchedule value) {
            id = value.id; name = value.name; type = value.type; enabled = value.enabled; days = value.days();
        }
    }
    TimerPage(Activity activity, DailyTimers timers, BooleanSupplier authorized, Runnable redraw, Runnable exit) {
        this.activity = activity; this.timers = timers; this.authorized = authorized; this.redraw = redraw; this.exit = exit;
    }
    private boolean allowed() { return authorized.getAsBoolean() && !activity.isFinishing() && !activity.isDestroyed(); }
    void close() {
        if (exactDialog != null) exactDialog.dismiss();
        if (sheet != null) sheet.dismiss();
    }
    boolean back() {
        close();
        if (draft != null) { draft = null; return true; }
        if (todayPage) { todayPage = false; return true; }
        return false;
    }
    void render(LinearLayout root) {
        if (!allowed()) return;
        String current = draft != null ? draft.id : todayPage ? "today" : "list";
        if (!current.equals(screen)) {
            screen = current;
            root.post(() -> { if (root.getParent() instanceof ScrollView scroll) scroll.scrollTo(0, 0); });
        }
        if (draft != null) { renderDraft(root); return; }
        header(root, todayPage ? "Today" : "Manage timers", () -> {
            if (todayPage) { todayPage = false; redraw.run(); } else exit.run();
        });
        if (todayPage) { renderToday(root); return; }
        root.addView(Ui.text(activity, "Set limits for watching videos and using TrusTube.", 16, false));
        LinearLayout summary = surface(root);
        summary.addView(Ui.text(activity, "Daily time limits", 21, true));
        for (Type type : Type.values()) summary.addView(Ui.text(activity,
                label(type) + " · " + timers.remainingLabel(type), 16, false));
        summary.addView(button("Today  ›", () -> { todayPage = true; redraw.run(); }));
        root.addView(Ui.text(activity, "Weekly schedules", 22, true));
        root.addView(Ui.text(activity, "Create weekday and weekend timers. When schedules overlap, the smallest limit applies. Both timer types can run together.", 14, false));
        if (timers.unreadable()) root.addView(Ui.text(activity,
                "Saved timer settings could not be read. Viewing is locked. Save a timer to replace these settings.", 16, true));
        if (timers.schedules().isEmpty()) root.addView(Ui.text(activity, "No weekly timers yet. Add one to build your routine.", 16, false));
        for (TimerSchedule schedule : timers.schedules()) {
            LinearLayout card = surface(root);
            MaterialSwitch enabled = new MaterialSwitch(activity);
            enabled.setText(schedule.name); enabled.setTextSize(19); enabled.setChecked(schedule.enabled);
            enabled.setOnCheckedChangeListener((button, checked) -> mutate(() -> timers.save(schedule.enabled(checked))));
            card.addView(enabled, new LinearLayout.LayoutParams(-1, -2));
            card.addView(Ui.text(activity, label(schedule.type) + " · " + summary(schedule), 14, false));
            card.addView(button("Edit " + schedule.name, () -> { draft = new Draft(schedule); redraw.run(); }));
        }
        root.addView(button("+ Add timer", () -> {
            draft = new Draft(new TimerSchedule(UUID.randomUUID().toString(), "New timer", selectedType, true,
                    new int[]{60, 60, 60, 60, 60, 60, 60}));
            redraw.run();
        }));
    }
    private void renderToday(LinearLayout root) {
        root.addView(Ui.text(activity, "Changes here last only until midnight and leave your weekly schedules unchanged.", 16, false));
        LinearLayout card = surface(root);
        card.addView(Ui.text(activity, "Timer type", 14, true));
        card.addView(typeSelector(selectedType, type -> { selectedType = type; redraw.run(); }));
        card.addView(Ui.text(activity, timers.remainingLabel(selectedType), 28, true));
        card.addView(Ui.text(activity, "Used today: " + Ui.duration(timers.used(selectedType)), 15, false));
        card.addView(Ui.text(activity, timers.hasToday(selectedType) ? "Today-only override" : "Using weekly schedule", 14, false));
        card.addView(button("Change today's limit", () -> editDuration(-1)));
        if (timers.hasToday(selectedType)) card.addView(button("Use weekly schedule", () ->
                mutate(() -> timers.today(selectedType, null))));
        root.addView(Ui.text(activity, "Watching counts video playback, excluding pauses and buffering. Full app includes browsing. Time already used today stays used when a limit changes.", 15, false));
    }
    private void renderDraft(LinearLayout root) {
        header(root, "Weekly schedule", () -> { draft = null; redraw.run(); });
        LinearLayout details = surface(root);
        EditText name = new EditText(activity); name.setSingleLine(); name.setText(draft.name);
        name.setHint("Timer name"); name.setContentDescription("Timer name");
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});
        name.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { if (draft != null) draft.name = s.toString(); }
            public void afterTextChanged(android.text.Editable s) {}
        });
        details.addView(name);
        details.addView(typeSelector(draft.type, type -> { if (draft != null) draft.type = type; }));
        MaterialSwitch enabled = new MaterialSwitch(activity); enabled.setText("Timer enabled"); enabled.setChecked(draft.enabled);
        enabled.setOnCheckedChangeListener((view, checked) -> { if (draft != null) draft.enabled = checked; }); details.addView(enabled);
        LinearLayout presets = new LinearLayout(activity);
        presets.addView(button("Weekdays", () -> preset(false)), new LinearLayout.LayoutParams(0, -2, 1));
        presets.addView(button("Weekend", () -> preset(true)), new LinearLayout.LayoutParams(0, -2, 1)); details.addView(presets);
        for (int day = 0; day < 7; day++) {
            final int index = day;
            LinearLayout row = surface(root); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            TextView dayLabel = Ui.text(activity, dayName(day), 17, true);
            row.addView(dayLabel, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(button(duration(draft.days[day]) + "  ›", () -> editDuration(index)));
            row.setContentDescription(dayName(day) + ": " + duration(draft.days[day]));
        }
        root.addView(button("Save timer", () -> {
            if (draft.name.trim().isEmpty()) { name.setError("Name this timer."); return; }
            mutate(() -> {
                timers.save(new TimerSchedule(draft.id, draft.name, draft.type, draft.enabled, draft.days)); draft = null;
            });
        }));
        if (timers.schedules().stream().anyMatch(item -> item.id.equals(draft.id)))
            root.addView(button("Delete timer", () -> {
                exactDialog = new AlertDialog.Builder(activity).setTitle("Delete this timer?")
                        .setMessage("Time already used today will be kept.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Delete", (dialog, which) -> mutate(() -> {
                            timers.delete(draft.id); draft = null;
                        })).show();
            }));
        root.addView(button("Cancel", () -> { draft = null; redraw.run(); }));
    }
    private void preset(boolean weekend) {
        if (!allowed() || draft == null) return;
        int duration = Arrays.stream(draft.days).filter(value -> value >= 0).findFirst().orElse(60);
        for (int day = 0; day < 7; day++) draft.days[day] = (day >= 5) == weekend ? duration : -1;
        redraw.run();
    }
    private void editDuration(int day) {
        if (!allowed()) return;
        final boolean today = day < 0;
        final Type[] type = {today ? selectedType : draft.type};
        int initial = today ? timers.allowance(type[0]) : draft.days[day];
        final int[] minutes = {initial < 0 ? 60 : initial};
        sheet = new BottomSheetDialog(activity);
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 18), Ui.dp(activity, 20), Ui.dp(activity, 24));
        TextView title = Ui.text(activity, today ? "Today" : dayName(day), 26, true); title.setGravity(Gravity.CENTER); body.addView(title);
        if (today) body.addView(Ui.text(activity, "Your weekly schedule stays the same.", 15, false));
        MaterialSwitch limit = new MaterialSwitch(activity); limit.setText("Daily limit"); limit.setChecked(initial >= 0);
        TextView readout = Ui.text(activity, duration(minutes[0]), 32, true); readout.setGravity(Gravity.CENTER);
        Runnable refresh = () -> { readout.setText(duration(minutes[0])); readout.setAlpha(limit.isChecked() ? 1f : .4f); };
        if (today) body.addView(typeSelector(type[0], next -> {
            type[0] = next; int value = timers.allowance(next); limit.setChecked(value >= 0);
            minutes[0] = value < 0 ? 60 : value; refresh.run();
        }));
        body.addView(limit, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 64)));
        LinearLayout stepper = new LinearLayout(activity); stepper.setGravity(Gravity.CENTER_VERTICAL);
        stepper.addView(button("−", () -> { if (limit.isChecked()) { minutes[0] = Math.max(0, minutes[0] - 15); refresh.run(); } }),
                new LinearLayout.LayoutParams(Ui.dp(activity, 64), Ui.dp(activity, 64)));
        readout.setContentDescription("Set exact duration in minutes"); readout.setFocusable(true); readout.setClickable(true);
        readout.setOnClickListener(view -> {
            if (!allowed() || !limit.isChecked()) return;
            EditText exact = new EditText(activity); exact.setInputType(InputType.TYPE_CLASS_NUMBER); exact.setText(Integer.toString(minutes[0]));
            exactDialog = new AlertDialog.Builder(activity).setTitle("Minutes (0–1440)").setView(exact)
                    .setNegativeButton("Cancel", null).setPositiveButton("Set", null).create();
            exactDialog.setOnShowListener(ignored -> exactDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                if (!allowed()) { exactDialog.dismiss(); return; }
                try {
                    int value = Integer.parseInt(exact.getText().toString());
                    if (value < 0 || value > 1440) throw new IllegalArgumentException();
                    minutes[0] = value; refresh.run(); exactDialog.dismiss();
                } catch (IllegalArgumentException error) { exact.setError("Use 0–1440 minutes."); }
            })); exactDialog.show();
        });
        stepper.addView(readout, new LinearLayout.LayoutParams(0, Ui.dp(activity, 72), 1));
        stepper.addView(button("+", () -> { if (limit.isChecked()) { minutes[0] = Math.min(1440, minutes[0] + 15); refresh.run(); } }),
                new LinearLayout.LayoutParams(Ui.dp(activity, 64), Ui.dp(activity, 64))); body.addView(stepper);
        limit.setOnCheckedChangeListener((view, checked) -> refresh.run()); refresh.run();
        body.addView(Ui.text(activity, "Tap the time for an exact value. Zero minutes means no time today.", 13, false));
        CheckBox all = new CheckBox(activity); all.setText("Apply to all days of the week"); if (!today) body.addView(all);
        LinearLayout actions = new LinearLayout(activity);
        actions.addView(button("Cancel", () -> sheet.dismiss()), new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(button("Done", () -> {
            if (!allowed()) { close(); return; }
            int value = limit.isChecked() ? minutes[0] : -1;
            if (today) { selectedType = type[0]; mutate(() -> timers.today(type[0], value)); }
            else if (draft != null) { if (all.isChecked()) Arrays.fill(draft.days, value); else draft.days[day] = value; redraw.run(); }
            sheet.dismiss();
        }), new LinearLayout.LayoutParams(0, -2, 1)); body.addView(actions);
        ScrollView scroll = new ScrollView(activity); scroll.addView(body); sheet.setContentView(scroll); sheet.show();
        sheet.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
    }
    private void mutate(Runnable action) {
        if (!allowed()) return;
        try { action.run(); redraw.run(); } catch (RuntimeException error) { Ui.error(activity, error); }
    }
    private Spinner typeSelector(Type initial, java.util.function.Consumer<Type> selected) {
        Spinner spinner = new Spinner(activity); spinner.setContentDescription("Timer type");
        spinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, new String[]{"Watching", "Full app"}));
        spinner.setSelection(initial.ordinal(), false);
        spinner.setMinimumHeight(Ui.dp(activity, 56));
        final int[] previous = {initial.ordinal()};
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position != previous[0] && allowed()) { previous[0] = position; selected.accept(Type.values()[position]); }
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        }); return spinner;
    }
    private android.widget.Button button(String label, Runnable action) {
        com.google.android.material.button.MaterialButton button = (com.google.android.material.button.MaterialButton) Ui.button(activity, label, () -> {
            if (allowed()) action.run();
        });
        if (!(label.startsWith("Save") || label.startsWith("+ Add") || label.equals("Done"))) {
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFEEDCFF));
            button.setTextColor(0xFF2D0051);
        }
        if (label.equals("Cancel") || label.startsWith("Delete")) {
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
            button.setStrokeWidth(Ui.dp(activity, 1));
            button.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFF79747E));
        }
        return button;
    }
    private void header(LinearLayout root, String title, Runnable back) {
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.icon(activity, R.drawable.ic_back, "Back", Ui.INK, back),
                new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 56)));
        row.addView(Ui.text(activity, title, 26, true), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row);
    }
    private LinearLayout surface(LinearLayout root) {
        LinearLayout surface = new LinearLayout(activity); surface.setOrientation(LinearLayout.VERTICAL);
        surface.setBackground(Ui.background(Color.WHITE, 28, activity));
        surface.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 14), Ui.dp(activity, 18), Ui.dp(activity, 14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 4));
        root.addView(surface, params); return surface;
    }
    static String label(Type type) { return type == Type.WATCHING ? "Watching" : "Full app"; }
    static String duration(int minutes) {
        if (minutes < 0) return "No limit";
        if (minutes < 60) return minutes + " min";
        return minutes / 60 + " h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " min");
    }
    private static String dayName(int day) { return DayOfWeek.of(day + 1).getDisplayName(TextStyle.FULL, Locale.getDefault()); }
    private static String summary(TimerSchedule schedule) {
        StringBuilder text = new StringBuilder();
        for (int day = 0; day < 7; day++) if (schedule.minutes(day) >= 0) {
            if (text.length() > 0) text.append(" · ");
            text.append(DayOfWeek.of(day + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault())).append(' ').append(duration(schedule.minutes(day)));
        }
        return text.length() == 0 ? "No daily limits" : text.toString();
    }
}
