package dev.gltfexport.export;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.gltfexport.GltfExportClient;
import dev.gltfexport.util.Refl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Drives vanilla's entity and block-entity renderers: extract a render state, then "submit" it into a
 * {@link SubmitCapture}. Method lookup is by parameter/return types so small signature changes between game versions
 * don't break the exporter.
 */
public final class DispatcherBridge {
	private static final float PARTIAL_TICK = 1.0f;

	private final Object entityDispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
	private final Object blockEntityDispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
	private final Object camera = findCameraState();
	private Method entityExtract, entitySubmit, beExtract, beSubmit;
	private boolean entityWarned, beWarned;

	/** @return true if anything was submitted */
	public boolean submitEntity(Entity entity, double relX, double relY, double relZ, SubmitCapture capture) {
		try {
			if (entityExtract == null) {
				entityExtract = Refl.findMethod(entityDispatcher.getClass(), m -> !Modifier.isStatic(m.getModifiers())
						&& m.getParameterCount() >= 1 && m.getParameterTypes()[0].isAssignableFrom(Entity.class)
						&& EntityRenderState.class.isAssignableFrom(m.getReturnType()));
			}
			if (entityExtract == null) throw new IllegalStateException("no entity extract method");
			Object state = entityExtract.invoke(entityDispatcher, fill(entityExtract, entity, false));
			if (state == null) return false;
			hideNameTags(state);

			if (entitySubmit == null) entitySubmit = findSubmit(entityDispatcher, state.getClass());
			if (entitySubmit == null) throw new IllegalStateException("no entity submit method");

			PoseStack poseStack = new PoseStack();
			Class<?>[] types = entitySubmit.getParameterTypes();
			Object[] args = new Object[types.length];
			double[] rel = {relX, relY, relZ};
			int doubles = 0;
			for (int i = 0; i < types.length; i++) {
				Class<?> t = types[i];
				if (t.isInstance(state)) args[i] = state;
				else if (t == PoseStack.class) args[i] = poseStack;
				else if (t.isAssignableFrom(SubmitNodeCollector.class)) args[i] = capture.collector();
				else if (t == CameraRenderState.class) args[i] = camera;
				else if (t == double.class) args[i] = doubles < 3 ? rel[doubles++] : 0d;
				else args[i] = Refl.defaultValue(t);
			}
			if (doubles == 0) poseStack.translate(relX, relY, relZ);
			entitySubmit.invoke(entityDispatcher, args);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			if (!entityWarned) {
				entityWarned = true;
				GltfExportClient.LOGGER.warn("Entity capture failed for {}", entity, e);
			}
			return false;
		}
	}

	public boolean submitBlockEntity(BlockEntity be, double relX, double relY, double relZ, SubmitCapture capture) {
		try {
			if (beExtract == null) {
				beExtract = Refl.findMethod(blockEntityDispatcher.getClass(), m -> !Modifier.isStatic(m.getModifiers())
						&& m.getParameterCount() >= 1 && m.getParameterTypes()[0].isAssignableFrom(BlockEntity.class)
						&& BlockEntityRenderState.class.isAssignableFrom(m.getReturnType()));
			}
			if (beExtract == null) throw new IllegalStateException("no block entity extract method");
			Object state = beExtract.invoke(blockEntityDispatcher, fill(beExtract, be, false));
			if (state == null) state = beExtract.invoke(blockEntityDispatcher, fill(beExtract, be, true));
			if (state == null) return false;

			if (beSubmit == null) beSubmit = findSubmit(blockEntityDispatcher, state.getClass());
			if (beSubmit == null) throw new IllegalStateException("no block entity submit method");

			PoseStack poseStack = new PoseStack();
			poseStack.translate(relX, relY, relZ);
			Class<?>[] types = beSubmit.getParameterTypes();
			Object[] args = new Object[types.length];
			for (int i = 0; i < types.length; i++) {
				Class<?> t = types[i];
				if (t.isInstance(state)) args[i] = state;
				else if (t == PoseStack.class) args[i] = poseStack;
				else if (t.isAssignableFrom(SubmitNodeCollector.class)) args[i] = capture.collector();
				else if (t == CameraRenderState.class) args[i] = camera;
				else args[i] = Refl.defaultValue(t);
			}
			beSubmit.invoke(blockEntityDispatcher, args);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			if (!beWarned) {
				beWarned = true;
				GltfExportClient.LOGGER.warn("Block entity capture failed for {}", be.getType(), e);
			}
			return false;
		}
	}

	private static Object[] fill(Method m, Object first, boolean bools) {
		Class<?>[] types = m.getParameterTypes();
		Object[] args = new Object[types.length];
		args[0] = first;
		for (int i = 1; i < types.length; i++) {
			if (types[i] == float.class) args[i] = PARTIAL_TICK;
			else if (types[i] == boolean.class) args[i] = bools;
			else args[i] = Refl.defaultValue(types[i]);
		}
		return args;
	}

	private static Method findSubmit(Object dispatcher, Class<?> stateClass) {
		Method best = null;
		for (Method m : Refl.allMethods(dispatcher.getClass())) {
			if (Modifier.isStatic(m.getModifiers())) continue;
			boolean hasState = false, hasCollector = false, hasPose = false;
			for (Class<?> t : m.getParameterTypes()) {
				if (t.isAssignableFrom(stateClass) && t != Object.class) hasState = true;
				if (t == SubmitNodeCollector.class || t.isAssignableFrom(SubmitNodeCollector.class) && t != Object.class) hasCollector = true;
				if (t == PoseStack.class) hasPose = true;
			}
			if (hasState && hasCollector && hasPose) {
				if (best == null || m.getName().equals("submit")) best = m;
			}
		}
		if (best != null) best.setAccessible(true);
		return best;
	}

	private static void hideNameTags(Object state) {
		Refl.setIfPresent(state, "nameTag", null);
		Refl.setIfPresent(state, "scoreText", null);
		Refl.setIfPresent(state, "nameTagAttachment", null);
	}

	/** The camera state used this frame (needed by billboarded parts); a fresh one if it can't be found. */
	private static Object findCameraState() {
		Minecraft mc = Minecraft.getInstance();
		for (Object root : new Object[]{mc.gameRenderer, mc.levelRenderer}) {
			List<CameraRenderState> found = Refl.searchGraph(root, CameraRenderState.class, 3);
			if (!found.isEmpty()) return found.get(0);
		}
		try {
			Constructor<CameraRenderState> c = CameraRenderState.class.getDeclaredConstructor();
			c.setAccessible(true);
			return c.newInstance();
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}
}
