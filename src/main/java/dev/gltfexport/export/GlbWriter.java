package dev.gltfexport.export;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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

	private final ByteArrayOutputStream bin = new ByteArrayOutputStream();
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

	public byte[] write(String title) throws IOException {
		JsonArray rootChildren = new JsonArray();

		int blocksMesh = addMesh("blocks", scene.blocks, false);
		if (blocksMesh >= 0) {
			JsonObject n = new JsonObject();
			n.addProperty("name", "blocks");
			n.addProperty("mesh", blocksMesh);
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
		if (bin.size() > 0) {
			JsonArray buffers = new JsonArray();
			JsonObject b = new JsonObject();
			b.addProperty("byteLength", bin.size());
			buffers.add(b);
			gltf.add("buffers", buffers);
		}

		Gson gson = new GsonBuilder().disableHtmlEscaping().create();
		return assemble(gson.toJson(gltf).getBytes(StandardCharsets.UTF_8), bin.toByteArray());
	}

	// ------------------------------------------------------------------------------------------- rigs

	private void addRig(Rig rig, JsonArray groupChildren) throws IOException {
		if (rig.joints.isEmpty() || rig.isEmpty()) return;
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
		int rootJoint = -1;
		for (int i = 0; i < rig.joints.size(); i++) {
			if (rig.joints.get(i).parent() < 0) {
				if (rootJoint < 0) rootJoint = nodeOf[i];
				groupChildren.add(nodeOf[i]);
			}
		}
		if (rootJoint >= 0) skin.addProperty("skeleton", rootJoint);
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

		int[] tex = texture(key.texture(), key.tint());
		int alphaClass = tex[1];

		JsonObject m = new JsonObject();
		m.addProperty("name", shortName(key.texture()) + (key.tint() != 0xFFFFFF ? String.format("_%06x", key.tint()) : ""));
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

	private int[] texture(String texKey, int tint) throws IOException {
		String k = texKey + "|" + Integer.toHexString(tint & 0xFFFFFF);
		int[] existing = imageIndex.get(k);
		if (existing != null) return existing;
		BufferedImage img = TextureCache.tinted(textures.loaded(texKey), tint);
		int alphaClass = TextureCache.alphaClass(img);
		byte[] png = TextureCache.png(img);
		int view = writeBytes(png, -1);
		JsonObject image = new JsonObject();
		image.addProperty("name", shortName(texKey) + ((tint & 0xFFFFFF) != 0xFFFFFF ? String.format("_%06x", tint & 0xFFFFFF) : ""));
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

	private int writeFloats(float[] data, int target) {
		ByteBuffer b = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (float f : data) b.putFloat(f);
		return writeBytes(b.array(), target);
	}

	private int writeShorts(short[] data, int target) {
		ByteBuffer b = ByteBuffer.allocate(data.length * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (short s : data) b.putShort(s);
		return writeBytes(b.array(), target);
	}

	private int writeInts(IntArrayList data, int target) {
		ByteBuffer b = ByteBuffer.allocate(data.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < data.size(); i++) b.putInt(data.getInt(i));
		return writeBytes(b.array(), target);
	}

	private int writeBytes(byte[] data, int target) {
		while (bin.size() % 4 != 0) bin.write(0);
		int offset = bin.size();
		bin.write(data, 0, data.length);
		JsonObject v = new JsonObject();
		v.addProperty("buffer", 0);
		v.addProperty("byteOffset", offset);
		v.addProperty("byteLength", data.length);
		if (target > 0) v.addProperty("target", target);
		bufferViews.add(v);
		return bufferViews.size() - 1;
	}

	private static byte[] assemble(byte[] json, byte[] binary) {
		int jsonLen = pad4(json.length);
		int binLen = pad4(binary.length);
		int total = 12 + 8 + jsonLen + (binary.length > 0 ? 8 + binLen : 0);
		ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(0x46546C67); // "glTF"
		b.putInt(2);
		b.putInt(total);
		b.putInt(jsonLen);
		b.putInt(0x4E4F534A); // "JSON"
		b.put(json);
		for (int i = json.length; i < jsonLen; i++) b.put((byte) ' ');
		if (binary.length > 0) {
			b.putInt(binLen);
			b.putInt(0x004E4942); // "BIN\0"
			b.put(binary);
			for (int i = binary.length; i < binLen; i++) b.put((byte) 0);
		}
		return b.array();
	}

	private static int pad4(int n) {
		return (n + 3) & ~3;
	}

	private static JsonArray arr(float... values) {
		JsonArray a = new JsonArray();
		for (float v : values) a.add(v);
		return a;
	}
}
