package dev.gltfexport.export;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.gltfexport.GltfExportClient;
import dev.gltfexport.export.SceneData.Group;
import dev.gltfexport.export.SceneData.MaterialKey;
import dev.gltfexport.export.SceneData.Rig;
import dev.gltfexport.util.Refl;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

/**
 * A stand-in for vanilla's {@link SubmitNodeCollector}. Entity and block-entity renderers "submit" what they want
 * drawn (models, model parts, items, block models, custom geometry); instead of queueing those for the GPU this
 * records them into a {@link Group}.
 *
 * <p>It is a dynamic proxy so it implements every submit method of the current game version without depending on
 * their exact signatures; arguments are recognised by type.
 */
public final class SubmitCapture implements InvocationHandler {
	private final Group group;
	private final TextureCache textures;
	private final BlockMesher blocks;
	private final ModelRigger rigger = new ModelRigger();
	private final CaptureConsumer capture = new CaptureConsumer();
	private final Set<String> ignored = new HashSet<>();
	private final Object proxy;

	/** Skin texture key for player heads / players, used instead of a default or unreadable skin texture. */
	private final String skinOverride;

	public SubmitCapture(Group group, TextureCache textures, BlockMesher blocks) {
		this(group, textures, blocks, null);
	}

	public SubmitCapture(Group group, TextureCache textures, BlockMesher blocks, String skinOverride) {
		this.skinOverride = skinOverride;
		this.group = group;
		this.textures = textures;
		this.blocks = blocks;
		this.proxy = Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(),
				new Class<?>[]{SubmitNodeCollector.class}, this);
	}

	public Object collector() {
		return proxy;
	}

	@Override
	public Object invoke(Object self, Method method, Object[] args) throws Throwable {
		if (method.getDeclaringClass() == Object.class) {
			return switch (method.getName()) {
				case "equals" -> self == args[0];
				case "hashCode" -> System.identityHashCode(self);
				case "toString" -> "gltfexport$SubmitCapture";
				default -> null;
			};
		}
		if (args == null) args = new Object[0];

		// Convenience overloads delegate to the full methods; let them, so we only interpret the full ones.
		if (method.isDefault()) {
			try {
				return InvocationHandler.invokeDefault(self, method, args);
			} catch (Throwable t) {
				return fallbackReturn(method, self);
			}
		}

		String name = method.getName();
		if (dev.gltfexport.GltfExportClient.SETTINGS.verboseLog) {
			StringBuilder sb = new StringBuilder();
			for (Object a : args) sb.append(a == null ? "null" : a.getClass().getSimpleName()).append(' ');
			GltfExportClient.LOGGER.info("[gltfexport] {}({})", name, sb.toString().trim());
		}
		try {
			if (name.startsWith("submit")) handleSubmit(name, args);
		} catch (Throwable t) {
			GltfExportClient.LOGGER.warn("Failed to capture {}: {}", name, t.toString());
		}
		return fallbackReturn(method, self);
	}

	private Object fallbackReturn(Method method, Object self) {
		Class<?> rt = method.getReturnType();
		if (rt == void.class) return null;
		if (rt.isInstance(self)) return self; // order(int) and other builders
		return Refl.defaultValue(rt);
	}

	// --------------------------------------------------------------------------------------------- dispatch

	private void handleSubmit(String name, Object[] args) {
		PoseStack poseStack = find(args, PoseStack.class);
		Matrix4f pose = poseStack != null ? new Matrix4f(poseStack.last().pose()) : new Matrix4f();
		Object renderType = findRenderType(args);
		TextureAtlasSprite sprite = find(args, TextureAtlasSprite.class);
		int[] tintLayers = find(args, int[].class);
		int tint = tintArgument(args);

		if (renderType != null && skipRenderType(renderType)) return;

		Model<?> model = find(args, Model.class);
		if (model != null) {
			submitModel(model, args, renderType, sprite, tint, pose);
			return;
		}
		ModelPart part = find(args, ModelPart.class);
		if (part != null) {
			submitPart(part, part.getClass().getSimpleName(), "part", renderType, sprite, tint, pose);
			return;
		}

		// Block models (falling blocks, carried blocks, minecart contents, special block renderers)
		for (Object a : args) {
			if (a instanceof List<?> list && !list.isEmpty()) {
				Object first = list.get(0);
				if (first instanceof BlockStateModelPart) {
					@SuppressWarnings("unchecked") List<BlockStateModelPart> parts = (List<BlockStateModelPart>) list;
					blocks.meshParts(null, null, parts, pose, false, tintLayers != null ? tintLayers : new int[0], group.statics);
					return;
				}
				if (first instanceof BakedQuad) {
					addQuads(list, pose, tintLayers);
					return;
				}
			}
		}
		BlockState state = find(args, BlockState.class);
		if (state == null) {
			// MovingBlockRenderState and similar wrappers
			for (Object a : args) {
				if (a == null || a instanceof PoseStack) continue;
				Object bs = Refl.get(a, "blockState");
				if (bs instanceof BlockState s) {
					state = s;
					break;
				}
			}
		}
		if (state != null) {
			blocks.meshModel(state, null, pose, false, tintLayers, group.statics);
			return;
		}

		// Items: an ItemQuads-like holder exposing all()
		for (Object a : args) {
			if (a == null || a instanceof PoseStack || a.getClass().isArray()) continue;
			if (a.getClass().getName().contains("ItemQuads") || a.getClass().getSimpleName().endsWith("Quads")) {
				Object all = Refl.invokeNoArg(a, "all");
				if (all instanceof List<?> list) {
					addQuads(list, pose, tintLayers);
					return;
				}
			}
		}

		// Custom geometry: a callback that writes vertices into a VertexConsumer
		for (Object a : args) {
			if (a == null || !name.contains("Custom")) continue;
			Method callback = geometryCallback(a.getClass());
			if (callback != null) {
				submitCustomGeometry(a, callback, poseStack, renderType, tint);
				return;
			}
		}

		if (ignored.add(name)) GltfExportClient.LOGGER.debug("Not exported: {}", name);
	}

	// --------------------------------------------------------------------------------------------- models

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void submitModel(Model model, Object[] args, Object renderType, TextureAtlasSprite sprite, int tint, Matrix4f pose) {
		// submitModel(model, state, poseStack, ...): the state is the argument right after the model. It can be a
		// boxed number (a chest's lid openness is a Float), so don't filter by type.
		Object state = null;
		for (int i = 0; i + 1 < args.length; i++) {
			if (args[i] == model) {
				Object next = args[i + 1];
				if (!(next instanceof PoseStack) && !isRenderType(next)) state = next;
				break;
			}
		}
		if (state != null) {
			try {
				// Renderers share one model instance between entities; pose it for this entity right now.
				model.setupAnim(state);
			} catch (RuntimeException e) {
				GltfExportClient.LOGGER.debug("setupAnim failed for {}", model.getClass().getName(), e);
			}
		}
		ModelPart root = model.root();
		if (root == null) return;
		submitPart(root, model.getClass().getSimpleName(), "root", renderType, sprite, tint, pose);
	}

	private void submitPart(ModelPart part, String rigName, String rootName, Object renderType, TextureAtlasSprite sprite,
							int tint, Matrix4f pose) {
		String texKey;
		if (sprite != null) {
			texKey = TextureCache.spriteKey(sprite);
		} else {
			Optional<Identifier> tex = textures.textureOf(renderType);
			if (tex.isPresent() && skipTexture(tex.get())) return;
			texKey = TextureCache.textureKey(tex.orElse(null));
			if (skinOverride != null && isSkinTexture(tex.orElse(null))) texKey = skinOverride;
			if (GltfExportClient.SETTINGS.verboseLog) {
				GltfExportClient.LOGGER.info("[gltfexport] {} texture {} -> {}", rigName, tex.orElse(null), texKey);
			}
		}
		textures.source(texKey); // load now, on the client thread
		String rt = renderTypeName(renderType);
		boolean emissive = rt.contains("eyes") || rt.contains("emissive") || rt.contains("energy");
		boolean translucent = rt.contains("translucent") || rt.contains("eyes");
		MaterialKey key = new MaterialKey(texKey, translucent, true, emissive);
		Rig rig = rigger.rig(part, rigName, rootName, pose, key, sprite, tint);
		if (!rig.isEmpty()) group.rigs.add(rig);
	}

	// --------------------------------------------------------------------------------------------- quads

	private void addQuads(List<?> quads, Matrix4f pose, int[] tintLayers) {
		for (Object o : quads) {
			if (!(o instanceof BakedQuad quad)) continue;
			int ti = quad.materialInfo().tintIndex();
			int tint = 0xFFFFFF;
			if (ti >= 0 && tintLayers != null && ti < tintLayers.length) tint = tintLayers[ti];
			textures.source(TextureCache.spriteKey(quad.materialInfo().sprite()));
			BlockMesher.addBakedQuad(quad, pose, tint, group.statics, -1);
		}
	}

	private void submitCustomGeometry(Object callback, Method method, PoseStack poseStack, Object renderType, int tint) {
		capture.clear();
		Object[] callArgs = new Object[method.getParameterCount()];
		Class<?>[] types = method.getParameterTypes();
		PoseStack.Pose pose = poseStack != null ? poseStack.last() : new PoseStack().last();
		for (int i = 0; i < types.length; i++) {
			if (types[i] == PoseStack.Pose.class) callArgs[i] = pose;
			else if (VertexConsumer.class.isAssignableFrom(types[i])) callArgs[i] = capture;
			else if (types[i] == PoseStack.class) callArgs[i] = poseStack;
			else callArgs[i] = Refl.defaultValue(types[i]);
		}
		try {
			method.setAccessible(true);
			method.invoke(callback, callArgs);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return;
		}
		if (capture.quadCount() == 0) return;
		Optional<Identifier> tex = textures.textureOf(renderType);
		if (tex.isEmpty() || skipTexture(tex.get())) return; // untextured debug / beam / shadow geometry
		String key = TextureCache.textureKey(tex.get());
		textures.source(key);
		MaterialKey mat = new MaterialKey(key, true, true, false);
		int[] colors = new int[4];
		float[] p = new float[12];
		float[] t = new float[8];
		for (int q = 0; q < capture.quadCount(); q++) {
			for (int i = 0; i < 4; i++) {
				int vi = q * 4 + i;
				p[i * 3] = capture.x(vi);
				p[i * 3 + 1] = capture.y(vi);
				p[i * 3 + 2] = capture.z(vi);
				t[i * 2] = capture.u(vi);
				t[i * 2 + 1] = capture.v(vi);
				colors[i] = multiply(tint, capture.argb(vi));
			}
			group.stat(mat).addQuad(p, t, -1, colors);
		}
	}

	private static Method geometryCallback(Class<?> type) {
		if (type.isArray() || type.isPrimitive() || type.getName().startsWith("java.")) return null;
		for (Class<?> iface : allInterfaces(type)) {
			for (Method m : iface.getMethods()) {
				if (!Modifier.isAbstract(m.getModifiers())) continue;
				boolean hasConsumer = false;
				for (Class<?> p : m.getParameterTypes()) {
					if (VertexConsumer.class.isAssignableFrom(p)) hasConsumer = true;
				}
				if (hasConsumer) return m;
			}
		}
		return null;
	}

	private static List<Class<?>> allInterfaces(Class<?> type) {
		List<Class<?>> out = new ArrayList<>();
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			for (Class<?> i : c.getInterfaces()) {
				out.add(i);
				out.addAll(allInterfaces(i));
			}
		}
		return out;
	}

	// --------------------------------------------------------------------------------------------- helpers

	private static <T> T find(Object[] args, Class<T> type) {
		for (Object a : args) {
			if (type.isInstance(a)) return type.cast(a);
		}
		return null;
	}

	private static boolean isRenderType(Object o) {
		return o instanceof RenderType;
	}

	private static Object findRenderType(Object[] args) {
		for (Object a : args) {
			if (isRenderType(a)) return a;
		}
		return null;
	}

	/** A player skin slot: a downloaded skin, a default skin, or a texture we couldn't identify. */
	private static boolean isSkinTexture(Identifier id) {
		if (id == null) return true;
		String p = id.getPath();
		return p.startsWith("skins/") || p.contains("entity/player/") || p.contains("textures/entity/steve")
				|| p.contains("textures/entity/alex");
	}

	/** Only outline passes are skipped by render type; glint, shadows etc. are recognised by their texture. */
	private static boolean skipRenderType(Object renderType) {
		try {
			return renderType instanceof RenderType rt && rt.isOutline();
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	private static boolean skipTexture(Identifier id) {
		if (id == null) return false;
		String p = id.getPath();
		return p.contains("glint") || p.contains("misc/shadow") || p.contains("destroy_stage")
				|| p.contains("lightmap") || p.contains("misc/white");
	}

	/** The render type's short name (e.g. "entity_cutout_no_cull", "eyes"), lower case. */
	private static String renderTypeName(Object renderType) {
		if (renderType == null) return "";
		Object n = Refl.get(renderType, "name");
		String s = n instanceof String str ? str : renderType.toString();
		int cut = s.length();
		for (char c : new char[]{'[', '{', '(', ',', ':', ' '}) {
			int i = s.indexOf(c);
			if (i > 0 && i < cut) cut = i;
		}
		return s.substring(0, cut).toLowerCase(java.util.Locale.ROOT);
	}

	/**
	 * Model submits pass ints in the order (light, overlay, tintColour, outlineColour). Short overloads omit the
	 * tint, leaving three ints. Returns RGB, or white.
	 */
	private static int tintArgument(Object[] args) {
		List<Integer> ints = new ArrayList<>(4);
		for (Object a : args) {
			if (a instanceof Integer i) ints.add(i);
		}
		if (ints.size() < 4) return 0xFFFFFF;
		int c = ints.get(2);
		if (c == -1 || c >>> 24 == 0) return 0xFFFFFF;
		return c & 0xFFFFFF;
	}

	private static int multiply(int a, int b) {
		int r = (a >> 16 & 255) * (b >> 16 & 255) / 255;
		int g = (a >> 8 & 255) * (b >> 8 & 255) / 255;
		int bl = (a & 255) * (b & 255) / 255;
		return r << 16 | g << 8 | bl;
	}
}
