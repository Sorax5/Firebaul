package fr.phylisium.firebaul.keyword;

import com.google.gson.Gson;
import fr.phylisium.firebaul.Firebaul;
import fr.phylisium.firebaul.keyword.impls.*;
import org.bukkit.Bukkit;
import org.vosk.Model;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SpeechRecognitionService {
    private static final long DEFAULT_PURGE_TTL_MS = 1000 * 60 * 60;
    private static final long MIN_PURGE_PERIOD_TICKS = 10 * 60 * 20L;

    private final Firebaul plugin = Firebaul.getInstance();
    private Model model;
    private final Map<UUID, PlayerRecognition> recognitions = new ConcurrentHashMap<>();
    private final KeywordRegistry keywordRegistry = new KeywordRegistry();
    private final Gson gson = new Gson();

    private final DebounceService debounceService = new DebounceService();
    private final ActionBarFormatter actionBarFormatter = new ActionBarFormatter();

    private Duration pollInterval = Duration.ofMillis(100);
    private int stabilityThreshold = 2;
    private Duration debounceDuration = Duration.ofMillis(1000);
    private Duration purgeTtlDuration = Duration.ofMillis(DEFAULT_PURGE_TTL_MS);

    private int purgeTaskId = -1;
    private volatile boolean keywordsRegistered;

    public SpeechRecognitionService() {
        loadConfiguration();
    }

    private void loadConfiguration() {
        if (plugin == null) {
            return;
        }
        try {
            var config = plugin.getConfig();
            int pollMs = Math.max(10, config.getInt("speech.poll_ms", (int) pollInterval.toMillis()));
            pollInterval = Duration.ofMillis(pollMs);
            stabilityThreshold = config.getInt("speech.stability_threshold", stabilityThreshold);
            long debounceMs = config.getLong("speech.debounce_ms", debounceDuration.toMillis());
            debounceDuration = Duration.ofMillis(Math.max(0, debounceMs));
            long purgeTtlMs = config.getLong("speech.purge_ttl_ms", purgeTtlDuration.toMillis());
            purgeTtlDuration = Duration.ofMillis(Math.max(0, purgeTtlMs));
            debounceService.setDebug(config.getBoolean("speech.debug", false));
        } catch (Exception e) {
            plugin.getLogger().warning("Unable to load speech config: " + e.getMessage());
        }
    }

    public KeywordRegistry getKeywordRegistry() {
        return keywordRegistry;
    }

    public void loadModel(File modelDir) throws IOException {
        ensureKeywordsRegistered();
        if (plugin == null) {
            return;
        }
        if (modelDir == null || !modelDir.isDirectory()) {
            plugin.getLogger().warning("Vosk model not found in " + (modelDir == null ? "null" : modelDir.getAbsolutePath()) + " - speech recognition disabled");
            this.model = null;
            return;
        }

        try {
            this.model = new Model(modelDir.getAbsolutePath());
            plugin.getLogger().info("Loaded Vosk model from " + modelDir.getAbsolutePath());
            schedulePurgeTask();
        } catch (LinkageError e) {
            plugin.getLogger().severe("Failed to load Vosk native library: " + e.getMessage());
            plugin.getLogger().severe("Speech recognition disabled; see plugin docs for native library setup.");
            this.model = null;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load Vosk model: " + e.getMessage());
            this.model = null;
        }
    }

    private void ensureKeywordsRegistered() {
        if (keywordsRegistered) {
            return;
        }
        keywordRegistry.register(new FireBallKeywordAction());
        keywordRegistry.register(new ChoucrouteKeywordAction());
        keywordRegistry.register(new ConfettiKeywordAction());
        keywordRegistry.register(new HeartStormKeywordAction());
        keywordRegistry.register(new RandomParticleShowKeywordAction());
        keywordsRegistered = true;
    }

    private void schedulePurgeTask() {
        if (plugin == null || model == null || purgeTaskId != -1) {
            return;
        }
        long purgeTicks = Math.max(1, purgeTtlDuration.toMillis());
        long period = Math.max(MIN_PURGE_PERIOD_TICKS, purgeTicks / 50L);
        purgeTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::runPurge, 20L * 60L, period).getTaskId();
    }

    private void runPurge() {
        if (plugin == null) {
            return;
        }
        try {
            int removed = debounceService.purgeExpired(purgeTtlDuration.toMillis());
            if (removed > 0) {
                plugin.getLogger().info("DebounceService purged " + removed + " players");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Unable to purge debounce entries: " + e.getMessage());
        }
    }

    public void acceptOpus(UUID playerId, byte[] opusData) {
        if (model == null || opusData == null) {
            return;
        }
        getOrCreateRecognition(playerId).enqueue(opusData);
    }

    @SuppressWarnings("unused")
    public void stopRecognition(UUID playerId) {
        PlayerRecognition pr = recognitions.remove(playerId);
        if (pr != null) {
            pr.shutdown();
        }
    }

    private PlayerRecognition getOrCreateRecognition(UUID playerId) {
        return recognitions.computeIfAbsent(playerId, id -> new PlayerRecognition(
                id,
                plugin,
                model,
                keywordRegistry,
                gson,
                debounceService,
                actionBarFormatter,
                pollInterval,
                stabilityThreshold,
                debounceDuration
        ));
    }
}
