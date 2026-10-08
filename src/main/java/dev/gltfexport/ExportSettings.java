package dev.gltfexport;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Export options. Persisted to {@code config/gltfexport.json}; editable from the options screen (Mod Menu or
 * {@code /gltf config}) and with {@code /gltf set <option> <value>}.
 */
public final class ExportSettings {
	// --- what to export
	public boolean entities = true;
	public boolean blockEntities = true;
	public boolean fluids = true;
	/** Include yourself if you stand in the selection. */
	public boolean includePlayer = false;
	/**
	 * Keep block faces on the border of the selection (closed model). When off, border faces are culled against the
	 * real blocks outside the selection, exactly as the game draws them.
	 */
	public boolean closeEdges = true;

	// --- how to export
	/** Export entity models as skinned meshes with a skeleton. When off they are plain static meshes. */
	public boolean rigEntities = true;
	/** Biome/dye tints as vertex colours (COLOR_0). When off, geometry is untinted. */
	public boolean tints = true;
	/**
	 * Also merge vertices where faces meet at an angle (block corners), averaging their normals. For use with flat
	 * shading. glTF keeps one UV per vertex, so vertices with different UVs still stay separate.
	 */
	public boolean weldEdges = false;
	/** Merge coplanar neighbouring block faces with the same texture into large rectangles (greedy meshing). */
	public boolean mergeFaces = true;
	/** Write vertex normals. Without them viewers use flat shading, and the file is ~30% smaller. */
	public boolean normals = true;
	/** Mark materials KHR_materials_unlit so viewers show the flat in-game look. */
	public boolean unlit = false;
	/** Selections wider than this (in X or Z) are split into several .glb files. 0 = never split. */
	public int partSize = 512;
	/** Block geometry inside a file is split into nodes of this many blocks square. */
	public int tileSize = 64;

	// --- misc
	/** Draw the selection outline. */
	public boolean showSelection = true;
	/** Log every render submission seen while capturing entities (for troubleshooting). */
	public boolean verboseLog = false;

	/** Option name -> description, in display order. */
	public static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();

	static {
		DESCRIPTIONS.put("entities", "Export mobs, players, item frames, boats, dropped items...");
		DESCRIPTIONS.put("blockEntities", "Export chests, beds, signs, banners, skulls...");
		DESCRIPTIONS.put("fluids", "Export water and lava surfaces");
		DESCRIPTIONS.put("includePlayer", "Include yourself if you are inside the selection");
		DESCRIPTIONS.put("closeEdges", "Keep block faces on the selection border (closed model)");
		DESCRIPTIONS.put("rigEntities", "Export entities with a skeleton (skinned mesh)");
		DESCRIPTIONS.put("tints", "Grass/foliage/water/dye colours as vertex colours");
		DESCRIPTIONS.put("weldEdges", "Also join vertices where faces meet at an angle (for flat shading)");
		DESCRIPTIONS.put("mergeFaces", "Merge flat runs of the same block face into big quads (much smaller files)");
		DESCRIPTIONS.put("normals", "Write normals (off = flat shading, smaller file)");
		DESCRIPTIONS.put("unlit", "Unlit materials (flat in-game look in viewers)");
		DESCRIPTIONS.put("partSize", "Split into several files every N blocks (0 = one file)");
		DESCRIPTIONS.put("tileSize", "Block mesh node size in blocks");
		DESCRIPTIONS.put("showSelection", "Outline the selection with particles");
		DESCRIPTIONS.put("verboseLog", "Log render calls while capturing (troubleshooting)");
	}

	public static final String[] NAMES = {"entities", "blockEntities", "fluids", "includePlayer", "closeEdges",
			"rigEntities", "tints", "weldEdges", "mergeFaces", "normals", "unlit", "showSelection", "verboseLog"};
	public static final String[] INT_NAMES = {"partSize", "tileSize"};

	public boolean set(String name, boolean value) {
		Consumer<Boolean> setter = boolSetter(name);
		if (setter == null) return false;
		setter.accept(value);
		return true;
	}

	public boolean get(String name) {
		Supplier<Boolean> g = boolGetter(name);
		return g != null && g.get();
	}

	public boolean setInt(String name, int value) {
		switch (name) {
			case "partSize" -> partSize = Math.max(0, value);
			case "tileSize" -> tileSize = Math.max(1, value);
			default -> {
				return false;
			}
		}
		return true;
	}

	public int getInt(String name) {
		return switch (name) {
			case "partSize" -> partSize;
			case "tileSize" -> tileSize;
			default -> 0;
		};
	}

	private Consumer<Boolean> boolSetter(String name) {
		return switch (name) {
			case "entities" -> v -> entities = v;
			case "blockEntities" -> v -> blockEntities = v;
			case "fluids" -> v -> fluids = v;
			case "includePlayer" -> v -> includePlayer = v;
			case "closeEdges" -> v -> closeEdges = v;
			case "rigEntities" -> v -> rigEntities = v;
			case "tints" -> v -> tints = v;
			case "weldEdges" -> v -> weldEdges = v;
			case "mergeFaces" -> v -> mergeFaces = v;
			case "normals" -> v -> normals = v;
			case "unlit" -> v -> unlit = v;
			case "showSelection" -> v -> showSelection = v;
			case "verboseLog" -> v -> verboseLog = v;
			default -> null;
		};
	}

	private Supplier<Boolean> boolGetter(String name) {
		return switch (name) {
			case "entities" -> () -> entities;
			case "blockEntities" -> () -> blockEntities;
			case "fluids" -> () -> fluids;
			case "includePlayer" -> () -> includePlayer;
			case "closeEdges" -> () -> closeEdges;
			case "rigEntities" -> () -> rigEntities;
			case "tints" -> () -> tints;
			case "weldEdges" -> () -> weldEdges;
			case "mergeFaces" -> () -> mergeFaces;
			case "normals" -> () -> normals;
			case "unlit" -> () -> unlit;
			case "showSelection" -> () -> showSelection;
			case "verboseLog" -> () -> verboseLog;
			default -> null;
		};
	}

	public String describe() {
		StringBuilder sb = new StringBuilder();
		for (String n : NAMES) sb.append(n).append('=').append(get(n)).append(", ");
		for (String n : INT_NAMES) sb.append(n).append('=').append(getInt(n)).append(", ");
		return sb.substring(0, sb.length() - 2);
	}

	public ExportSettings copy() {
		ExportSettings c = new ExportSettings();
		for (String n : NAMES) c.set(n, get(n));
		for (String n : INT_NAMES) c.setInt(n, getInt(n));
		return c;
	}

	// ------------------------------------------------------------------------------------------ persistence

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("gltfexport.json");
	}

	public void load() {
		Path f = file();
		if (!Files.exists(f)) {
			save();
			return;
		}
		try (Reader r = Files.newBufferedReader(f)) {
			JsonObject o = GSON.fromJson(r, JsonObject.class);
			if (o == null) return;
			for (String n : NAMES) {
				JsonElement e = o.get(n);
				if (e != null && e.isJsonPrimitive()) set(n, e.getAsBoolean());
			}
			for (String n : INT_NAMES) {
				JsonElement e = o.get(n);
				if (e != null && e.isJsonPrimitive()) setInt(n, e.getAsInt());
			}
		} catch (IOException | RuntimeException e) {
			GltfExportClient.LOGGER.warn("Could not read {}: {}", f, e.toString());
		}
	}

	public void save() {
		JsonObject o = new JsonObject();
		for (String n : NAMES) o.addProperty(n, get(n));
		for (String n : INT_NAMES) o.addProperty(n, getInt(n));
		try {
			Files.createDirectories(file().getParent());
			try (Writer w = Files.newBufferedWriter(file())) {
				GSON.toJson(o, w);
			}
		} catch (IOException e) {
			GltfExportClient.LOGGER.warn("Could not save {}: {}", file(), e.toString());
		}
	}
}
