package dev.gltfexport.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Plain geometry containers filled while walking the world and consumed by {@link GlbWriter}. */
public final class SceneData {
	/** Static block / fluid geometry, split into XZ tiles (one glTF node each), one primitive per material. */
	public final Map<Tile, Map<MaterialKey, Prim>> blockTiles = new LinkedHashMap<>();
	/** One group per entity / block entity. */
	public final List<Group> groups = new ArrayList<>();

	public int quadCount() {
		int n = 0;
		for (Map<MaterialKey, Prim> tile : blockTiles.values()) for (Prim p : tile.values()) n += p.quads;
		for (Group g : groups) {
			for (Prim p : g.statics.values()) n += p.quads;
			for (Rig r : g.rigs) for (Prim p : r.prims.values()) n += p.quads;
		}
		return n;
	}

	public Map<MaterialKey, Prim> tile(int tx, int tz) {
		return blockTiles.computeIfAbsent(new Tile(tx, tz), k -> new LinkedHashMap<>());
	}

	/** Tile coordinates (in units of the tile size) of a block-geometry node. */
	public record Tile(int x, int z) {
	}

	/**
	 * Identifies a glTF material.
	 *
	 * @param texture      texture key understood by {@link TextureCache}
	 * @param translucent  renders with alpha blending
	 * @param doubleSided  disable back-face culling
	 * @param emissive     glows (eyes layers, emissive render types)
	 */
	public record MaterialKey(String texture, boolean translucent, boolean doubleSided, boolean emissive) {
	}

	/**
	 * A triangle list built from quads. Carries an RGB vertex colour (biome / dye tint, multiplied with the texture by
	 * glTF viewers) and optionally one joint index per vertex for skinning.
	 */
	public static final class Prim {
		public final FloatArrayList pos = new FloatArrayList();
		public final FloatArrayList nrm = new FloatArrayList();
		public final FloatArrayList uv = new FloatArrayList();
		public final IntArrayList joint = new IntArrayList();
		/** sRGB 0xRRGGBB per vertex. */
		public final IntArrayList color = new IntArrayList();
		/** True once any vertex is not white, so COLOR_0 is only written when it matters. */
		public boolean tinted;
		public final IntArrayList idx = new IntArrayList();
		public int vertices;
		public int quads;

		/**
		 * @param p  12 floats: 4 vertex positions, counter-clockwise
		 * @param t  8 floats: 4 UVs
		 * @param j  joint index for all 4 vertices, or -1 for static geometry
		 * @param rgb tint for all 4 vertices (0xFFFFFF = none)
		 */
		public void addQuad(float[] p, float[] t, int j, int rgb) {
			addQuad(p, t, j, new int[]{rgb, rgb, rgb, rgb});
		}

		/** As above with a tint per vertex. */
		public void addQuad(float[] p, float[] t, int j, int[] rgb) {
			Vector3f a = new Vector3f(p[3] - p[0], p[4] - p[1], p[5] - p[2]);
			Vector3f b = new Vector3f(p[6] - p[0], p[7] - p[1], p[8] - p[2]);
			Vector3f n = a.cross(b);
			if (n.lengthSquared() < 1e-14f) {
				// first triangle degenerate (fluids sometimes emit triangles as quads): try the other diagonal
				Vector3f c = new Vector3f(p[9] - p[0], p[10] - p[1], p[11] - p[2]);
				n = new Vector3f(p[6] - p[0], p[7] - p[1], p[8] - p[2]).cross(c);
			}
			if (n.lengthSquared() < 1e-14f) {
				// fully degenerate quad: nothing visible, skip
				return;
			}
			n.normalize();
			int base = vertices;
			for (int i = 0; i < 4; i++) {
				pos.add(p[i * 3]);
				pos.add(p[i * 3 + 1]);
				pos.add(p[i * 3 + 2]);
				nrm.add(n.x);
				nrm.add(n.y);
				nrm.add(n.z);
				uv.add(t[i * 2]);
				uv.add(t[i * 2 + 1]);
				if (j >= 0) joint.add(j);
				int c = rgb[i] & 0xFFFFFF;
				color.add(c);
				if (c != 0xFFFFFF) tinted = true;
			}
			idx.add(base);
			idx.add(base + 1);
			idx.add(base + 2);
			idx.add(base);
			idx.add(base + 2);
			idx.add(base + 3);
			vertices += 4;
			quads++;
		}
	}

	/** A bone. {@code global} maps bone space to export space (the current pose doubles as the bind pose). */
	public record Joint(String name, int parent, Matrix4f global) {
	}

	/** A skinned model: a bone hierarchy plus geometry weighted 100% to one bone per vertex. */
	public static final class Rig {
		public final String name;
		public final List<Joint> joints = new ArrayList<>();
		public final Map<MaterialKey, Prim> prims = new LinkedHashMap<>();

		public Rig(String name) {
			this.name = name;
		}

		public Prim prim(MaterialKey key) {
			return prims.computeIfAbsent(key, k -> new Prim());
		}

		public boolean isEmpty() {
			return prims.values().stream().allMatch(p -> p.quads == 0);
		}
	}

	/** Everything rendered for one entity or block entity. */
	public static final class Group {
		public final String name;
		public final List<Rig> rigs = new ArrayList<>();
		public final Map<MaterialKey, Prim> statics = new LinkedHashMap<>();

		public Group(String name) {
			this.name = name;
		}

		public Prim stat(MaterialKey key) {
			return statics.computeIfAbsent(key, k -> new Prim());
		}

		public boolean isEmpty() {
			return rigs.isEmpty() && statics.values().stream().allMatch(p -> p.quads == 0);
		}
	}
}
