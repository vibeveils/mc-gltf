package dev.gltfexport.export;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.gltfexport.GltfExportClient;
import dev.gltfexport.export.SceneData.MaterialKey;
import dev.gltfexport.export.SceneData.Prim;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRendering;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Turns block states and fluids into textured, tinted quads. */
public final class BlockMesher {
	private static final Direction[] DIRECTIONS = Direction.values();
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	private final ClientLevel level;
	private final BlockPos min;
	private final BlockPos max;
	private final BlockStateModelSet models;
	private final FluidStateModelSet fluidModels;
	private final BlockColors blockColors;
	private final RandomSource random = RandomSource.createThreadLocalInstance(0L);
	private final List<BlockStateModelPart> parts = new ArrayList<>();
	private final CaptureConsumer capture = new CaptureConsumer();
	private FluidRenderer fluidRenderer;
	private boolean fluidsBroken;
	private boolean tintFallbackLogged;

	private final boolean closeEdges;

	public BlockMesher(ClientLevel level, BlockPos min, BlockPos max, boolean closeEdges) {
		this.closeEdges = closeEdges;
		this.level = level;
		this.min = min;
		this.max = max;
		Minecraft mc = Minecraft.getInstance();
		this.models = mc.getModelManager().getBlockStateModelSet();
		this.fluidModels = mc.getModelManager().getFluidStateModelSet();
		this.blockColors = mc.getBlockColors();
	}

	private boolean inside(BlockPos p) {
		return p.getX() >= min.getX() && p.getY() >= min.getY() && p.getZ() >= min.getZ()
				&& p.getX() <= max.getX() && p.getY() <= max.getY() && p.getZ() <= max.getZ();
	}

	/**
	 * Neighbour used for face culling. With closeEdges, everything outside the selection counts as air so the model is
	 * closed at its border; otherwise the real world is used, matching what the game draws.
	 */
	private BlockState stateAt(BlockPos p) {
		return inside(p) || !closeEdges ? level.getBlockState(p) : AIR;
	}

	// ------------------------------------------------------------------ world blocks

	/** Meshes the block (and fluid) at a world position into the scene's static block geometry. */
	public void meshWorldBlock(BlockPos pos, Map<MaterialKey, Prim> out, boolean fluids) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) return;
		float ox = pos.getX() - min.getX();
		float oy = pos.getY() - min.getY();
		float oz = pos.getZ() - min.getZ();

		if (state.getRenderShape() != RenderShape.INVISIBLE) {
			Vec3 offset = state.getOffset(pos);
			Matrix4f m = new Matrix4f().translation(ox + (float) offset.x, oy + (float) offset.y, oz + (float) offset.z);
			meshModel(state, pos, m, true, null, out);
		}

		if (fluids) {
			FluidState fluid = state.getFluidState();
			if (!fluid.isEmpty()) meshFluid(pos, state, fluid, ox, oy, oz, out);
		}
	}

	/**
	 * Meshes a block model.
	 *
	 * @param pos         world position used for random variants and biome tints (may be null)
	 * @param transform   block space (0..1) to export space
	 * @param cull        cull faces hidden by neighbours inside the selection
	 * @param tintLayers  explicit tint values (from a render submit), or null to compute from the world
	 */
	public void meshModel(BlockState state, BlockPos pos, Matrix4f transform, boolean cull, int[] tintLayers,
						  Map<MaterialKey, Prim> out) {
		BlockStateModel model = models.get(state);
		if (model == null) return;
		random.setSeed(pos != null ? state.getSeed(pos) : 42L);
		parts.clear();
		model.collectParts(random, parts);
		meshParts(state, pos, parts, transform, cull, tintLayers, out);
	}

	public void meshParts(BlockState state, BlockPos pos, List<BlockStateModelPart> modelParts, Matrix4f transform,
						  boolean cull, int[] tintLayers, Map<MaterialKey, Prim> out) {
		int[] tintCache = null;
		List<BakedQuad> quads = new ArrayList<>();
		it.unimi.dsi.fastutil.ints.IntArrayList tints = new it.unimi.dsi.fastutil.ints.IntArrayList();
		for (BlockStateModelPart part : List.copyOf(modelParts)) {
			for (int d = 0; d <= DIRECTIONS.length; d++) {
				Direction dir = d < DIRECTIONS.length ? DIRECTIONS[d] : null;
				if (dir != null && cull && pos != null && state != null) {
					BlockState neighbour = stateAt(pos.relative(dir));
					if (!Block.shouldRenderFace(state, neighbour, dir)) continue;
				}
				for (BakedQuad quad : part.getQuads(dir)) {
					int tintIndex = quad.materialInfo().tintIndex();
					int tint = 0xFFFFFF;
					if (tintIndex >= 0) {
						if (tintLayers != null) {
							tint = tintIndex < tintLayers.length ? tintLayers[tintIndex] : 0xFFFFFF;
						} else if (state != null) {
							if (tintCache == null) {
								tintCache = new int[8];
								java.util.Arrays.fill(tintCache, Integer.MIN_VALUE);
							}
							if (tintIndex >= tintCache.length) tintCache = java.util.Arrays.copyOf(tintCache, tintIndex + 1);
							if (tintCache[tintIndex] == Integer.MIN_VALUE) tintCache[tintIndex] = worldTint(state, pos, tintIndex);
							tint = tintCache[tintIndex];
						}
					}
					quads.add(quad);
					tints.add(tint);
				}
			}
		}

		// Flat models (flowers, grass, saplings, rails, ...) draw each plane twice, front and back.
		// Keep one quad per plane and make its material double-sided instead.
		boolean[] doubleSided = new boolean[quads.size()];
		boolean[] skip = new boolean[quads.size()];
		Map<String, Integer> seen = new java.util.HashMap<>();
		for (int i = 0; i < quads.size(); i++) {
			String key = planeKey(quads.get(i));
			Integer first = seen.putIfAbsent(key, i);
			if (first != null) {
				skip[i] = true;
				doubleSided[first] = true;
			}
		}
		for (int i = 0; i < quads.size(); i++) {
			if (!skip[i]) addBakedQuad(quads.get(i), transform, tints.getInt(i), out, -1, doubleSided[i]);
		}
	}

	/** Identifies a quad by its texture and the set of its four corners, ignoring winding (front vs back). */
	private static String planeKey(BakedQuad quad) {
		long[] corners = new long[4];
		for (int i = 0; i < 4; i++) {
			Vector3fc p = quad.position(i);
			long x = Math.round(p.x() * 4096), y = Math.round(p.y() * 4096), z = Math.round(p.z() * 4096);
			corners[i] = (x & 0x1FFFFF) << 42 | (y & 0x1FFFFF) << 21 | (z & 0x1FFFFF);
		}
		java.util.Arrays.sort(corners);
		return System.identityHashCode(quad.materialInfo().sprite()) + java.util.Arrays.toString(corners);
	}

	/** Computes a block tint exactly as the vanilla block renderer does (grass, foliage, water, redstone, stems...). */
	public int worldTint(BlockState state, BlockPos pos, int tintIndex) {
		try {
			List<BlockTintSource> sources = blockColors.getTintSources(state);
			if (sources == null || tintIndex >= sources.size()) return 0xFFFFFF;
			BlockTintSource source = sources.get(tintIndex);
			if (source == null) return 0xFFFFFF;
			if (pos != null) {
				try {
					return source.colorInWorld(state, (BlockAndTintGetter) (Object) level, pos);
				} catch (ClassCastException e) {
					if (!tintFallbackLogged) {
						tintFallbackLogged = true;
						GltfExportClient.LOGGER.warn("Level is not a BlockAndTintGetter; using default block tints");
					}
				}
			}
			return source.color(state);
		} catch (RuntimeException e) {
			return 0xFFFFFF;
		}
	}

	/** Adds one baked quad. UVs are converted from atlas space to sprite space so each sprite becomes its own texture. */
	public static void addBakedQuad(BakedQuad quad, Matrix4f transform, int tint, Map<MaterialKey, Prim> out, int joint) {
		addBakedQuad(quad, transform, tint, out, joint, false);
	}

	public static void addBakedQuad(BakedQuad quad, Matrix4f transform, int tint, Map<MaterialKey, Prim> out, int joint,
									boolean doubleSided) {
		TextureAtlasSprite sprite = quad.materialInfo().sprite();
		boolean translucent = false;
		try {
			ChunkSectionLayer layer = quad.materialInfo().layer();
			translucent = layer == ChunkSectionLayer.TRANSLUCENT;
		} catch (RuntimeException ignored) {
		}
		MaterialKey key = new MaterialKey(TextureCache.spriteKey(sprite), translucent, doubleSided, quad.materialInfo().lightEmission() >= 15);
		float[] p = new float[12];
		float[] t = new float[8];
		Vector3f v = new Vector3f();
		for (int i = 0; i < 4; i++) {
			Vector3fc pos = quad.position(i);
			transform.transformPosition(v.set(pos));
			p[i * 3] = v.x;
			p[i * 3 + 1] = v.y;
			p[i * 3 + 2] = v.z;
			long packed = quad.packedUV(i);
			float u = net.minecraft.client.model.geom.builders.UVPair.unpackU(packed);
			float vv = net.minecraft.client.model.geom.builders.UVPair.unpackV(packed);
			t[i * 2] = sprite != null ? TextureCache.localU(sprite, u) : u;
			t[i * 2 + 1] = sprite != null ? TextureCache.localV(sprite, vv) : vv;
		}
		SceneData.alignUv(p, t);
		out.computeIfAbsent(key, k -> new Prim()).addQuad(p, t, joint, tint);
	}

	// ------------------------------------------------------------------ fluids

	private void meshFluid(BlockPos pos, BlockState state, FluidState fluid, float ox, float oy, float oz,
						   Map<MaterialKey, Prim> out) {
		if (fluidsBroken) return;
		FluidModel fluidModel;
		try {
			fluidModel = fluidModels.get(fluid);
			if (fluidRenderer == null) fluidRenderer = new FluidRenderer(fluidModels);
			capture.clear();
			FluidRenderHandler handler = FluidRenderingRegistry.get(fluid.getType());
			if (handler == null) handler = FluidRenderingRegistry.get(fluid.is(FluidTags.LAVA) ? Fluids.LAVA : Fluids.WATER);
			BlockAndTintGetter tintGetter = (BlockAndTintGetter) (Object) level;
			FluidRendering.render(fluidRenderer, handler, tintGetter, pos, layer -> capture, state, fluid,
					new FluidRendering.DefaultRenderer() {
					});
		} catch (Throwable e) {
			fluidsBroken = true;
			GltfExportClient.LOGGER.warn("Fluid export disabled: {}", e.toString());
			return;
		}
		if (capture.quadCount() == 0) return;

		List<TextureAtlasSprite> sprites = new ArrayList<>(3);
		var still = fluidModel.stillMaterial();
		var flowing = fluidModel.flowingMaterial();
		var overlayMat = fluidModel.overlayMaterial();
		addSprite(sprites, still != null ? still.sprite() : null);
		addSprite(sprites, flowing != null ? flowing.sprite() : null);
		addSprite(sprites, overlayMat != null ? overlayMat.sprite() : null);

		int tint = fluidTint(fluidModel, state, fluid, pos);
		boolean translucent = !fluid.is(FluidTags.LAVA);

		// Vanilla writes fluid vertices relative to the 16x16x16 section; detect that and convert to export space.
		float sx = pos.getX() & 15, sy = pos.getY() & 15, sz = pos.getZ() & 15;
		boolean sectionLocal = true;
		for (int i = 0; i < capture.vertexCount(); i++) {
			if (Math.abs(capture.x(i) - sx - 0.5f) > 1.01f || Math.abs(capture.y(i) - sy - 0.5f) > 1.01f
					|| Math.abs(capture.z(i) - sz - 0.5f) > 1.01f) {
				sectionLocal = false;
				break;
			}
		}
		float bx = sectionLocal ? sx : pos.getX();
		float by = sectionLocal ? sy : pos.getY();
		float bz = sectionLocal ? sz : pos.getZ();

		float[] p = new float[12];
		float[] t = new float[8];
		for (int q = 0; q < capture.quadCount(); q++) {
			float cu = 0, cv = 0;
			for (int i = 0; i < 4; i++) {
				int vi = q * 4 + i;
				p[i * 3] = capture.x(vi) - bx + ox;
				p[i * 3 + 1] = capture.y(vi) - by + oy;
				p[i * 3 + 2] = capture.z(vi) - bz + oz;
				cu += capture.u(vi) / 4f;
				cv += capture.v(vi) / 4f;
			}
			TextureAtlasSprite sprite = null;
			for (TextureAtlasSprite s : sprites) {
				if (cu >= s.getU0() - 1e-5f && cu <= s.getU1() + 1e-5f && cv >= s.getV0() - 1e-5f && cv <= s.getV1() + 1e-5f) {
					sprite = s;
					break;
				}
			}
			if (sprite == null && !sprites.isEmpty()) sprite = sprites.get(0);
			for (int i = 0; i < 4; i++) {
				int vi = q * 4 + i;
				t[i * 2] = sprite != null ? TextureCache.localU(sprite, capture.u(vi)) : capture.u(vi);
				t[i * 2 + 1] = sprite != null ? TextureCache.localV(sprite, capture.v(vi)) : capture.v(vi);
			}
			boolean overlay = sprites.size() > 2 && sprite == sprites.get(2);
			MaterialKey key = new MaterialKey(TextureCache.spriteKey(sprite),
					translucent && !overlay, true, fluid.is(FluidTags.LAVA));
			SceneData.alignUv(p, t);
			out.computeIfAbsent(key, k -> new Prim()).addQuad(p, t, -1, overlay ? 0xFFFFFF : tint);
		}
	}

	private static void addSprite(List<TextureAtlasSprite> list, TextureAtlasSprite sprite) {
		if (sprite != null) list.add(sprite);
	}

	private int fluidTint(FluidModel model, BlockState state, FluidState fluid, BlockPos pos) {
		// 26.x fluid models carry their own tint source (water -> biome water colour, lava -> none).
		try {
			BlockTintSource source = model.tintSource();
			if (source == null) return 0xFFFFFF;
			try {
				return source.colorInWorld(state, (BlockAndTintGetter) (Object) level, pos);
			} catch (ClassCastException e) {
				return source.color(state);
			}
		} catch (RuntimeException ignored) {
		}
		// Older path: the tint source registered for the fluid's block.
		try {
			BlockState legacy = fluid.createLegacyBlock();
			int tint = worldTint(legacy, pos, 0);
			if ((tint & 0xFFFFFF) != 0xFFFFFF) return tint;
		} catch (RuntimeException ignored) {
		}
		// Fallback: the brightest captured vertex colour (the top face carries no directional shading).
		int best = 0xFFFFFF, bestSum = -1;
		for (int i = 0; i < capture.vertexCount(); i++) {
			int c = capture.argb(i);
			int sum = (c >> 16 & 255) + (c >> 8 & 255) + (c & 255);
			if (sum > bestSum) {
				bestSum = sum;
				best = c;
			}
		}
		return best;
	}
}
