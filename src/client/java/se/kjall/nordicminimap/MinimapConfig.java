package se.kjall.nordicminimap;

import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

final class MinimapConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mats-minimap.properties");
	static final MinimapConfig INSTANCE = new MinimapConfig();
	boolean northLocked = false;
	boolean circular = false;
	int mapSize = 100;
	double zoom = 0.5;
	int resolutionScale = 1;
	boolean worldWaypoints = true;
	boolean showEntities = true;
	boolean displayItems = false;
	boolean deathpoint = false;
	boolean borderEnabled = true;
	String borderStyle = "old";
	String playerMarker = "dot";
	final List<Waypoint> waypoints = new ArrayList<>();

	private MinimapConfig() { load(); }

	void load() {
		if (!Files.exists(FILE)) return;
		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(FILE)) {
			p.load(in);
			northLocked = Boolean.parseBoolean(p.getProperty("northLocked", "false"));
			circular = Boolean.parseBoolean(p.getProperty("circular", "false"));
			mapSize = Math.max(80, Math.min(200, Integer.parseInt(p.getProperty("mapSize", "100"))));
			double loadedZoom = Double.parseDouble(p.getProperty("zoom", "0.5"));
			zoom = loadedZoom < 0.75 ? 0.5 : loadedZoom < 1.5 ? 1.0 : 2.0;
			int loadedResolution = Integer.parseInt(p.getProperty("resolutionScale", "1"));
			resolutionScale = loadedResolution <= 2 ? 1 : loadedResolution <= 4 ? 3 : loadedResolution <= 6 ? 5 : 7;
			worldWaypoints = Boolean.parseBoolean(p.getProperty("worldWaypoints", "true"));
			showEntities = Boolean.parseBoolean(p.getProperty("showEntities", "false"));
			displayItems = Boolean.parseBoolean(p.getProperty("displayItems", "false"));
			deathpoint = Boolean.parseBoolean(p.getProperty("deathpoint", "false"));
			borderEnabled = Boolean.parseBoolean(p.getProperty("borderEnabled", "true"));
			borderStyle = p.getProperty("borderStyle", "old").equals("modern") ? "modern" : "old";
			playerMarker = p.getProperty("playerMarker", "dot").equals("arrow") ? "arrow" : "dot";
			int count = Integer.parseInt(p.getProperty("waypoints", "0"));
			waypoints.clear();
			for (int i = 0; i < count; i++) {
				waypoints.add(new Waypoint(p.getProperty("waypoint." + i + ".name", "Waypoint " + (i + 1)),
						Integer.parseInt(p.getProperty("waypoint." + i + ".x", "0")),
						Integer.parseInt(p.getProperty("waypoint." + i + ".y", "64")),
						Integer.parseInt(p.getProperty("waypoint." + i + ".z", "0")),
						p.getProperty("waypoint." + i + ".dimension", "minecraft:overworld"),
						Integer.parseUnsignedInt(p.getProperty("waypoint." + i + ".color", "FFFFD53D"), 16)));
			}
		} catch (Exception ignored) { }
	}

	void save() {
		Properties p = new Properties();
		p.setProperty("northLocked", Boolean.toString(northLocked));
		p.setProperty("circular", Boolean.toString(circular));
		p.setProperty("mapSize", Integer.toString(mapSize));
		p.setProperty("zoom", Double.toString(zoom));
		p.setProperty("resolutionScale", Integer.toString(resolutionScale));
		p.setProperty("worldWaypoints", Boolean.toString(worldWaypoints));
		p.setProperty("showEntities", Boolean.toString(showEntities));
		p.setProperty("displayItems", Boolean.toString(displayItems));
		p.setProperty("deathpoint", Boolean.toString(deathpoint));
		p.setProperty("borderEnabled", Boolean.toString(borderEnabled));
		p.setProperty("borderStyle", borderStyle);
		p.setProperty("playerMarker", playerMarker);
		p.setProperty("waypoints", Integer.toString(waypoints.size()));
		for (int i = 0; i < waypoints.size(); i++) {
			Waypoint w = waypoints.get(i);
			p.setProperty("waypoint." + i + ".name", w.name());
			p.setProperty("waypoint." + i + ".x", Integer.toString(w.x()));
			p.setProperty("waypoint." + i + ".y", Integer.toString(w.y()));
			p.setProperty("waypoint." + i + ".z", Integer.toString(w.z()));
			p.setProperty("waypoint." + i + ".dimension", w.dimension());
			p.setProperty("waypoint." + i + ".color", String.format("%08X", w.color()));
		}
		try {
			Files.createDirectories(FILE.getParent());
			try (OutputStream out = Files.newOutputStream(FILE)) { p.store(out, "Mat's Minimap settings"); }
		} catch (Exception ignored) { }
	}

	record Waypoint(String name, int x, int y, int z, String dimension, int color) { }
}
