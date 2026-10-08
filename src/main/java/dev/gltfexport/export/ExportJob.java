package dev.gltfexport.export;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.gltfexport.ExportSettings;
import dev.gltfexport.GltfExportClient;
import dev.gltfexport.export.SceneData.Group;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * An export of any size, run incrementally from the client tick so the game never freezes.
 *
 * <p>The selection is cut into <em>parts</em> (XZ squares of {@link ExportSettings#partSize} blocks, full height).
 * Each part is captured over as many ticks as it needs, then encoded and written on a background thread while the
 * next part is captured, and its geometry is released. Memory use is therefore bounded by one part, and output size
 * by nothing but disk space. A selection that fits in one part produces a single {@code name.glb}; larger ones
 * produce {@code name/name_<px>_<pz>.glb} files that share one coordinate system and line up when imported together.
 *
 * <p>Within each file, block geometry is further split into {@link ExportSettings#tileSize} tiles, one node each.
 */
public final class ExportJob {
	private static final long TICK_BUDGET_NANOS = 30_000_000L; // 30 ms of capture per client tick

	public interface Listener {
		void progress(String message);

		void finished(String message, boolean success);
	}

	private enum Phase { BLOCKS, BLOCK_ENTITIES, ENTITIES, WRITE, WAIT_WRITE, DONE }

	private final ClientLevel level;
	private final BlockPos min, max;
	private final String name;
	private final Path outputDir;
	private final ExportSettings settings;
	private final Listener listener;
	private final TextureCache textures = new TextureCache();
	private final BlockMesher mesher;
	private final DispatcherBridge bridge = new DispatcherBridge();
	private final List<int[]> parts = new ArrayList<>(); // {minX, minZ, maxX, maxZ}
	private final boolean multiFile;
	private final long totalColumns;

	private Phase phase = Phase.BLOCKS;
	private int partIndex;
	private SceneData scene;
	private List<BlockPos> blockEntityPositions;
	private int cursorX, cursorZ; // column cursor inside the current part
	private int beCursor;
	private CompletableFuture<Long> pendingWrite;
	private boolean cancelled;

	private long columnsDone, unloadedColumns, totalQuads, totalBytes;
	private int entityCount, blockEntityCount, filesWritten;
	private long lastProgressNanos;
	private final long startNanos = System.nanoTime();

	public ExportJob(ClientLevel level, BlockPos a, BlockPos b, String name, Path exportRoot, ExportSettings settings,
					 Listener listener) {
		this.level = level;
		this.min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		this.max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		this.name = name;
		this.settings = settings.copy(); // changing options mid-export doesn't affect this job
		this.listener = listener;
		this.mesher = new BlockMesher(level, min, max, this.settings.closeEdges);

		int part = this.settings.partSize <= 0 ? Integer.MAX_VALUE : settings.partSize;
		for (long x = min.getX(); x <= max.getX(); x += part) {
			for (long z = min.getZ(); z <= max.getZ(); z += part) {
				parts.add(new int[]{(int) x, (int) z,
						(int) Math.min(max.getX(), x + part - 1L), (int) Math.min(max.getZ(), z + part - 1L)});
			}
		}
		this.multiFile = parts.size() > 1;
		this.outputDir = multiFile ? exportRoot.resolve(name) : exportRoot;
		this.totalColumns = (long) (max.getX() - min.getX() + 1) * (max.getZ() - min.getZ() + 1);
		beginPart();
	}

	public void cancel() {
		cancelled = true;
	}

	public boolean isDone() {
		return phase == Phase.DONE;
	}

	public String describe() {
		return parts.size() == 1 ? "1 file" : parts.size() + " files (part size " + settings.partSize + ")";
	}

	private void beginPart() {
		int[] p = parts.get(partIndex);
		scene = new SceneData();
		blockEntityPositions = new ArrayList<>();
		cursorX = p[0];
		cursorZ = p[1];
		beCursor = 0;
		phase = Phase.BLOCKS;
	}

	/** Called every client tick. */
	public void tick() {
		if (phase == Phase.DONE) return;
		if (cancelled) {
			phase = Phase.DONE;
			listener.finished("Export cancelled after " + filesWritten + " file(s).", false);
			return;
		}
		if (level != Minecraft.getInstance().level) {
			phase = Phase.DONE;
			listener.finished("Export aborted: the world changed.", false);
			return;
		}
		long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
		try {
			while (System.nanoTime() < deadline && phase != Phase.DONE) {
				if (!step()) break;
			}
		} catch (RuntimeException e) {
			GltfExportClient.LOGGER.error("glTF export failed", e);
			phase = Phase.DONE;
			listener.finished("Export failed: " + e, false);
			return;
		}
		reportProgress();
	}

	/** Does one unit of work. Returns false if it has to wait for the next tick. */
	private boolean step() {
		// after the last part has been handed to the writer, partIndex == parts.size() (only WAIT_WRITE runs then)
		int[] p = partIndex < parts.size() ? parts.get(partIndex) : null;
		switch (phase) {
			case BLOCKS -> {
				meshColumn(cursorX, cursorZ);
				columnsDone++;
				if (++cursorZ > p[3]) {
					cursorZ = p[1];
					if (++cursorX > p[2]) phase = settings.blockEntities ? Phase.BLOCK_ENTITIES : Phase.ENTITIES;
				}
				return true;
			}
			case BLOCK_ENTITIES -> {
				if (beCursor >= blockEntityPositions.size()) {
					phase = Phase.ENTITIES;
					return true;
				}
				captureBlockEntity(blockEntityPositions.get(beCursor++));
				return true;
			}
			case ENTITIES -> {
				if (settings.entities) captureEntities(p);
				phase = Phase.WRITE;
				return true;
			}
			case WRITE -> {
				// keep at most one part in flight so memory stays bounded
				if (pendingWrite != null && !pendingWrite.isDone()) return false;
				if (!collectWrite()) return false;
				startWrite(p);
				partIndex++;
				if (partIndex < parts.size()) beginPart();
				else phase = Phase.WAIT_WRITE;
				return true;
			}
			case WAIT_WRITE -> {
				if (pendingWrite != null && !pendingWrite.isDone()) return false;
				if (!collectWrite()) return false;
				phase = Phase.DONE;
				finish();
				return false;
			}
			default -> {
				return false;
			}
		}
	}

	private void meshColumn(int x, int z) {
		if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
			unloadedColumns++;
			return;
		}
		int tile = Math.max(1, settings.tileSize);
		var out = scene.tile(Math.floorDiv(x - min.getX(), tile), Math.floorDiv(z - min.getZ(), tile));
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = min.getY(); y <= max.getY(); y++) {
			pos.set(x, y, z);
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) continue;
			BlockPos immutable = pos.immutable();
			mesher.meshWorldBlock(immutable, out, settings.fluids);
			if (settings.blockEntities && state.hasBlockEntity()) blockEntityPositions.add(immutable);
		}
	}

	private void captureBlockEntity(BlockPos pos) {
		BlockEntity be = level.getBlockEntity(pos);
		if (be == null) return;
		Group group = new Group(idPath(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()))
				+ "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ());
		SubmitCapture capture = new SubmitCapture(group, textures, mesher, SkinResolver.find(be));
		bridge.submitBlockEntity(be, pos.getX() - min.getX(), pos.getY() - min.getY(), pos.getZ() - min.getZ(), capture);
		// origin at the bottom centre of the block; block entities face via their own model
		group.localize(new org.joml.Matrix4f().translation(pos.getX() - min.getX() + 0.5f, pos.getY() - min.getY(),
				pos.getZ() - min.getZ() + 0.5f));
		if (!group.isEmpty()) {
			scene.groups.add(group);
			blockEntityCount++;
		}
	}

	private void captureEntities(int[] p) {
		AABB box = new AABB(p[0], min.getY(), p[1], p[2] + 1, max.getY() + 1, p[3] + 1);
		Entity self = Minecraft.getInstance().player;
		List<Entity> found = level.getEntities((Entity) null, box, e -> !e.isRemoved()
				&& (settings.includePlayer || e != self));
		for (Entity e : found) {
			// an entity straddling a part border belongs to the part containing its feet
			int bx = (int) Math.floor(e.getX()), bz = (int) Math.floor(e.getZ());
			if (bx < p[0] || bx > p[2] || bz < p[1] || bz > p[3]) continue;
			Group group = new Group(idPath(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())) + "_" + e.getId());
			SubmitCapture capture = new SubmitCapture(group, textures, mesher, SkinResolver.find(e));
			bridge.submitEntity(e, e.getX() - min.getX(), e.getY() - min.getY(), e.getZ() - min.getZ(), capture);
			// place the group at the entity's feet, turned to face where it faces; inside, the entity faces +Z
			float yaw = e instanceof net.minecraft.world.entity.LivingEntity living ? living.yBodyRot : e.getYRot();
			group.localize(new org.joml.Matrix4f()
					.translation((float) (e.getX() - min.getX()), (float) (e.getY() - min.getY()), (float) (e.getZ() - min.getZ()))
					.rotateY((float) Math.toRadians(-yaw)));
			if (!group.isEmpty()) {
				scene.groups.add(group);
				entityCount++;
			}
		}
	}

	private void startWrite(int[] p) {
		SceneData part = scene;
		scene = null; // release on the client side; the writer holds the only reference
		// load every referenced texture now, on the client thread
		part.blockTiles.values().forEach(t -> t.keySet().forEach(k -> textures.source(k.texture())));
		for (Group g : part.groups) {
			g.statics.keySet().forEach(k -> textures.source(k.texture()));
			g.rigs.forEach(r -> r.prims.keySet().forEach(k -> textures.source(k.texture())));
		}
		totalQuads += part.quadCount();
		int px = Math.floorDiv(p[0] - min.getX(), Math.max(1, settings.partSize));
		int pz = Math.floorDiv(p[1] - min.getZ(), Math.max(1, settings.partSize));
		Path file = outputDir.resolve(multiFile ? name + "_" + px + "_" + pz + ".glb" : name + ".glb");
		String title = "Minecraft " + min.toShortString() + " to " + max.toShortString()
				+ (multiFile ? " part " + px + "," + pz : "");
		pendingWrite = CompletableFuture.supplyAsync(() -> {
			try {
				Files.createDirectories(outputDir);
				return new GlbWriter(part, textures, settings).write(title, file);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}

	/** Returns false if the pending write failed (the job is then finished with an error). */
	private boolean collectWrite() {
		if (pendingWrite == null) return true;
		try {
			totalBytes += pendingWrite.join();
			filesWritten++;
			pendingWrite = null;
			return true;
		} catch (RuntimeException e) {
			Throwable t = e;
			while (t.getCause() != null) t = t.getCause();
			GltfExportClient.LOGGER.error("Writing glTF part failed", e);
			phase = Phase.DONE;
			listener.finished("Export failed while writing: " + t.getMessage(), false);
			return false;
		}
	}

	private void reportProgress() {
		long now = System.nanoTime();
		if (phase == Phase.DONE || now - lastProgressNanos < 1_000_000_000L) return;
		lastProgressNanos = now;
		int pct = (int) (100 * columnsDone / Math.max(1, totalColumns));
		String what = switch (phase) {
			case BLOCKS -> "blocks";
			case BLOCK_ENTITIES -> "block entities";
			case ENTITIES -> "entities";
			default -> "writing";
		};
		listener.progress("glTF export " + pct + "% - " + what
				+ (multiFile ? " (part " + Math.min(partIndex + 1, parts.size()) + "/" + parts.size() + ")" : ""));
	}

	private void finish() {
		long seconds = (System.nanoTime() - startNanos) / 1_000_000_000L;
		StringBuilder sb = new StringBuilder();
		sb.append("Saved ").append(filesWritten).append(filesWritten == 1 ? " file" : " files")
				.append(" (").append(totalBytes >> 20).append(" MB, ").append(totalQuads).append(" quads, ")
				.append(entityCount).append(" entities, ").append(blockEntityCount).append(" block entities) in ")
				.append(seconds).append("s to ").append(outputDir.toAbsolutePath());
		if (unloadedColumns > 0) {
			sb.append(". Warning: ").append(unloadedColumns)
					.append(" block columns were not loaded on your client and are empty; move closer or raise render distance and export again");
		}
		if (textures.missingCount() > 0) {
			sb.append(". ").append(textures.missingCount()).append(" texture(s) used a placeholder (see log)");
		}
		listener.finished(sb.toString(), true);
	}

	private static String idPath(Identifier id) {
		return id == null ? "unknown" : id.getPath();
	}
}
