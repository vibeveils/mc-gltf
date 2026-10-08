package dev.gltfexport.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Small reflection helpers. Minecraft 26.x ships unobfuscated, so member names seen here are the real runtime names.
 * Reflection is used for the parts of the render pipeline whose exact signatures shift between snapshots, so the mod
 * keeps working (and degrades gracefully) instead of failing to load.
 */
public final class Refl {
	private static final Map<String, Optional<Field>> FIELD_CACHE = new ConcurrentHashMap<>();

	private Refl() {
	}

	public static List<Field> allFields(Class<?> type) {
		List<Field> out = new ArrayList<>();
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			Collections.addAll(out, c.getDeclaredFields());
		}
		return out;
	}

	public static List<Method> allMethods(Class<?> type) {
		List<Method> out = new ArrayList<>();
		Set<String> seen = new java.util.HashSet<>();
		ArrayDeque<Class<?>> queue = new ArrayDeque<>();
		queue.add(type);
		while (!queue.isEmpty()) {
			Class<?> c = queue.poll();
			if (c == null || c == Object.class) continue;
			for (Method m : c.getDeclaredMethods()) {
				String sig = m.getName() + java.util.Arrays.toString(m.getParameterTypes());
				if (seen.add(sig)) out.add(m);
			}
			if (c.getSuperclass() != null) queue.add(c.getSuperclass());
			Collections.addAll(queue, c.getInterfaces());
		}
		return out;
	}

	/** Finds a field by name anywhere in the class hierarchy. */
	public static Optional<Field> field(Class<?> type, String name) {
		return FIELD_CACHE.computeIfAbsent(type.getName() + "#" + name, k -> {
			for (Field f : allFields(type)) {
				if (f.getName().equals(name)) {
					try {
						f.setAccessible(true);
						return Optional.of(f);
					} catch (RuntimeException e) {
						return Optional.empty();
					}
				}
			}
			return Optional.empty();
		});
	}

	/** Finds the first field by name, or failing that the first field assignable to the given type. */
	public static Optional<Field> fieldNamedOrTyped(Class<?> owner, String name, Class<?> type) {
		Optional<Field> named = field(owner, name);
		if (named.isPresent() && type.isAssignableFrom(named.get().getType())) return named;
		return FIELD_CACHE.computeIfAbsent(owner.getName() + "#type:" + type.getName(), k -> {
			for (Field f : allFields(owner)) {
				if (!Modifier.isStatic(f.getModifiers()) && type.isAssignableFrom(f.getType())) {
					try {
						f.setAccessible(true);
						return Optional.of(f);
					} catch (RuntimeException e) {
						return Optional.empty();
					}
				}
			}
			return Optional.empty();
		});
	}

	public static Object get(Object target, String name) {
		if (target == null) return null;
		Optional<Field> f = field(target.getClass(), name);
		if (f.isEmpty()) return null;
		try {
			return f.get().get(target);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	public static boolean getBoolean(Object target, String name, boolean def) {
		Object v = get(target, name);
		return v instanceof Boolean b ? b : def;
	}

	public static void setIfPresent(Object target, String name, Object value) {
		if (target == null) return;
		Optional<Field> f = field(target.getClass(), name);
		if (f.isEmpty() || Modifier.isFinal(f.get().getModifiers())) return;
		try {
			if (value == null && f.get().getType().isPrimitive()) return;
			f.get().set(target, value);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
		}
	}

	public static Method findMethod(Class<?> type, Predicate<Method> filter) {
		for (Method m : allMethods(type)) {
			if (filter.test(m)) {
				try {
					m.setAccessible(true);
				} catch (RuntimeException ignored) {
				}
				return m;
			}
		}
		return null;
	}

	public static Object invokeNoArg(Object target, String name) {
		if (target == null) return null;
		Method m = findMethod(target.getClass(), mm -> mm.getName().equals(name) && mm.getParameterCount() == 0
				&& !Modifier.isStatic(mm.getModifiers()));
		if (m == null) return null;
		try {
			return m.invoke(target);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	public static Object defaultValue(Class<?> type) {
		if (!type.isPrimitive()) return null;
		if (type == boolean.class) return false;
		if (type == int.class) return 0;
		if (type == long.class) return 0L;
		if (type == float.class) return 0f;
		if (type == double.class) return 0d;
		if (type == short.class) return (short) 0;
		if (type == byte.class) return (byte) 0;
		if (type == char.class) return (char) 0;
		return null;
	}

	/**
	 * Breadth-first search through an object graph (instance fields, collections, maps, optionals, arrays) for the first
	 * value of the wanted type that passes the filter. Used to dig a texture location out of a RenderType.
	 */
	public static <T> List<T> searchGraph(Object root, Class<T> wanted, int maxDepth) {
		List<T> found = new ArrayList<>();
		if (root == null) return found;
		IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
		ArrayDeque<Object[]> queue = new ArrayDeque<>();
		queue.add(new Object[]{root, 0});
		while (!queue.isEmpty() && visited.size() < 4000) {
			Object[] entry = queue.poll();
			Object obj = entry[0];
			int depth = (Integer) entry[1];
			if (obj == null || visited.put(obj, Boolean.TRUE) != null) continue;
			if (wanted.isInstance(obj)) {
				found.add(wanted.cast(obj));
				continue;
			}
			if (depth >= maxDepth) continue;
			Class<?> c = obj.getClass();
			if (c.isPrimitive() || obj instanceof String || obj instanceof Number || obj instanceof Boolean
					|| obj instanceof Class<?> || obj instanceof Enum<?> && !wanted.isEnum()) continue;
			if (obj instanceof Optional<?> opt) {
				opt.ifPresent(v -> queue.add(new Object[]{v, depth + 1}));
				continue;
			}
			if (obj instanceof Map<?, ?> map) {
				for (Object v : map.values()) queue.add(new Object[]{v, depth + 1});
				for (Object v : map.keySet()) queue.add(new Object[]{v, depth + 1});
				continue;
			}
			if (obj instanceof Collection<?> col) {
				int n = 0;
				for (Object v : col) {
					if (n++ > 64) break;
					queue.add(new Object[]{v, depth + 1});
				}
				continue;
			}
			if (c.isArray()) {
				if (!c.getComponentType().isPrimitive()) {
					Object[] arr = (Object[]) obj;
					for (int i = 0; i < Math.min(arr.length, 64); i++) queue.add(new Object[]{arr[i], depth + 1});
				}
				continue;
			}
			String pkg = c.getPackageName();
			if (!(pkg.startsWith("net.minecraft") || pkg.startsWith("com.mojang") || pkg.startsWith("net.fabricmc")
					|| pkg.startsWith("java.util"))) {
				continue;
			}
			for (Field f : allFields(c)) {
				if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
				try {
					f.setAccessible(true);
					queue.add(new Object[]{f.get(obj), depth + 1});
				} catch (ReflectiveOperationException | RuntimeException ignored) {
				}
			}
		}
		return found;
	}
}
