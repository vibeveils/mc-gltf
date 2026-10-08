package dev.gltfexport;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Defaults for exports. Opened from Mod Menu or with /gltf config. Saved to config/gltfexport.json on close. */
public final class OptionsScreen extends Screen {
	private static final int[] PART_SIZES = {0, 128, 256, 512, 1024, 2048, 4096};
	private static final int[] TILE_SIZES = {16, 32, 64, 128, 256};
	private static final int BUTTON_W = 200, BUTTON_H = 20, GAP = 4;

	private final Screen parent;
	private final ExportSettings settings = GltfExportClient.SETTINGS;

	public OptionsScreen(Screen parent) {
		super(Component.literal("glTF Exporter Options"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		String[] order = ExportSettings.DESCRIPTIONS.keySet().toArray(new String[0]);
		int columns = this.width >= BUTTON_W * 2 + GAP * 3 ? 2 : 1;
		int rows = (order.length + columns - 1) / columns;
		int totalW = columns * BUTTON_W + (columns - 1) * GAP;
		int left = (this.width - totalW) / 2;
		int top = Math.max(8, (this.height - (rows + 1) * (BUTTON_H + GAP) - 10) / 2);

		for (int i = 0; i < order.length; i++) {
			String name = order[i];
			int col = i % columns, row = i / columns;
			int x = left + col * (BUTTON_W + GAP);
			int y = top + row * (BUTTON_H + GAP);
			this.addRenderableWidget(Button.builder(label(name), b -> {
						toggle(name);
						b.setMessage(label(name));
					})
					.pos(x, y)
					.size(BUTTON_W, BUTTON_H)
					.tooltip(Tooltip.create(Component.literal(ExportSettings.DESCRIPTIONS.get(name))))
					.build());
		}

		int doneY = top + rows * (BUTTON_H + GAP) + 10;
		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
				.pos(this.width / 2 - 100, doneY)
				.size(200, BUTTON_H)
				.build());
	}

	private void toggle(String name) {
		switch (name) {
			case "partSize" -> settings.setInt(name, next(PART_SIZES, settings.partSize));
			case "tileSize" -> settings.setInt(name, next(TILE_SIZES, settings.tileSize));
			default -> settings.set(name, !settings.get(name));
		}
	}

	/** The next preset above the current value, wrapping to the first. */
	private static int next(int[] values, int current) {
		for (int v : values) {
			if (v > current) return v;
		}
		return values[0];
	}

	private Component label(String name) {
		String pretty = prettyName(name);
		return switch (name) {
			case "partSize" -> Component.literal(pretty + ": " + (settings.partSize == 0 ? "One file" : settings.partSize + " blocks"));
			case "tileSize" -> Component.literal(pretty + ": " + settings.tileSize + " blocks");
			default -> Component.literal(pretty + ": " + (settings.get(name) ? "ON" : "OFF"));
		};
	}

	private static String prettyName(String name) {
		return switch (name) {
			case "entities" -> "Entities";
			case "blockEntities" -> "Block entities";
			case "fluids" -> "Fluids";
			case "includePlayer" -> "Include yourself";
			case "closeEdges" -> "Faces at edges";
			case "rigEntities" -> "Entity rigs";
			case "tints" -> "Tints (vertex colours)";
			case "unlit" -> "Unlit materials";
			case "partSize" -> "Split files";
			case "tileSize" -> "Mesh tiles";
			case "showSelection" -> "Show selection";
			case "verboseLog" -> "Debug logging";
			default -> name;
		};
	}

	@Override
	public void onClose() {
		settings.save();
		this.minecraft.setScreen(parent);
	}
}
