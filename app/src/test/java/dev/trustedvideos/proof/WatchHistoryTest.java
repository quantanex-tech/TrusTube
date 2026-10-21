// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof;

import android.content.Context;
import android.content.SharedPreferences;
import dev.trustedvideos.proof.source.SourceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 23)
@LooperMode(LooperMode.Mode.PAUSED)
public class WatchHistoryTest {
    private static final String CHANNEL = "UC513PdAP2-jWkJunTh5kXRw";
    private final List<ProofState> states = new ArrayList<>();

    private SharedPreferences preferences() {
        return RuntimeEnvironment.getApplication().getSharedPreferences("proof", Context.MODE_PRIVATE);
    }
    private static String id(int number) { return String.format(Locale.ROOT, "%011d", number); }
    private ProofState restore() {
        ProofState state = new ProofState(RuntimeEnvironment.getApplication());
        states.add(state); return state;
    }
    private ProofState fixture(int count) throws Exception {
        JSONArray catalog = new JSONArray();
        for (int i = 0; i < count; i++) {
            catalog.put(new JSONObject().put("id", id(i)).put("channel", CHANNEL)
                    .put("channelName", "Test channel").put("title", "Video " + i));
        }
        preferences().edit().clear().putStringSet("channels", Set.of(CHANNEL))
                .putString("catalog", catalog.toString()).commit();
        return restore();
    }
    private static List<String> ids(ProofState state) {
        return state.historyEntries().stream().map(entry -> entry.id).collect(Collectors.toList());
    }
    @After public void shutDownExecutors() {
        for (ProofState state : states) {
            state.worker.shutdownNow(); state.credentials.shutdownNow();
        }
    }

    @Test public void newestDistinctVideosPersistWithReplayMovingToFrontAndOldestEvicted() throws Exception {
        ProofState state = fixture(ProofState.WATCH_HISTORY_LIMIT + 5);
        for (int i = 0; i < ProofState.WATCH_HISTORY_LIMIT + 5; i++) state.recordWatched(id(i));
        assertEquals(50, state.historyEntries().size());
        assertEquals(id(54), ids(state).get(0));
        assertEquals(id(5), ids(state).get(49));

        state.recordWatched(id(10));
        assertEquals(50, state.historyEntries().size());
        assertEquals(id(10), ids(state).get(0));
        assertEquals(id(5), ids(state).get(49));
        assertEquals(1, ids(state).stream().filter(id(10)::equals).count());

        state.recordWatched(id(0));
        assertEquals(50, state.historyEntries().size());
        assertEquals(List.of(id(0), id(10), id(54)), ids(state).subList(0, 3));
        assertEquals(id(6), ids(state).get(49));
        assertFalse(ids(state).contains(id(5)));
        assertEquals(ids(state), ids(restore()));

        JSONArray saved = new JSONArray(preferences().getString("watchHistory", "[]"));
        assertEquals(50, saved.length());
        for (int i = 0; i < saved.length(); i++) assertEquals(ids(state).get(i), saved.getString(i));
    }

    @Test public void recordingIgnoresUnknownBlockedAndRemovedVideos() throws Exception {
        ProofState state = fixture(3);
        state.blockVideo(id(0));
        state.recordWatched(id(0));
        state.recordWatched(id(999));
        state.recordWatched(null);
        state.recordWatched("invalid");
        state.removeChannel(CHANNEL);
        state.recordWatched(id(1));
        assertTrue(state.historyEntries().isEmpty());
        assertFalse(state.policy.allowsChannel(CHANNEL));
        assertTrue(ids(restore()).isEmpty());
    }

    @Test public void currentPolicyFiltersHistoryWithoutChangingApprovalsOrStoredOrder() throws Exception {
        ProofState state = fixture(3);
        for (int i = 0; i < 3; i++) state.recordWatched(id(i));
        state.blockVideo(id(1));
        assertEquals(List.of(id(2), id(0)), ids(state));
        state.blockChannel(CHANNEL);
        assertTrue(ids(state).isEmpty());
        assertFalse(state.policy.allowsChannel(CHANNEL));
        state.restoreChannel(CHANNEL);
        assertEquals(List.of(id(2), id(0)), ids(state));

        state.removeChannel(CHANNEL);
        ProofState restored = restore();
        assertTrue(ids(restored).isEmpty());
        assertFalse(restored.policy.allowsChannel(CHANNEL));
        restored.add(new SourceRepository.ChannelBatch(CHANNEL, "Test channel", "", List.of(), false));
        assertEquals(List.of(id(2), id(0)), ids(restored));
        restored.restoreVideo(id(1));
        assertEquals(List.of(id(2), id(1), id(0)), ids(restored));
    }

    @Test public void clearingHistoryPersistsAndPreservesCatalogAndPermissions() throws Exception {
        ProofState state = fixture(2);
        state.recordWatched(id(0)); state.recordWatched(id(1));
        state.clearHistory();
        assertTrue(ids(state).isEmpty());
        ProofState restored = restore();
        assertTrue(ids(restored).isEmpty());
        assertEquals("[]", preferences().getString("watchHistory", ""));
        assertEquals(2, restored.allowedEntries().size());
        assertTrue(restored.policy.allowsChannel(CHANNEL));
    }

    @Test public void malformedHistoryDoesNotInvalidateCatalog() throws Exception {
        fixture(3);
        for (String malformed : List.of("{broken", "{}", "null")) {
            preferences().edit().putString("watchHistory", malformed).commit();
            ProofState restored = restore();
            assertTrue(ids(restored).isEmpty());
            assertEquals(3, restored.allowedEntries().size());
        }
        preferences().edit().putInt("watchHistory", 42).commit();
        ProofState restored = restore();
        assertTrue(ids(restored).isEmpty());
        assertEquals(3, restored.allowedEntries().size());
    }

    @Test public void restorationSkipsMalformedItemsDeduplicatesAndCapsHistory() throws Exception {
        fixture(55);
        JSONArray history = new JSONArray().put(JSONObject.NULL).put(new JSONObject())
                .put(12345678901L).put("invalid").put(id(0)).put(id(0));
        for (int i = 1; i < 55; i++) history.put(id(i));
        preferences().edit().putString("watchHistory", history.toString()).commit();
        ProofState restored = restore();
        assertEquals(50, restored.historyEntries().size());
        for (int i = 0; i < 50; i++) assertEquals(id(i), ids(restored).get(i));
        assertEquals(55, restored.allowedEntries().size());
    }
}
