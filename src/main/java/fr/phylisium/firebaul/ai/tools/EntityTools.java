package fr.phylisium.firebaul.ai;

import fr.phylisium.firebaul.Firebaul;
import fr.phylisium.firebaul.mapper.MinecraftMapper;
import io.github.ollama4j.tools.annotations.ToolProperty;
import io.github.ollama4j.tools.annotations.ToolSpec;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

public class EntityTools {
    public EntityTools() {
    }

    private <T> T callSync(Callable<T> task) throws Exception {
        if (Bukkit.isPrimaryThread()) {
            return task.call();
        }

        var plugin = Firebaul.getInstance();
        if (plugin == null) {
            throw new IllegalStateException("Firebaul instance non initialisée");
        }

        try {
            var future = Bukkit.getScheduler().callSyncMethod(plugin, task);
            return future.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new Exception("Interrompu en attendant l'exécution sur le thread principal", ie);
        } catch (ExecutionException ee) {
            throw new Exception("Erreur pendant l'exécution sur le thread principal", ee.getCause());
        }
    }

    @ToolSpec(name = "getEntityIdAroundLocation", desc = "Retourne une liste des UUIDs des entités autour d'une position donnée dans le monde Minecraft. Si une erreur survient, retourne un message d'erreur.")
    public List<String> getEntityIdAroundLocation(@ToolProperty(name = "location", desc = "Position au format x,y,z,world", required = true) String location,
                                                  @ToolProperty(name = "radius", desc = "Rayon de recherche (optionnel, défaut 10)", required = false) Integer radius) {
        try {
            int r = (radius == null) ? 10 : radius;
            return callSync(() -> {
                Location l = MinecraftMapper.mapStringToLocation(location);
                var entities = l.getWorld().getNearbyEntities(l, r, r, r);
                return List.copyOf(entities.stream().map(e -> e.getUniqueId().toString()).toList());
            });
        }
        catch (Exception e) {
            return List.of("Erreur lors de la récupération des entités : " + e.getMessage());
        }
    }

    @ToolSpec(name = "getLocationFromEntityId", desc = "Retourne la position d'une entité donnée son UUID dans le monde Minecraft. Si l'entité n'est pas trouvée, retourne un message d'erreur.")
    public String getLocationFromEntityId(@ToolProperty(name = "entityId", desc = "UUID de l'entité", required = true) String entityId) {
        try {
            return callSync(() -> {
                var uuid = UUID.fromString(entityId);
                for (var world : Bukkit.getWorlds()) {
                    var entity = world.getEntity(uuid);
                    if (entity != null) {
                        var loc = entity.getLocation();
                        return MinecraftMapper.mapLocationToString(loc);
                    }
                }
                return "Entité non trouvée";
            });
        }
        catch (Exception e) {
            return "Erreur lors de la récupération de l'entité : " + e.getMessage();
        }
    }

    @ToolSpec(name = "getDetailedEntityInfo", desc = "Retourne des informations détaillées sur une entité donnée son UUID dans le monde Minecraft. Si l'entité n'est pas trouvée, retourne un message d'erreur.")
    public String getDetailedEntityInfo(@ToolProperty(name = "entityId", desc = "UUID de l'entité", required = true) String entityId) {
        try {
            return callSync(() -> {
                var uuid = UUID.fromString(entityId);
                for (var world : Bukkit.getWorlds()) {
                    var entity = world.getEntity(uuid);
                    if (entity != null) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Type d'entité: ").append(entity.getType().name()).append("\n");
                        sb.append("Position: ").append(MinecraftMapper.mapLocationToString(entity.getLocation())).append("\n");
                        sb.append("Santé: ").append(entity instanceof org.bukkit.entity.LivingEntity le ? le.getHealth() : "N/A").append("\n");
                        sb.append("Nom personnalisé: ").append(entity.customName() != null ? entity.customName() : "N/A").append("\n");
                        sb.append("Est vivant: ").append(entity.isDead() ? "Non" : "Oui").append("\n");
                        return sb.toString();
                    }
                }
                return "Entité non trouvée";
            });
        }
        catch (Exception e) {
            return "Erreur lors de la récupération de l'entité : " + e.getMessage();
        }
    }

    @ToolSpec(name = "getAllEntityIdsInWorld", desc = "Retourne une liste des UUIDs de toutes les entités dans un monde donné. Si le monde n'est pas trouvé, retourne un message d'erreur.")
    public List<String> getAllEntityIdsInWorld(@ToolProperty(name = "worldName", desc = "Nom du monde", required = true) String worldName) {
        try {
            return callSync(() -> {
                var world = Bukkit.getWorld(worldName);
                if (world == null) {
                    return List.of("Monde non trouvé");
                }
                var entities = world.getEntities();
                return List.copyOf(entities.stream().map(e -> e.getUniqueId().toString()).toList());
            });
        } catch (Exception e) {
            return List.of("Erreur lors de la récupération des entités : " + e.getMessage());
        }
    }
}
