package dev.gltfexport;

/** Toggles changed with {@code /gltf set <option> <true|false>}. */
public final class ExportSettings {
	public boolean entities = true;
	public boolean blockEntities = true;
	public boolean fluids = true;
	/** Include yourself if you stand in the selection. */
	public boolean includePlayer = false;
	/** Mark materials KHR_materials_unlit so viewers show the flat in-game look. */
	public boolean unlit = false;
	/** Draw the selection outline. */
	public boolean showSelection = true;

	public boolean set(String name, boolean value) {
		switch (name) {
			case "entities" -> entities = value;
			case "blockEntities" -> blockEntities = value;
			case "fluids" -> fluids = value;
			case "includePlayer" -> includePlayer = value;
			case "unlit" -> unlit = value;
			case "showSelection" -> showSelection = value;
			default -> {
				return false;
			}
		}
		return true;
	}

	public static final String[] NAMES = {"entities", "blockEntities", "fluids", "includePlayer", "unlit", "showSelection"};

	public String describe() {
		return "entities=" + entities + ", blockEntities=" + blockEntities + ", fluids=" + fluids
				+ ", includePlayer=" + includePlayer + ", unlit=" + unlit + ", showSelection=" + showSelection;
	}
}
