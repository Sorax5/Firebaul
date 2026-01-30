package fr.phylisium.firebaul.keyword;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.phylisium.firebaul.Firebaul;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.vosk.Model;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class PlayerRecognition implements Runnable {
    private static final String PARTIAL_FIELD = "partial";
    private static final String TEXT_FIELD = "text";

    private final UUID playerId;
    private final Firebaul plugin;
    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private final KeywordRegistry keywordRegistry;
    private final Gson gson;
    private final DebounceService debounceService;
    private final ActionBarFormatter formatter;

    private final Duration pollInterval;
    private final int stabilityThreshold;
    private final Duration debounceDuration;

    private AudioRecognizer audioRecognizer;
    private final Map<String, Integer> partialCounts = new ConcurrentHashMap<>();

    public PlayerRecognition(UUID playerId,
                             Firebaul plugin,
                             Model model,
                             KeywordRegistry keywordRegistry,
                             Gson gson,
                             DebounceService debounceService,
                             ActionBarFormatter formatter,
                             Duration pollInterval,
                             int stabilityThreshold,
                             Duration debounceDuration) {
        this.playerId = playerId;
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.keywordRegistry = keywordRegistry;
        this.gson = gson;
        this.debounceService = debounceService;
        this.formatter = formatter;
        this.pollInterval = pollInterval != null ? pollInterval : Duration.ofMillis(100);
        this.stabilityThreshold = stabilityThreshold;
        this.debounceDuration = debounceDuration != null ? debounceDuration : Duration.ofMillis(1000);

        this.worker = new Thread(this, "speech-recog-" + playerId);
        this.worker.setDaemon(true);

        try {
            this.audioRecognizer = new AudioRecognizer(model);
        } catch (Exception e) {
            this.plugin.getLogger().severe("Failed to init audio recognizer: " + e.getMessage());
            this.running.set(false);
        }

        if (this.running.get()) {
            this.worker.start();
        }
    }

    public void enqueue(byte[] opus) {
        if (!running.get()) {
            return;
        }
        boolean offered = queue.offer(opus);
        if (!offered) {
            plugin.getLogger().warning("Speech queue full for " + playerId + " - dropping frame");
        }
    }

    public void shutdown() {
        running.set(false);
        worker.interrupt();
        try {
            worker.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        drainQueueAndEmit();
        flushRecognizerAndEmit();
        closeResources();
    }

    @Override
    public void run() {
        long pollMillis = Math.max(10, pollInterval.toMillis());
        while (running.get()) {
            try {
                byte[] opus = queue.poll(pollMillis, TimeUnit.MILLISECONDS);
                if (opus == null) {
                    flushRecognizerAndEmit();
                    continue;
                }
                if (opus.length == 0) {
                    continue;
                }
                processOpusFrame(opus);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                plugin.getLogger().severe("Error in speech recognition worker for " + playerId + ": " + e.getMessage());
            }
        }
        flushRecognizerAndEmit();
    }

    private void processOpusFrame(byte[] opus) {
        if (audioRecognizer == null) {
            return;
        }
        try {
            byte[] pcmBytes = audioRecognizer.decodeOpusTo16kBytes(opus);
            if (pcmBytes == null) {
                return;
            }
            boolean accepted = audioRecognizer.acceptWaveForm(pcmBytes, pcmBytes.length);
            String json = accepted ? audioRecognizer.getFinalResult() : audioRecognizer.getPartialResult();
            if (accepted) {
                partialCounts.clear();
                handleFinalResult(json);
                resetRecognizerState();
            } else {
                handlePartialResult(json);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to process opus frame: " + e.getMessage());
        }
    }

    private void resetRecognizerState() {
        if (audioRecognizer == null) {
            return;
        }
        try {
            audioRecognizer.reset();
        } catch (Exception e) {
            plugin.getLogger().warning("Unable to reset recognizer: " + e.getMessage());
        }
    }

    private void drainQueueAndEmit() {
        byte[] opus;
        while ((opus = queue.poll()) != null) {
            if (opus.length == 0) {
                continue;
            }
            try {
                processOpusFrame(opus);
            } catch (Exception ignored) {
            }
        }
    }

    private void flushRecognizerAndEmit() {
        if (audioRecognizer == null) {
            return;
        }
        try {
            String finalJson = audioRecognizer.getFinalResult();
            handleFinalResult(finalJson);
            resetRecognizerState();
        } catch (Exception ignored) {
        }
    }

    private void handlePartialResult(String json) {
        extractJsonText(json, PARTIAL_FIELD).ifPresent(normalized -> {
            List<KeywordRegistry.Match> matches = keywordRegistry.findAllMatches(normalized);
            if (debounceService.isDebug()) {
                plugin.getLogger().info("[Speech][DBG] partial='" + normalized + "' matches=" + matches);
            }
            showActionBar(normalized, matches);

            Map<String, KeywordRegistry.Match> matched = buildKeywordToMatch(matches);
            for (Map.Entry<String, KeywordRegistry.Match> entry : matched.entrySet()) {
                String key = entry.getKey();
                KeywordRegistry.Match triggerMatch = entry.getValue();
                int count = partialCounts.getOrDefault(key, 0) + 1;
                partialCounts.put(key, count);
                if (debounceService.isDebug()) {
                    plugin.getLogger().info("[Speech][DBG] partialCount '" + key + "' -> " + count);
                }
                if (count >= stabilityThreshold) {
                    if (triggerKeyword(triggerMatch, key, "(partial)")) {
                        partialCounts.put(key, 0);
                    }
                }
            }
            partialCounts.keySet().removeIf(key -> !matched.containsKey(key));
        });
    }

    private void handleFinalResult(String json) {
        extractJsonText(json, TEXT_FIELD).ifPresent(normalized -> {
            List<KeywordRegistry.Match> matches = keywordRegistry.findAllMatches(normalized);
            if (debounceService.isDebug()) {
                plugin.getLogger().info("[Speech][DBG] final='" + normalized + "' matches=" + matches);
            }
            showActionBar(normalized, matches);

            Set<String> triggered = new HashSet<>();
            for (KeywordRegistry.Match match : matches) {
                String key = match.action.getKeyword();
                if (triggered.contains(key)) {
                    continue;
                }
                if (triggerKeyword(match, key, "(final)")) {
                    triggered.add(key);
                }
            }
        });
    }

    private Optional<String> extractJsonText(String json, String field) {
        if (json == null || json.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            if (!obj.has(field)) {
                return Optional.empty();
            }
            JsonElement element = obj.get(field);
            if (element == null || element.isJsonNull()) {
                return Optional.empty();
            }
            String text = element.getAsString();
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(text.trim());
        } catch (Exception e) {
            if (debounceService.isDebug()) {
                plugin.getLogger().warning("Unable to parse Vosk JSON: " + e.getMessage());
            }
            return Optional.empty();
        }
    }

    private Map<String, KeywordRegistry.Match> buildKeywordToMatch(List<KeywordRegistry.Match> matches) {
        Map<String, KeywordRegistry.Match> map = new HashMap<>();
        if (matches == null || matches.isEmpty()) {
            return map;
        }
        for (KeywordRegistry.Match match : matches) {
            String key = match.action.getKeyword();
            map.putIfAbsent(key, match);
        }
        return map;
    }

    private void showActionBar(String text, List<KeywordRegistry.Match> matches) {
        Component component = formatter.formatForActionBar(text, matches);
        sendActionBarComponent(component);
    }

    private void sendActionBarComponent(Component comp) {
        if (comp == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendActionBar(comp);
            }
        });
    }

    private boolean triggerKeyword(KeywordRegistry.Match match, String key, String reason) {
        if (match == null) {
            return false;
        }
        if (!debounceService.canTrigger(playerId, key, debounceDuration.toMillis())) {
            return false;
        }
        debounceService.recordTrigger(playerId, key);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                return;
            }
            plugin.getLogger().info("[Speech] trigger '" + key + "' for " + player.getName() + " @ " + System.currentTimeMillis() + " " + reason);
            try {
                match.action.getHandler().accept(player);
            } catch (Exception ex) {
                plugin.getLogger().severe("Error in keyword handler: " + ex.getMessage());
            }
        });
        return true;
    }

    private void closeResources() {
        if (audioRecognizer != null) {
            try {
                audioRecognizer.close();
            } catch (Exception ignored) {
            }
        }
    }
}
