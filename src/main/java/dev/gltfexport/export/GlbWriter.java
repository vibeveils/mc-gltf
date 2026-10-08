package dev.gltfexport.export;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.gltfexport.ExportSettings;
import dev.gltfexport.export.SceneData.Group;
import dev.gltfexport.export.SceneData.Joint;
import dev.gltfexport.export.SceneData.MaterialKey;
import dev.gltfexport.export.SceneData.Prim;
import dev.gltfexport.export.SceneData.Rig;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Serialises {@link SceneData} to a single binary glTF 2.0 (.glb) with embedded PNG textures. */
public final class GlbWriter {
	private static final int FLOAT = 5126, UINT = 5125, USHORT = 5123;
	private static final int ARRAY_BUFFER = 34962, ELEMENT_ARRAY_BUFFER = 34963;
	private static final int NEAREST = 9728, REPEAT = 10497;

	private final SceneData scene;
	private final TextureCache textures;
	private final ExportSettings settings;

	/** Binary chunk is streamed to a temp file so output size is not bounded by Java array limits. */
	private Path binFile;
	private OutputStream bin;
	private long binSize;
	private final JsonArray bufferViews = new JsonArray();
	private final JsonArray accessors = new JsonArray();
	private final JsonArray meshes = new JsonArray();
	private final JsonArray nodes = new JsonArray();
	private final JsonArray materials = new JsonArray();
	private final JsonArray gltfTextures = new JsonArray();
	private final JsonArray images = new JsonArray();
	private final JsonArray skins = new JsonArray();
	private final Map<MaterialKey, Integer> materialIndex = new HashMap<>();
	private final Map<String, int[]> imageIndex = new HashMap<>(); // key -> {textureIndex, alphaClass}

	public GlbWriter(SceneData scene, TextureCache textures, ExportSettings settings) {
		this.scene = scene;
		this.textures = textures;
		this.settings = settings;
	}

	/** Writes the scene to {@code out} as a .glb. Returns the file size in bytes. */
	public long write(String title, Path out) throws IOException {
		binFile = Files.createTempFile(out.getParent(), ".gltfexport", ".bin");
		try {
			bin = new BufferedOutputStream(Files.newOutputStream(binFile), 1 << 20);
			JsonObject gltf = build(title);
			bin.close();
			Gson gson = new GsonBuilder().disableHtmlEscaping().create();
			return assemble(gson.toJson(gltf).getBytes(StandardCharsets.UTF_8), out);
		} finally {
			try {
				if (bin != null) bin.close();
			} catch (IOException ignored) {
			}
			Files.deleteIfExists(binFile);
		}
	}

	private JsonObject build(String title) throws IOException {
		JsonArray rootChildren = new JsonArray();

		JsonArray blockNodes = new JsonArray();
		for (Map.Entry<SceneData.Tile, Map<MaterialKey, Prim>> tile : scene.blockTiles.entrySet()) {
			String name = "blocks_" + tile.getKey().x() + "_" + tile.getKey().z();
			int mesh = addMesh(name, tile.getValue(), false);
			if (mesh < 0) continue;
			JsonObject n = new JsonObject();
			n.addProperty("name", name);
			n.addProperty("mesh", mesh);
			blockNodes.add(addNode(n));
		}
		if (!blockNodes.isEmpty()) {
			JsonObject n = new JsonObject();
			n.addProperty("name", "blocks");
			n.add("children", blockNodes);
			rootChildren.add(addNode(n));
		}

		for (Group group : scene.groups) {
			JsonArray children = new JsonArray();
			for (Rig rig : group.rigs) addRig(rig, children);
			int staticMesh = addMesh(group.name + "_static", group.statics, false);
			if (staticMesh >= 0) {
				JsonObject n = new JsonObject();
				n.addProperty("name", group.name + "_static");
				n.addProperty("mesh", staticMesh);
				children.add(addNode(n));
			}
			if (children.isEmpty()) continue;
			JsonObject g = new JsonObject();
			g.addProperty("name", group.name);
			putTrs(g, group.placement);
			g.add("children", children);
			rootChildren.add(addNode(g));
		}

		JsonObject root = new JsonObject();
		root.addProperty("name", "minecraft_export");
		if (!rootChildren.isEmpty()) root.add("children", rootChildren);
		int rootIndex = addNode(root);

		JsonObject gltf = new JsonObject();
		JsonObject asset = new JsonObject();
		asset.addProperty("version", "2.0");
		asset.addProperty("generator", "gltfexport Fabric mod");
		asset.addProperty("copyright", "Textures are property of their respective owners (Mojang / resource pack authors)");
		JsonObject extras = new JsonObject();
		extras.addProperty("title", title);
		asset.add("extras", extras);
		gltf.add("asset", asset);
		if (settings.unlit) {
			JsonArray used = new JsonArray();
			used.add("KHR_materials_unlit");
			gltf.add("extensionsUsed", used);
		}
		gltf.addProperty("scene", 0);
		JsonArray scenes = new JsonArray();
		JsonObject sc = new JsonObject();
		sc.addProperty("name", title);
		JsonArray sn = new JsonArray();
		sn.add(rootIndex);
		sc.add("nodes", sn);
		scenes.add(sc);
		gltf.add("scenes", scenes);
		gltf.add("nodes", nodes);
		if (!meshes.isEmpty()) gltf.add("meshes", meshes);
		if (!skins.isEmpty()) gltf.add("skins", skins);
		if (!materials.isEmpty()) {
			gltf.add("materials", materials);
			gltf.add("textures", gltfTextures);
			gltf.add("images", images);
			JsonArray samplers = new JsonArray();
			JsonObject s = new JsonObject();
			s.addProperty("magFilter", NEAREST);
			s.addProperty("minFilter", NEAREST);
			s.addProperty("wrapS", REPEAT);
			s.addProperty("wrapT", REPEAT);
			samplers.add(s);
			gltf.add("samplers", samplers);
		}
		if (!accessors.isEmpty()) gltf.add("accessors", accessors);
		if (!bufferViews.isEmpty()) gltf.add("bufferViews", bufferViews);
		if (binSize > 0) {
			pad();
			JsonArray buffers = new JsonArray();
			JsonObject b = new JsonObject();
			b.addProperty("byteLength", binSize);
			buffers.add(b);
			gltf.add("buffers", buffers);
		}

		return gltf;
	}

	// ------------------------------------------------------------------------------------------- rigs

	private void addRig(Rig rig, JsonArray groupChildren) throws IOException {
		if (rig.joints.isEmpty() || rig.isEmpty()) return;
		if (!settings.rigEntities) {
			// vertices are already in export space: emit as a plain static mesh
			int mesh = addMesh(rig.name, rig.prims, false);
			if (mesh < 0) return;
			JsonObject n = new JsonObject();
			n.addProperty("name", rig.name);
			n.addProperty("mesh", mesh);
			groupChildren.add(addNode(n));
			return;
		}
		int[] nodeOf = new int[rig.joints.size()];
		List<List<Integer>> kids = new ArrayList<>();
		for (int i = 0; i < rig.joints.size(); i++) kids.add(new ArrayList<>());
		for (int i = 0; i < rig.joints.size(); i++) {
			int parent = rig.joints.get(i).parent();
			if (parent >= 0) kids.get(parent).add(i);
		}
		// create joint nodes first (children indices are patched in afterwards)
		JsonObject[] jointNodes = new JsonObject[rig.joints.size()];
		for (int i = 0; i < rig.joints.size(); i++) {
			Joint j = rig.joints.get(i);
			Matrix4f local = j.parent() < 0 ? new Matrix4f(j.global())
					: new Matrix4f(rig.joints.get(j.parent()).global()).invert().mul(j.global());
			JsonObject n = new JsonObject();
			n.addProperty("name", j.name());
			putTrs(n, local);
			jointNodes[i] = n;
			nodeOf[i] = addNode(n);
		}
		for (int i = 0; i < rig.joints.size(); i++) {
			if (kids.get(i).isEmpty()) continue;
			JsonArray c = new JsonArray();
			for (int k : kids.get(i)) c.add(nodeOf[k]);
			jointNodes[i].add("children", c);
		}

		float[] ibm = new float[16 * rig.joints.size()];
		for (int i = 0; i < rig.joints.size(); i++) {
			new Matrix4f(rig.joints.get(i).global()).invert().get(ibm, i * 16);
		}
		int ibmAccessor = accessor(writeFloats(ibm, 0), FLOAT, rig.joints.size(), "MAT4", null, null);

		JsonObject skin = new JsonObject();
		skin.addProperty("name", rig.name);
		skin.addProperty("inverseBindMatrices", ibmAccessor);
		JsonArray joints = new JsonArray();
		for (int n : nodeOf) joints.add(n);
		skin.add("joints", joints);
		// armature object: identity transform at the group origin, holding the bone hierarchy
		JsonArray topJoints = new JsonArray();
		for (int i = 0; i < rig.joints.size(); i++) {
			if (rig.joints.get(i).parent() < 0) topJoints.add(nodeOf[i]);
		}
		JsonObject armature = new JsonObject();
		armature.addProperty("name", rig.name + "_armature");
		armature.add("children", topJoints);
		int armatureNode = addNode(armature);
		groupChildren.add(armatureNode);
		skin.addProperty("skeleton", armatureNode);
		int skinIndex = skins.size();
		skins.add(skin);

		int mesh = addMesh(rig.name, rig.prims, true);
		if (mesh < 0) return;
		JsonObject n = new JsonObject();
		n.addProperty("name", rig.name + "_mesh");
		n.addProperty("mesh", mesh);
		n.addProperty("skin", skinIndex);
		groupChildren.add(addNode(n));
	}

	/** Decomposes an affine matrix into glTF translation / rotation / scale (no shear assumed). */
	private static void putTrs(JsonObject node, Matrix4f m) {
		Vector3f c0 = new Vector3f(m.m00(), m.m01(), m.m02());
		Vector3f c1 = new Vector3f(m.m10(), m.m11(), m.m12());
		Vector3f c2 = new Vector3f(m.m20(), m.m21(), m.m22());
		float sx = c0.length(), sy = c1.length(), sz = c2.length();
		if (new Matrix3f(m).determinant() < 0) sx = -sx;
		Quaternionf q = new Quaternionf();
		if (Math.abs(sx) > 1e-8f && sy > 1e-8f && sz > 1e-8f) {
			Matrix3f r = new Matrix3f(
					c0.x / sx, c0.y / sx, c0.z / sx,
					c1.x / sy, c1.y / sy, c1.z / sy,
					c2.x / sz, c2.y / sz, c2.z / sz);
			q.setFromNormalized(r).normalize();
		}
		if (m.m30() != 0 || m.m31() != 0 || m.m32() != 0) node.add("translation", arr(m.m30(), m.m31(), m.m32()));
		if (Math.abs(q.w - 1f) > 1e-7f) node.add("rotation", arr(q.x, q.y, q.z, q.w));
		if (Math.abs(sx - 1) > 1e-6f || Math.abs(sy - 1) > 1e-6f || Math.abs(sz - 1) > 1e-6f) node.add("scale", arr(sx, sy, sz));
	}

	// ------------------------------------------------------------------------------------------- meshes

	private int addMesh(String name, Map<MaterialKey, Prim> prims, boolean skinned) throws IOException {
		JsonArray primitives = new JsonArray();
		for (Map.Entry<MaterialKey, Prim> e : prims.entrySet()) {
			Prim p = e.getValue();
			if (p.quads == 0) continue;
			p.weld(settings.weldEdges);
			JsonObject attributes = new JsonObject();
			float[] pos = p.pos.toFloatArray();
			float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
			float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
			for (int i = 0; i < pos.length; i++) {
				min[i % 3] = Math.min(min[i % 3], pos[i]);
				max[i % 3] = Math.max(max[i % 3], pos[i]);
			}
			attributes.addProperty("POSITION", accessor(writeFloats(pos, ARRAY_BUFFER), FLOAT, p.vertices, "VEC3", min, max));
			attributes.addProperty("NORMAL", accessor(writeFloats(p.nrm.toFloatArray(), ARRAY_BUFFER), FLOAT, p.vertices, "VEC3", null, null));
			attributes.addProperty("TEXCOORD_0", accessor(writeFloats(p.uv.toFloatArray(), ARRAY_BUFFER), FLOAT, p.vertices, "VEC2", null, null));
			if (settings.tints && p.tinted) {
				attributes.addProperty("COLOR_0", colorAccessor(p));
			}
			if (skinned) {
				short[] joints = new short[p.vertices * 4];
				float[] weights = new float[p.vertices * 4];
				for (int v = 0; v < p.vertices; v++) {
					joints[v * 4] = (short) (v < p.joint.size() ? p.joint.getInt(v) : 0);
					weights[v * 4] = 1f;
				}
				attributes.addProperty("JOINTS_0", accessor(writeShorts(joints, ARRAY_BUFFER), USHORT, p.vertices, "VEC4", null, null));
				attributes.addProperty("WEIGHTS_0", accessor(writeFloats(weights, ARRAY_BUFFER), FLOAT, p.vertices, "VEC4", null, null));
			}
			JsonObject prim = new JsonObject();
			prim.add("attributes", attributes);
			prim.addProperty("indices", accessor(writeInts(p.idx, ELEMENT_ARRAY_BUFFER), UINT, p.idx.size(), "SCALAR", null, null));
			prim.addProperty("material", material(e.getKey()));
			prim.addProperty("mode", 4);
			primitives.add(prim);
		}
		if (primitives.isEmpty()) return -1;
		JsonObject mesh = new JsonObject();
		mesh.addProperty("name", name);
		mesh.add("primitives", primitives);
		meshes.add(mesh);
		return meshes.size() - 1;
	}

	// ------------------------------------------------------------------------------------------- materials

	private int material(MaterialKey key) throws IOException {
		Integer existing = materialIndex.get(key);
		if (existing != null) return existing;

		int[] tex = texture(key.texture());
		int alphaClass = tex[1];

		JsonObject m = new JsonObject();
		m.addProperty("name", shortName(key.texture()) + (key.translucent() ? "_translucent" : "")
				+ (key.emissive() ? "_emissive" : "") + (key.doubleSided() ? "" : "_culled"));
		JsonObject pbr = new JsonObject();
		JsonObject base = new JsonObject();
		base.addProperty("index", tex[0]);
		pbr.add("baseColorTexture", base);
		pbr.addProperty("metallicFactor", 0);
		pbr.addProperty("roughnessFactor", 1);
		m.add("pbrMetallicRoughness", pbr);
		if (alphaClass == 2 && key.translucent()) {
			m.addProperty("alphaMode", "BLEND");
		} else if (alphaClass > 0) {
			m.addProperty("alphaMode", "MASK");
			m.addProperty("alphaCutoff", 0.1);
		}
		if (key.doubleSided()) m.addProperty("doubleSided", true);
		if (key.emissive()) {
			JsonObject em = new JsonObject();
			em.addProperty("index", tex[0]);
			m.add("emissiveTexture", em);
			m.add("emissiveFactor", arr(1, 1, 1));
		}
		if (settings.unlit) {
			JsonObject ext = new JsonObject();
			ext.add("KHR_materials_unlit", new JsonObject());
			m.add("extensions", ext);
		}
		materials.add(m);
		int index = materials.size() - 1;
		materialIndex.put(key, index);
		return index;
	}

	/** One image per source texture; tints live in vertex colours. */
	private int[] texture(String texKey) throws IOException {
		String k = texKey;
		int[] existing = imageIndex.get(k);
		if (existing != null) return existing;
		BufferedImage img = textures.loaded(texKey);
		int alphaClass = TextureCache.alphaClass(img);
		byte[] png = TextureCache.png(img);
		int view = writeBytes(png, -1);
		JsonObject image = new JsonObject();
		image.addProperty("name", shortName(texKey));
		image.addProperty("bufferView", view);
		image.addProperty("mimeType", "image/png");
		images.add(image);
		JsonObject t = new JsonObject();
		t.addProperty("sampler", 0);
		t.addProperty("source", images.size() - 1);
		gltfTextures.add(t);
		int[] result = {gltfTextures.size() - 1, alphaClass};
		imageIndex.put(k, result);
		return result;
	}

	private static String shortName(String texKey) {
		String s = texKey;
		int colon = s.indexOf(':');
		if (colon >= 0) s = s.substring(colon + 1);
		s = s.replace("#anim", "").replace("minecraft:", "").replace("textures/", "").replace(".png", "");
		return s.replaceAll("[^A-Za-z0-9_./-]", "_");
	}

	// ------------------------------------------------------------------------------------------- buffers

	/** sRGB -> linear lookup: glTF vertex colours are linear and multiplied with the (linearised) texture. */
	private static final int[] SRGB_TO_LINEAR_U8 = new int[256];

	static {
		for (int i = 0; i < 256; i++) {
			double c = i / 255.0;
			double lin = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
			SRGB_TO_LINEAR_U8[i] = (int) Math.round(lin * 255.0);
		}
	}

	/** COLOR_0 as normalised unsigned bytes, VEC4 (keeps the required 4-byte vertex attribute alignment). */
	private int colorAccessor(Prim p) throws IOException {
		byte[] data = new byte[p.vertices * 4];
		for (int v = 0; v < p.vertices; v++) {
			int c = p.color.getInt(v);
			data[v * 4] = (byte) SRGB_TO_LINEAR_U8[c >> 16 & 255];
			data[v * 4 + 1] = (byte) SRGB_TO_LINEAR_U8[c >> 8 & 255];
			data[v * 4 + 2] = (byte) SRGB_TO_LINEAR_U8[c & 255];
			data[v * 4 + 3] = (byte) 255;
		}
		JsonObject a = new JsonObject();
		a.addProperty("bufferView", writeBytes(data, ARRAY_BUFFER));
		a.addProperty("componentType", 5121); // UNSIGNED_BYTE
		a.addProperty("normalized", true);
		a.addProperty("count", p.vertices);
		a.addProperty("type", "VEC4");
		accessors.add(a);
		return accessors.size() - 1;
	}

	private int addNode(JsonObject node) {
		nodes.add(node);
		return nodes.size() - 1;
	}

	private int accessor(int bufferView, int componentType, int count, String type, float[] min, float[] max) {
		JsonObject a = new JsonObject();
		a.addProperty("bufferView", bufferView);
		a.addProperty("componentType", componentType);
		a.addProperty("count", count);
		a.addProperty("type", type);
		if (min != null) a.add("min", arr(min));
		if (max != null) a.add("max", arr(max));
		accessors.add(a);
		return accessors.size() - 1;
	}

	private int writeFloats(float[] data, int target) throws IOException {
		ByteBuffer b = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (float f : data) b.putFloat(f);
		return writeBytes(b.array(), target);
	}

	private int writeShorts(short[] data, int target) throws IOException {
		ByteBuffer b = ByteBuffer.allocate(data.length * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (short s : data) b.putShort(s);
		return writeBytes(b.array(), target);
	}

	private int writeInts(IntArrayList data, int target) throws IOException {
		ByteBuffer b = ByteBuffer.allocate(data.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < data.size(); i++) b.putInt(data.getInt(i));
		return writeBytes(b.array(), target);
	}

	private void pad() throws IOException {
		while (binSize % 4 != 0) {
			bin.write(0);
			binSize++;
		}
	}

	private int writeBytes(byte[] data, int target) throws IOException {
		pad();
		long offset = binSize;
		bin.write(data, 0, data.length);
		binSize += data.length;
		JsonObject v = new JsonObject();
		v.addProperty("buffer", 0);
		v.addProperty("byteOffset", offset);
		v.addProperty("byteLength", data.length);
		if (target > 0) v.addProperty("target", target);
		bufferViews.add(v);
		return bufferViews.size() - 1;
	}

	private long assemble(byte[] json, Path out) throws IOException {
		long jsonLen = pad4(json.length);
		long binLen = pad4(binSize);
		long total = 12 + 8 + jsonLen + (binSize > 0 ? 8 + binLen : 0);
		if (total > 0xFFFFFFFFL) {
			throw new IOException("This part would be " + (total >> 20) + " MB; a .glb can hold at most 4 GB. "
					+ "Lower the part size with /gltf set partSize <blocks>.");
		}
		Path tmp = out.resolveSibling(out.getFileName() + ".part");
		try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20)) {
			ByteBuffer h = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
			h.putInt(0x46546C67); // "glTF"
			h.putInt(2);
			h.putInt((int) total);
			h.putInt((int) jsonLen);
			h.putInt(0x4E4F534A); // "JSON"
			o.write(h.array());
			o.write(json);
			for (long i = json.length; i < jsonLen; i++) o.write(' ');
			if (binSize > 0) {
				ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
				b.putInt((int) binLen);
				b.putInt(0x004E4942); // "BIN\0"
				o.write(b.array());
				Files.copy(binFile, o);
				for (long i = binSize; i < binLen; i++) o.write(0);
			}
		}
		Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		return total;
	}

	private static long pad4(long n) {
		return (n + 3) & ~3L;
	}

	private static JsonArray arr(float... values) {
		JsonArray a = new JsonArray();
		for (float v : values) a.add(v);
		return a;
	}
}
