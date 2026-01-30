package fr.phylisium.firebaul.ai;

import fr.phylisium.firebaul.Firebaul;
import io.github.ollama4j.tools.annotations.ToolProperty;
import io.github.ollama4j.tools.annotations.ToolSpec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

@SuppressWarnings("unused")
public class MinecraftTools {
    public MinecraftTools() {
    }

    @ToolSpec(desc = "Execute une commande sur le serveur Minecraft et retourne le résultat sous forme de chaîne de caractères.")
    public String executeCommand(
            @ToolProperty(name = "command", desc = "La commande à exécuter", required = true) String command) {
        var plugin = Firebaul.getInstance();
        if (plugin == null) {
            return "Erreur: Firebaul instance non initialisée";
        }

        var logger = plugin.getLogger();

        String sanitizedCommand = command;
        try {
            if (sanitizedCommand != null && sanitizedCommand.toLowerCase().startsWith("give ")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^give\\s+(\\S+)\\s+(.+)$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sanitizedCommand);
                if (m.find()) {
                    String target = m.group(1);
                    String rest = m.group(2);

                    // Extraire token item (avant '{' si présent, sinon premier token)
                    int braceIndex = rest.indexOf('{');
                    String itemToken;
                    String remainder = "";
                    if (braceIndex >= 0) {
                        itemToken = rest.substring(0, braceIndex);
                        remainder = rest.substring(braceIndex); // inclut '{'
                    } else {
                        int spaceIdx = rest.indexOf(' ');
                        if (spaceIdx >= 0) {
                            itemToken = rest.substring(0, spaceIdx);
                            remainder = rest.substring(spaceIdx); // inclut leading space
                        } else {
                            itemToken = rest;
                            remainder = "";
                        }
                    }

                    // Ajouter namespace minecraft: si absent
                    if (!itemToken.contains(":")) {
                        itemToken = "minecraft:" + itemToken;
                    }

                    // Si le NBT est présent avant le count (ex: diamond_sword{...} 1),
                    // détecter la fin du NBT et repositionner le count avant le NBT.
                    String trailing = "";
                    String countToken = null;
                    if (!remainder.isEmpty() && remainder.charAt(0) == '{') {
                        int depth = 0;
                        int endIdx = -1;
                        for (int i = 0; i < remainder.length(); i++) {
                            char ch = remainder.charAt(i);
                            if (ch == '{') depth++;
                            else if (ch == '}') {
                                depth--;
                                if (depth == 0) {
                                    endIdx = i;
                                    break;
                                }
                            }
                        }
                        if (endIdx != -1) {
                            String nbt = remainder.substring(0, endIdx + 1);
                            trailing = remainder.substring(endIdx + 1).trim();

                            if (!trailing.isEmpty()) {
                                String[] parts = trailing.split("\\s+", 2);
                                if (parts.length > 0 && parts[0].matches("\\d+")) {
                                    countToken = parts[0];
                                    // keep possible extra after count (rare)
                                    trailing = parts.length > 1 ? parts[1] : "";
                                }
                            }

                            // sanitize ids d'enchantement à l'intérieur du NBT (id:sharpness -> id:"minecraft:sharpness")
                            java.util.regex.Pattern idPattern = java.util.regex.Pattern.compile("(?<![:\"])id:([A-Za-z0-9_]+)", java.util.regex.Pattern.CASE_INSENSITIVE);
                            java.util.regex.Matcher idMatcher = idPattern.matcher(nbt);
                            if (idMatcher.find()) {
                                nbt = idMatcher.replaceAll("id:\"minecraft:$1\"");
                            }

                            if (countToken != null) {
                                // reorder: item count nbt [trailing]
                                StringBuilder newRest = new StringBuilder();
                                newRest.append(itemToken).append(' ').append(countToken).append(' ').append(nbt);
                                if (!trailing.isEmpty()) newRest.append(' ').append(trailing);
                                sanitizedCommand = "give " + target + " " + newRest;
                            } else {
                                // keep as item nbt ... (no count)
                                StringBuilder newRest = new StringBuilder();
                                newRest.append(itemToken).append(nbt);
                                if (!trailing.isEmpty()) newRest.append(' ').append(trailing);
                                sanitizedCommand = "give " + target + " " + newRest;
                            }

                        } else {
                            // malformed NBT, just attempt basic id replacement on remainder
                            java.util.regex.Pattern idPattern = java.util.regex.Pattern.compile("(?<![:\"])id:([A-Za-z0-9_]+)", java.util.regex.Pattern.CASE_INSENSITIVE);
                            java.util.regex.Matcher idMatcher = idPattern.matcher(remainder);
                            if (idMatcher.find()) {
                                remainder = idMatcher.replaceAll("id:\"minecraft:$1\"");
                            }
                            sanitizedCommand = "give " + target + " " + itemToken + remainder;
                        }
                    } else {
                        // pas de NBT en début, juste appliquer remplacement d'id si nécessaire
                        if (!remainder.isEmpty()) {
                            java.util.regex.Pattern idPattern = java.util.regex.Pattern.compile("(?<![:\"])id:([A-Za-z0-9_]+)", java.util.regex.Pattern.CASE_INSENSITIVE);
                            java.util.regex.Matcher idMatcher = idPattern.matcher(remainder);
                            if (idMatcher.find()) {
                                remainder = idMatcher.replaceAll("id:\"minecraft:$1\"");
                            }
                        }
                        sanitizedCommand = "give " + target + " " + itemToken + remainder;
                    }

                    if (!sanitizedCommand.equals(command)) {
                        logger.info("[Tools] executeCommand sanitized: '" + command + "' -> '" + sanitizedCommand + "'");
                    }
                }
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING, "[Tools] failed to sanitize command, executing raw: " + command, t);
            sanitizedCommand = command;
        }

        logger.info("[Tools] executeCommand requested: " + sanitizedCommand);

        final String execCommand = sanitizedCommand;

        var scheduler = Bukkit.getScheduler();
        var future = scheduler.callSyncMethod(plugin, () -> {
            // Exécuter la commande sur le thread principal et renvoyer si elle a réussi
            plugin.getLogger().info("[Tools] executing command on main thread: " + execCommand);
            boolean success = Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), execCommand);
            plugin.getLogger().info("[Tools] dispatchCommand returned: " + success + " for command: " + execCommand);
            return success;
        });

        try {
            Boolean res = future.get(5, TimeUnit.SECONDS);
            logger.info("[Tools] future.get() returned for command '" + execCommand + "': " + res);
            if (res != null) {
                boolean ok = res;
                if (ok) {
                    logger.info("[Tools] Command succeeded: " + execCommand);
                    return "Commande exécutée: " + execCommand;
                } else {
                    logger.warning("[Tools] Command executed but returned false: " + execCommand);
                    return "La commande a été exécutée mais a retourné false (échec ou commande inconnue): " + execCommand;
                }
            }
            logger.warning("[Tools] Command returned null result: " + execCommand);
            return "Commande exécutée (résultat non standard): " + res;
        } catch (TimeoutException te) {
            logger.log(Level.WARNING, "[Tools] executeCommand timeout for command: " + execCommand, te);
            return "Erreur: l'exécution de la commande a expiré (timeout).";
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "[Tools] executeCommand interrupted for command: " + execCommand, ie);
            return "Erreur: l'exécution de la commande a été interrompue.";
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable cause = ee.getCause();
            logger.log(Level.WARNING, "[Tools] executeCommand execution exception for command: " + execCommand, ee);
            return "Erreur lors de l'exécution de la commande (cause): " + (cause == null ? ee.getMessage() : cause.toString());
        } catch (Exception e) {
            logger.log(Level.WARNING, "[Tools] executeCommand unexpected exception for command: " + execCommand, e);
            return "Erreur lors de l'exécution de la commande: " + e.getMessage();
        }
    }

    @ToolSpec(desc = "Retourne la liste des joueurs en ligne sur le serveur Minecraft.")
    public List<String> getOnlinePlayers() {
        var plugin = Firebaul.getInstance();
        var logger = plugin == null ? null : plugin.getLogger();
        if (logger != null) logger.info("[Tools] getOnlinePlayers called");

        List<String> playerNames = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            playerNames.add(player.getName());
        }

        if (logger != null) logger.info("[Tools] getOnlinePlayers returning " + playerNames.size() + " players");
        return playerNames;
    }

    @ToolSpec(desc = "Retourne l'UUID d'un joueur donné son nom. Si le joueur n'est pas trouvé, retourne un message d'erreur.")
    public String getPlayerUUID(
            @ToolProperty(name = "player", desc = "Nom du joueur", required = true) String playerName) {
        var plugin = Firebaul.getInstance();
        var logger = plugin == null ? null : plugin.getLogger();
        if (logger != null) logger.info("[Tools] getPlayerUUID called for: " + playerName);

        Player player = Bukkit.getPlayerExact(playerName);
        if (player != null) {
            String uuid = player.getUniqueId().toString();
            if (logger != null) logger.info("[Tools] getPlayerUUID found: " + uuid);
            return uuid;
        } else {
            if (logger != null) logger.warning("[Tools] getPlayerUUID: player not found " + playerName);
            return "Joueur non trouvé";
        }
    }

    // get player location
    @ToolSpec(desc = "Retourne la position d'un joueur donné son nom. Si le joueur n'est pas trouvé, retourne un message d'erreur.")
    public String getPlayerLocation(
            @ToolProperty(name = "player", desc = "Nom du joueur", required = true) String playerName) {
        var plugin = Firebaul.getInstance();
        var logger = plugin == null ? null : plugin.getLogger();
        if (logger != null) logger.info("[Tools] getPlayerLocation called for: " + playerName);

        Player player = Bukkit.getPlayerExact(playerName);
        if (player != null) {
            String loc = player.getLocation().toString();
            if (logger != null) logger.info("[Tools] getPlayerLocation: " + loc);
            return loc;
        } else {
            if (logger != null) logger.warning("[Tools] getPlayerLocation: player not found " + playerName);
            return "Joueur non trouvé";
        }
    }

    @ToolSpec(desc = "Retourne la liste des noms de mondes disponibles sur le serveur Minecraft.")
    public List<String> getWorldNames() {
        var plugin = Firebaul.getInstance();
        var logger = plugin == null ? null : plugin.getLogger();
        if (logger != null) logger.info("[Tools] getWorldNames called");

        List<String> worldNames = new ArrayList<>();
        for (var world : Bukkit.getWorlds()) {
            worldNames.add(world.getName());
        }

        if (logger != null) logger.info("[Tools] getWorldNames returning " + worldNames.size() + " worlds");
        return worldNames;
    }

    // get player location
    @ToolSpec(desc = "Récupère les coordonnées x,y,z et le monde d'un joueur donné son nom. Si le joueur n'est pas trouvé, retourne un message d'erreur.")
    public String getPlayerCoordinates(
            @ToolProperty(name = "player", desc = "Nom du joueur", required = true) String playerName) {
        var plugin = Firebaul.getInstance();
        var logger = plugin == null ? null : plugin.getLogger();
        if (logger != null) logger.info("[Tools] getPlayerCoordinates called for: " + playerName);

        Player player = Bukkit.getPlayerExact(playerName);
        if (player != null) {
            var loc = player.getLocation();
            String coords = String.format("x=%.2f, y=%.2f, z=%.2f, world=%s",
                    loc.getX(), loc.getY(), loc.getZ(), loc.getWorld().getName());
            if (logger != null) logger.info("[Tools] getPlayerCoordinates: " + coords);
            return coords;
        } else {
            if (logger != null) logger.warning("[Tools] getPlayerCoordinates: player not found " + playerName);
            return "Joueur non trouvé";
        }
    }

    @ToolSpec(desc = "Téléporte un joueur à une position donnée. La position doit être au format x,y,z,world. Si le joueur n'est pas trouvé, retourne un message d'erreur.")
    public String teleportPlayer(
            @ToolProperty(name = "player", desc = "Nom du joueur", required = true) String playerName,
            @ToolProperty(name = "location", desc = "Position au format x,y,z,world", required = true) String locationStr) {
        var plugin = Firebaul.getInstance();
        var logger = plugin == null ? null : plugin.getLogger();
        if (logger != null) logger.info("[Tools] teleportPlayer called for: " + playerName + " -> " + locationStr);

        Player player = Bukkit.getPlayerExact(playerName);
        if (player != null) {
            String[] parts = locationStr.split(",");
            if (parts.length != 4) {
                if (logger != null) logger.warning("[Tools] teleportPlayer: invalid location format: " + locationStr);
                return "Format de position invalide. Utilisez x,y,z,world.";
            }
            try {
                double x = Double.parseDouble(parts[0]);
                double y = Double.parseDouble(parts[1]);
                double z = Double.parseDouble(parts[2]);
                String worldName = parts[3];
                var world = Bukkit.getWorld(worldName);
                if (world == null) {
                    if (logger != null) logger.warning("[Tools] teleportPlayer: world not found: " + worldName);
                    return "Monde non trouvé: " + worldName;
                }
                var location = new org.bukkit.Location(world, x, y, z);
                BukkitScheduler scheduler = Bukkit.getScheduler();
                // use scheduler to run teleport on main thread
                scheduler.runTask(Firebaul.getInstance(), () -> {
                    player.teleport(location);
                    if (logger != null) logger.info("[Tools] teleportPlayer: teleported " + playerName + " to " + locationStr);
                });

                return "Joueur " + playerName + " téléporté à " + locationStr;
            } catch (NumberFormatException e) {
                if (logger != null) logger.warning("[Tools] teleportPlayer: invalid coordinates: " + locationStr);
                return "Coordonnées invalides.";
            }
        } else {
            if (logger != null) logger.warning("[Tools] teleportPlayer: player not found " + playerName);
            return "Joueur non trouvé";
        }
    }


    // tool pour obtenir toutes les informations d'un joueur
    @ToolSpec(desc = "Retourne les informations d'un joueur donné son nom. " +
            "Si le joueur n'est pas trouvé, retourne un message d'erreur. " +
            "Utilise cet outils si tu ne trouve pas l'information avec les autres outils.")
    public String getPlayerInfo(
            @ToolProperty(name = "player", desc = "Nom du joueur", required = true) String playerName) {
        var plugin = Firebaul.getInstance();
        var logger = plugin == null ? null : plugin.getLogger();
        if (logger != null) logger.info("[Tools] getPlayerInfo called for: " + playerName);

        Player player = Bukkit.getPlayerExact(playerName);
        if (player != null) {
            StringBuilder info = new StringBuilder();
            info.append("Nom: ").append(player.getName()).append("\n");
            info.append("UUID: ").append(player.getUniqueId().toString()).append("\n");
            info.append("Santé: ").append(player.getHealth()).append("\n");
            info.append("Nourriture: ").append(player.getFoodLevel()).append("\n");
            info.append("Position: ").append(player.getLocation().toString()).append("\n");
            if (logger != null) logger.info("[Tools] getPlayerInfo: returning info for " + playerName);
            return info.toString();
        } else {
            if (logger != null) logger.warning("[Tools] getPlayerInfo: player not found " + playerName);
            return "Joueur non trouvé";
        }
    }

    @ToolSpec(desc = "Retourne les types de blocks dans le chunk où se trouve un joueur donné son nom. Si le joueur n'est pas trouvé, retourne un message d'erreur.")
    public List<String> getPlayerChunkBlocks(
            @ToolProperty(name = "player", desc = "Nom du joueur", required = true) String playerName) {
        var plugin = Firebaul.getInstance();
        var logger = plugin == null ? null : plugin.getLogger();
        if (logger != null) logger.info("[Tools] getPlayerChunkBlocks called for: " + playerName);

        Player player = Bukkit.getPlayerExact(playerName);
        List<String> blockTypes = new ArrayList<>();
        if (player != null) {
            var chunk = player.getLocation().getChunk();
            // warn: this can be expensive
            if (logger != null) logger.info("[Tools] getPlayerChunkBlocks: scanning chunk at " + player.getLocation());
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 256; y++) {
                    for (int z = 0; z < 16; z++) {
                        var block = chunk.getBlock(x, y, z);
                        blockTypes.add(block.getType().toString());
                    }
                }
            }
            if (logger != null) logger.info("[Tools] getPlayerChunkBlocks: found " + blockTypes.size() + " blocks");
            return blockTypes;
        } else {
            if (logger != null) logger.warning("[Tools] getPlayerChunkBlocks: player not found " + playerName);
            blockTypes.add("Joueur non trouvé");
            return blockTypes;
        }
    }
}
