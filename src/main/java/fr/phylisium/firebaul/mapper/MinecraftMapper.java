package fr.phylisium.firebaul.mapper;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

public class MinercraftMapper {
    public static String mapLocationToString(Location location) {
        return location.getX() + "," +
               location.getY() + "," +
               location.getZ() + "," +
               location.getWorld().getName();
    }

    public static Location mapStringToLocation(String location) {
        String[] parts = location.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid location format. Expected format: x,y,z,world");
        }
        double x = Double.parseDouble(parts[0]);
        double y = Double.parseDouble(parts[1]);
        double z = Double.parseDouble(parts[2]);
        String worldName = parts[3];
        var world = org.bukkit.Bukkit.getWorld(worldName);
        if (world == null) {
            throw new IllegalArgumentException("World not found: " + worldName);
        }
        return new Location(world, x, y, z);
    }

    public static String mapEntitiesToString(Iterable<Entity> entities) {
        StringBuilder sb = new StringBuilder();
        for (var entity : entities) {
            sb.append(entity.getUniqueId().toString()).append(",");
        }
        if (!sb.isEmpty()) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }
}
