package fr.phylisium.firebaul.keyword;

import fr.phylisium.firebaul.Firebaul;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service simple pour gérer les timestamps de dernier trigger par joueur/keyword.
 * Thread-safe via ConcurrentHashMap.
 * Ajoute TTL/purge pour libérer la mémoire et un mode debug.
 */
public class DebounceService {
    private final Map<UUID, Map<String, Long>> lastTrigger = new ConcurrentHashMap<>();
    private volatile boolean debug = false;

    /**
     * Retourne vrai si le keyword peut être déclenché pour ce joueur (selon debounceMs).
     */
    public boolean canTrigger(UUID playerId, String key, long debounceMs) {
        long now = System.currentTimeMillis();
        Map<String, Long> playerMap = lastTrigger.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        long last = playerMap.getOrDefault(key, 0L);
        boolean ok = (now - last) >= debounceMs;
        if (debug) Firebaul.getInstance().getLogger().info("[DebounceService] canTrigger=" + ok + " player=" + playerId + " key=" + key + " last=" + last);
        return ok;
    }

    /**
     * Enregistre le trigger (timestamp currentTimeMillis) pour le joueur+keyword.
     * Doit être appelé depuis le thread principal pour correspondre au comportement précédent.
     */
    public void recordTrigger(UUID playerId, String key) {
        long now = System.currentTimeMillis();
        Map<String, Long> playerMap = lastTrigger.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        playerMap.put(key, now);
        if (debug) Firebaul.getInstance().getLogger().info("[DebounceService] recordTrigger player=" + playerId + " key=" + key + " @ " + now);
    }

    /**
     * Supprime toutes les entrées plus anciennes que ttlMs (millisecondes).
     * Retourne le nombre d'entrées supprimées (approximatif).
     */
    public int purgeExpired(long ttlMs) {
        long now = System.currentTimeMillis();
        int removed = 0;
        Iterator<Map.Entry<UUID, Map<String, Long>>> it = lastTrigger.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Map<String, Long>> e = it.next();
            Map<String, Long> pm = e.getValue();
            pm.entrySet().removeIf(entry -> (now - entry.getValue()) > ttlMs);
            if (pm.isEmpty()) { it.remove(); removed++; }
        }
        if (debug) Firebaul.getInstance().getLogger().info("[DebounceService] purgeExpired removedPlayers=" + removed + " ttlMs=" + ttlMs);
        return removed;
    }

    // exposer la map si nécessaire (lecture seule) — utile pour diagnostics
    public Map<UUID, Map<String, Long>> getSnapshot() {
        return lastTrigger;
    }

    public void setDebug(boolean debug) { this.debug = debug; }
    public boolean isDebug() { return debug; }
}
