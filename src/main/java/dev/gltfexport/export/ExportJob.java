package dev.gltfexport.export;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import dev.gltfexport.ExportSettings;
import dev.gltfexport.GltfExportClient;
import dev.gltfexport.export.SceneData.Group;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * Captures the selection on the client thread (models, tints and textures must be read there), then encodes and
 * writes the .glb on a background thread.
 */
public final class ExportJob {
	public record Result(Path file, int quads, int entities, int blockEntities, int missingTextures, long bytes) {
	}

	private ExportJob() {
	}

	private static String idPath(net.minecraft.resources.Identifier id) {
		return id == null ? "unknown" : id.getPath();
	}

	public static CompletableFuture<Result> start(ClientLevel level, BlockPos a, BlockPos b, Path file,
												  ExportSettings settings, Consumer<String> progress) {
		BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));

		SceneData scene = new SceneData();
		TextureCache textures = new TextureCache();
		BlockMesher mesher = new BlockMesher(level, min, max);

		// --- blocks and fluids
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			mesher.meshWorldBlock(pos, scene.blocks, settings.fluids);
		}
		scene.blocks.keySet().forEach(k -> textures.source(k.texture()));

		DispatcherBridge bridge = new DispatcherBridge();

		// --- block entities (chests, beds, signs, banners, skulls, shulker boxes, ...)
		int blockEntities = 0;
		if (settings.blockEntities) {
			for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
				BlockEntity be = level.getBlockEntity(pos);
				if (be == null) continue;
				Group group = new Group(idPath(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())) + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ());
				SubmitCapture capture = new SubmitCapture(group, textures, mesher);
				bridge.submitBlockEntity(be, pos.getX() - min.getX(), pos.getY() - min.getY(), pos.getZ() - min.getZ(), capture);
				if (!group.isEmpty()) {
					scene.groups.add(group);
					blockEntities++;
				}
			}
		}

		// --- entities (mobs, players, armour stands, item frames, boats, dropped items, ...)
		int entities = 0;
		if (settings.entities) {
			AABB box = new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
			Entity self = Minecraft.getInstance().player;
			List<Entity> found = level.getEntities((Entity) null, box, e -> !e.isRemoved()
					&& (settings.includePlayer || e != self));
			for (Entity e : found) {
				String typeName = idPath(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
				Group group = new Group(typeName + "_" + e.getId());
				SubmitCapture capture = new SubmitCapture(group, textures, mesher);
				bridge.submitEntity(e, e.getX() - min.getX(), e.getY() - min.getY(), e.getZ() - min.getZ(), capture);
				if (!group.isEmpty()) {
					scene.groups.add(group);
					entities++;
				}
			}
		}

		// make sure every referenced texture is loaded before leaving the client thread
		for (Group g : scene.groups) {
			g.statics.keySet().forEach(k -> textures.source(k.texture()));
			g.rigs.forEach(r -> r.prims.keySet().forEach(k -> textures.source(k.texture())));
		}

		int quads = scene.quadCount();
		int finalEntities = entities;
		int finalBlockEntities = blockEntities;
		int missing = textures.missingCount();
		progress.accept("Captured " + quads + " quads, " + entities + " entities, " + blockEntities
				+ " block entities. Writing file...");

		return CompletableFuture.supplyAsync(() -> {
			try {
				Files.createDirectories(file.getParent());
				byte[] glb = new GlbWriter(scene, textures, settings).write(
						"Minecraft area " + min.toShortString() + " to " + max.toShortString());
				Files.write(file, glb);
				return new Result(file, quads, finalEntities, finalBlockEntities, missing, glb.length);
			} catch (Exception e) {
				GltfExportClient.LOGGER.error("glTF export failed", e);
				throw new RuntimeException(e);
			}
		});
	}
}
