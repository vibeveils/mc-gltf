package dev.gltfexport.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.joml.Matrix3f;
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
		/** Applies a transform to all positions and normals (used to move geometry into a group's local space). */
		public void transform(Matrix4f m, Matrix3f normalMatrix) {
			Vector3f v = new Vector3f();
			for (int i = 0; i < vertices; i++) {
				m.transformPosition(v.set(pos.getFloat(i * 3), pos.getFloat(i * 3 + 1), pos.getFloat(i * 3 + 2)));
				pos.set(i * 3, v.x);
				pos.set(i * 3 + 1, v.y);
				pos.set(i * 3 + 2, v.z);
				normalMatrix.transform(v.set(nrm.getFloat(i * 3), nrm.getFloat(i * 3 + 1), nrm.getFloat(i * 3 + 2))).normalize();
				nrm.set(i * 3, v.x);
				nrm.set(i * 3 + 1, v.y);
				nrm.set(i * 3 + 2, v.z);
			}
		}

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

		/**
		 * Greedy meshing for grid-aligned faces: coplanar neighbouring full-block faces that show the same texture the
		 * same way round (same UV direction and phase) with the same colour are merged into one large rectangle whose
		 * UVs repeat the texture once per block. Visually identical; far fewer vertices. Faces that don't fit the
		 * pattern (partial faces, odd UVs, rotated variants, tint gradients) are left untouched. Static geometry only.
		 * Must run before {@link #weld}.
		 */
		public void mergeCoplanar() {
			if (welded || vertices == 0 || joint.size() != 0 || quads < 2) return;
			int n = vertices / 4;
			java.util.LinkedHashMap<String, java.util.HashSet<Long>> groups = new java.util.LinkedHashMap<>();
			java.util.HashMap<String, float[]> groupInfo = new java.util.HashMap<>();
			boolean[] merged = new boolean[n];
			for (int q = 0; q < n; q++) {
				float[] info = new float[14];
				String key = gridKey(q, info);
				if (key == null) continue;
				merged[q] = true;
				groups.computeIfAbsent(key, k -> new java.util.HashSet<>()).add(pack((int) info[12], (int) info[13]));
				groupInfo.putIfAbsent(key, info);
			}
			int candidates = 0;
			for (boolean m : merged) if (m) candidates++;
			if (candidates < 2) return;

			// keep the quads that can't be merged
			Prim out = new Prim();
			float[] p = new float[12], t = new float[8];
			int[] c = new int[4];
			for (int q = 0; q < n; q++) {
				if (merged[q]) continue;
				for (int i = 0; i < 4; i++) {
					int v = q * 4 + i;
					for (int k = 0; k < 3; k++) p[i * 3 + k] = pos.getFloat(v * 3 + k);
					t[i * 2] = uv.getFloat(v * 2);
					t[i * 2 + 1] = uv.getFloat(v * 2 + 1);
					c[i] = color.getInt(v);
				}
				out.addQuad(p, t, -1, c);
			}

			for (var e : groups.entrySet()) {
				float[] g = groupInfo.get(e.getKey());
				int axis = (int) g[0], ax = (int) g[1], bx = (int) g[2];
				float nSign = g[3], plane = g[4];
				float gua = g[5], gub = g[6], gva = g[7], gvb = g[8], uo = g[9], vo = g[10];
				int rgb = (int) g[11];
				java.util.HashSet<Long> cells = e.getValue();
				java.util.ArrayList<Long> sorted = new java.util.ArrayList<>(cells);
				sorted.sort((x, y) -> {
					int bx1 = (int) (x >> 32), by1 = (int) (y >> 32);
					if (bx1 != by1) return Integer.compare(bx1, by1);
					return Integer.compare((int) (long) x, (int) (long) y);
				});
				java.util.HashSet<Long> used = new java.util.HashSet<>();
				for (long cell : sorted) {
					if (used.contains(cell)) continue;
					int a0 = (int) cell, b0 = (int) (cell >> 32);
					int w = 1;
					while (cells.contains(pack(a0 + w, b0)) && !used.contains(pack(a0 + w, b0))) w++;
					int h = 1;
					grow:
					while (true) {
						for (int da = 0; da < w; da++) {
							long next = pack(a0 + da, b0 + h);
							if (!cells.contains(next) || used.contains(next)) break grow;
						}
						h++;
					}
					for (int db = 0; db < h; db++) for (int da = 0; da < w; da++) used.add(pack(a0 + da, b0 + db));
					int[][] corners = {{a0, b0}, {a0 + w, b0}, {a0 + w, b0 + h}, {a0, b0 + h}};
					for (int i = 0; i < 4; i++) {
						float[] xyz = new float[3];
						xyz[axis] = plane;
						xyz[ax] = corners[i][0];
						xyz[bx] = corners[i][1];
						p[i * 3] = xyz[0];
						p[i * 3 + 1] = xyz[1];
						p[i * 3 + 2] = xyz[2];
						t[i * 2] = uo + gua * corners[i][0] + gub * corners[i][1];
						t[i * 2 + 1] = vo + gva * corners[i][0] + gvb * corners[i][1];
					}
					// keep the original facing: reverse the corner order if it winds the wrong way
					float e1a = p[3] - p[0], e1b = p[4] - p[1], e1c = p[5] - p[2];
					float e2a = p[6] - p[0], e2b = p[7] - p[1], e2c = p[8] - p[2];
					float[] cross = {e1b * e2c - e1c * e2b, e1c * e2a - e1a * e2c, e1a * e2b - e1b * e2a};
					if (cross[axis] * nSign < 0) {
						float[] rp = p.clone(), rt = t.clone();
						for (int i = 0; i < 4; i++) {
							int j = 3 - i;
							System.arraycopy(rp, j * 3, p, i * 3, 3);
							System.arraycopy(rt, j * 2, t, i * 2, 2);
						}
					}
					out.addQuad(p, t, -1, rgb);
				}
			}

			pos.clear();
			nrm.clear();
			uv.clear();
			color.clear();
			idx.clear();
			copy(out.pos, pos);
			copy(out.nrm, nrm);
			copy(out.uv, uv);
			for (int i = 0; i < out.color.size(); i++) color.add(out.color.getInt(i));
			for (int i = 0; i < out.idx.size(); i++) idx.add(out.idx.getInt(i));
			vertices = out.vertices;
			quads = out.quads;
		}

		private static long pack(int a, int b) {
			return (long) b << 32 | (a & 0xFFFFFFFFL);
		}

		/**
		 * Describes quad q if it is a 1x1 grid-aligned face whose texture is mapped once per block; returns a grouping
		 * key, or null if it can't be merged. info receives: axis, aAxis, bAxis, normalSign, plane, gua, gub, gva, gvb,
		 * uOffset, vOffset, rgb, cellA, cellB.
		 */
		private String gridKey(int q, float[] info) {
			int v0 = q * 4;
			float nx = nrm.getFloat(v0 * 3), ny = nrm.getFloat(v0 * 3 + 1), nz = nrm.getFloat(v0 * 3 + 2);
			int axis;
			float sign;
			if (Math.abs(nx) > 0.999f) { axis = 0; sign = Math.signum(nx); }
			else if (Math.abs(ny) > 0.999f) { axis = 1; sign = Math.signum(ny); }
			else if (Math.abs(nz) > 0.999f) { axis = 2; sign = Math.signum(nz); }
			else return null;
			int ax = axis == 0 ? 1 : 0, bx = axis == 2 ? 1 : 2;
			int rgb = color.getInt(v0);
			float plane = pos.getFloat(v0 * 3 + axis);
			float amin = Float.MAX_VALUE, bmin = Float.MAX_VALUE, amax = -Float.MAX_VALUE, bmax = -Float.MAX_VALUE;
			for (int i = 0; i < 4; i++) {
				int v = v0 + i;
				if (color.getInt(v) != rgb) return null;
				if (Math.abs(pos.getFloat(v * 3 + axis) - plane) > 1e-5f) return null;
				float a = pos.getFloat(v * 3 + ax), b = pos.getFloat(v * 3 + bx);
				amin = Math.min(amin, a);
				amax = Math.max(amax, a);
				bmin = Math.min(bmin, b);
				bmax = Math.max(bmax, b);
			}
			if (Math.abs(amax - amin - 1) > 1e-5f || Math.abs(bmax - bmin - 1) > 1e-5f) return null;
			if (Math.abs(amin - Math.round(amin)) > 1e-5f || Math.abs(bmin - Math.round(bmin)) > 1e-5f) return null;
			// UVs at the three reference corners
			float[] u = new float[4], w = new float[4]; // index: bit0 = a at max, bit1 = b at max
			int seen = 0;
			for (int i = 0; i < 4; i++) {
				int v = v0 + i;
				int ia = pos.getFloat(v * 3 + ax) > amin + 0.5f ? 1 : 0;
				int ib = pos.getFloat(v * 3 + bx) > bmin + 0.5f ? 2 : 0;
				u[ia | ib] = uv.getFloat(v * 2);
				w[ia | ib] = uv.getFloat(v * 2 + 1);
				seen |= 1 << (ia | ib);
			}
			if (seen != 15) return null;
			float gua = u[1] - u[0], gub = u[2] - u[0], gva = w[1] - w[0], gvb = w[2] - w[0];
			// affine check on the 4th corner
			if (Math.abs(u[0] + gua + gub - u[3]) > 1e-4f || Math.abs(w[0] + gva + gvb - w[3]) > 1e-4f) return null;
			// texture must be mapped exactly once per block, along the grid axes
			if (!unitAxis(gua, gub) || !unitAxis(gva, gvb) || (Math.abs(gua) > 0.5f) == (Math.abs(gva) > 0.5f)) return null;
			gua = Math.round(gua);
			gub = Math.round(gub);
			gva = Math.round(gva);
			gvb = Math.round(gvb);
			int ia0 = Math.round(amin), ib0 = Math.round(bmin);
			float uo = u[0] - gua * ia0 - gub * ib0, vo = w[0] - gva * ia0 - gvb * ib0;
			uo -= (float) Math.floor(uo + 1e-4);
			vo -= (float) Math.floor(vo + 1e-4);
			info[0] = axis; info[1] = ax; info[2] = bx; info[3] = sign; info[4] = plane;
			info[5] = gua; info[6] = gub; info[7] = gva; info[8] = gvb; info[9] = uo; info[10] = vo;
			info[11] = rgb; info[12] = ia0; info[13] = ib0;
			return axis + "|" + sign + "|" + Math.round(plane * 4096) + "|" + (int) gua + (int) gub + (int) gva + (int) gvb
					+ "|" + Math.round(uo * 4096) + "|" + Math.round(vo * 4096) + "|" + rgb;
		}

		private static boolean unitAxis(float a, float b) {
			return (Math.abs(Math.abs(a) - 1) < 1e-4f && Math.abs(b) < 1e-4f)
					|| (Math.abs(Math.abs(b) - 1) < 1e-4f && Math.abs(a) < 1e-4f);
		}

		/**
		 * Merges vertices that touch: same position, normal, UV and bone. Faces of neighbouring blocks then share
		 * their edge vertices and form one connected surface instead of a separate island per face. Vertex colours of
		 * merged vertices are averaged (so a biome border blends smoothly, like the game's biome blending).
		 */
		public void weld() {
			weld(false);
		}

		/**
		 * @param acrossEdges also merge vertices whose normals differ (faces meeting at an angle). Their normals are
		 *                    averaged; meant for flat shading in the target application. Vertices still need the
		 *                    same UV to merge, because glTF stores one UV per vertex.
		 */
		public void weld(boolean acrossEdges) {
			if (welded || vertices == 0) return;
			welded = true;
			java.util.HashMap<VKey, Integer> map = new java.util.HashMap<>(vertices);
			FloatArrayList nPos = new FloatArrayList(), nNrm = new FloatArrayList(), nUv = new FloatArrayList();
			IntArrayList nJoint = new IntArrayList();
			java.util.ArrayList<long[]> colorSums = new java.util.ArrayList<>();
			int[] remap = new int[vertices];
			boolean skinned = joint.size() == vertices;
			for (int v = 0; v < vertices; v++) {
				float x = pos.getFloat(v * 3), y = pos.getFloat(v * 3 + 1), z = pos.getFloat(v * 3 + 2);
				float nx = nrm.getFloat(v * 3), ny = nrm.getFloat(v * 3 + 1), nz = nrm.getFloat(v * 3 + 2);
				float u = uv.getFloat(v * 2), w = uv.getFloat(v * 2 + 1);
				int j = skinned ? joint.getInt(v) : -1;
				int nKey = acrossEdges ? 0
						: (int) (q(nx, 256) & 1023) << 20 | (int) (q(ny, 256) & 1023) << 10 | (int) (q(nz, 256) & 1023);
				VKey key = new VKey(q(x, 1 << 14), q(y, 1 << 14), q(z, 1 << 14), q(u, 1 << 16), q(w, 1 << 16), nKey, j);
				Integer existing = map.get(key);
				int c = color.getInt(v);
				if (existing == null) {
					int ni = nPos.size() / 3;
					map.put(key, ni);
					nPos.add(x);
					nPos.add(y);
					nPos.add(z);
					nNrm.add(nx);
					nNrm.add(ny);
					nNrm.add(nz);
					nUv.add(u);
					nUv.add(w);
					if (skinned) nJoint.add(j);
					colorSums.add(new long[]{c >> 16 & 255, c >> 8 & 255, c & 255, 1});
					remap[v] = ni;
				} else {
					long[] sum = colorSums.get(existing);
					sum[0] += c >> 16 & 255;
					sum[1] += c >> 8 & 255;
					sum[2] += c & 255;
					sum[3]++;
					remap[v] = existing;
					if (acrossEdges) {
						nNrm.set(existing * 3, nNrm.getFloat(existing * 3) + nx);
						nNrm.set(existing * 3 + 1, nNrm.getFloat(existing * 3 + 1) + ny);
						nNrm.set(existing * 3 + 2, nNrm.getFloat(existing * 3 + 2) + nz);
					}
				}
			}
			for (int i = 0; i < idx.size(); i++) idx.set(i, remap[idx.getInt(i)]);
			pos.clear();
			nrm.clear();
			uv.clear();
			joint.clear();
			color.clear();
			copy(nPos, pos);
			if (acrossEdges) {
				for (int i = 0; i < nNrm.size(); i += 3) {
					float nx = nNrm.getFloat(i), ny = nNrm.getFloat(i + 1), nz = nNrm.getFloat(i + 2);
					float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
					if (len < 1e-6f) {
						nx = 0;
						ny = 1;
						nz = 0;
						len = 1;
					}
					nNrm.set(i, nx / len);
					nNrm.set(i + 1, ny / len);
					nNrm.set(i + 2, nz / len);
				}
			}
			copy(nNrm, nrm);
			copy(nUv, uv);
			for (int i = 0; i < nJoint.size(); i++) joint.add(nJoint.getInt(i));
			for (long[] sum : colorSums) {
				int r = (int) Math.round((double) sum[0] / sum[3]);
				int g = (int) Math.round((double) sum[1] / sum[3]);
				int b = (int) Math.round((double) sum[2] / sum[3]);
				color.add(r << 16 | g << 8 | b);
			}
			vertices = colorSums.size();
		}

		private boolean welded;

		private static long q(float f, int scale) {
			return Math.round((double) f * scale);
		}

		private static void copy(FloatArrayList from, FloatArrayList to) {
			for (int i = 0; i < from.size(); i++) to.add(from.getFloat(i));
		}

		private record VKey(long x, long y, long z, long u, long v, int n, int j) {
		}
	}

	/**
	 * Shifts a quad's UVs by whole texture repeats (invisible with REPEAT wrapping) so the texture phase is anchored to
	 * the world rather than to the quad. Coplanar neighbouring faces with the same texture then have identical UVs at
	 * their shared corners, which lets {@link Prim#weld} join them.
	 */
	public static void alignUv(float[] p, float[] t) {
		Vector3f e1 = new Vector3f(p[3] - p[0], p[4] - p[1], p[5] - p[2]);
		Vector3f e2 = new Vector3f(p[9] - p[0], p[10] - p[1], p[11] - p[2]);
		float a = e1.dot(e1), b = e1.dot(e2), c = e2.dot(e2);
		float det = a * c - b * b;
		if (Math.abs(det) < 1e-12f) return;
		for (int k = 0; k < 2; k++) {
			float d1 = t[2 + k] - t[k];
			float d2 = t[6 + k] - t[k];
			// gradient g = alpha*e1 + beta*e2 with g.e1 = d1, g.e2 = d2
			float alpha = (d1 * c - d2 * b) / det;
			float beta = (d2 * a - d1 * b) / det;
			Vector3f g = new Vector3f(e1).mul(alpha).add(new Vector3f(e2).mul(beta));
			// texture coordinate this face's mapping gives at the world origin
			double atOrigin = t[k] - (g.x * (double) p[0] + g.y * (double) p[1] + g.z * (double) p[2]);
			double shift = -Math.floor(atOrigin + 1e-4);
			if (shift == 0) continue;
			for (int i = 0; i < 4; i++) t[i * 2 + k] += (float) shift;
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

		/**
		 * Where the group sits in the export: entity position and facing. Everything inside the group is stored
		 * relative to this after {@link #localize}, so armatures start at the origin with no rotation.
		 */
		public final Matrix4f placement = new Matrix4f();

		public Group(String name) {
			this.name = name;
		}

		/**
		 * Moves all geometry and bones from export space into the group's local space and makes every bone's rest
		 * orientation axis-aligned (bones keep only their pivot position). The current pose stays baked into the
		 * vertices, so the model looks identical but the armature is clean: origin at 0, no rotations.
		 */
		public void localize(Matrix4f newPlacement) {
			placement.set(newPlacement);
			Matrix4f inv = new Matrix4f(newPlacement).invert();
			Matrix3f normalInv = new Matrix3f(inv).invert().transpose();
			for (Prim p : statics.values()) p.transform(inv, normalInv);
			for (Rig r : rigs) {
				for (Prim p : r.prims.values()) p.transform(inv, normalInv);
				for (int i = 0; i < r.joints.size(); i++) {
					Joint j = r.joints.get(i);
					Vector3f pivot = new Matrix4f(inv).mul(j.global()).getTranslation(new Vector3f());
					r.joints.set(i, new Joint(j.name(), j.parent(), new Matrix4f().translation(pivot)));
				}
			}
		}

		public Prim stat(MaterialKey key) {
			return statics.computeIfAbsent(key, k -> new Prim());
		}

		public boolean isEmpty() {
			return rigs.isEmpty() && statics.values().stream().allMatch(p -> p.quads == 0);
		}
	}
}
