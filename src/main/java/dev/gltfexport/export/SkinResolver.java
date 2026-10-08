package dev.gltfexport.export;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.gltfexport.GltfExportClient;
import dev.gltfexport.util.Refl;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Finds the skin texture for anything that carries a player profile: player heads (skull block entities), player
 * entities, mannequins. Works from the profile itself, so it doesn't depend on how the renderer binds the texture.
 *
 * <p>Order: the profile's signed "textures" property (gives the skin URL; the URL's last segment is the hash the game
 * caches it under), then the game's skin manager. The PNG is read from the game's skin cache, or downloaded from the
 * skin URL if it isn't cached yet.
 */
public final class SkinResolver {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NORMAL).build();

	private SkinResolver() {
	}

	/** Returns a TextureCache key ("skin:<hash>|<url>") for the profile found in {@code owner}, or null. */
	public static String find(Object owner) {
		Object profile = findProfile(owner);
		if (profile == null) return null;

		// 1. the textures property on the profile
		String key = fromTexturesProperty(profile);
		if (key != null) return key;

		// 2. ask the skin manager (it resolves unsigned/partial profiles, e.g. heads placed by name)
		key = fromSkinManager(profile);
		if (key == null && GltfExportClient.SETTINGS.verboseLog) {
			GltfExportClient.LOGGER.info("[gltfexport] no skin found for profile {}", profile);
		}
		return key;
	}

	private static Object findProfile(Object owner) {
		Class<?> gameProfile;
		try {
			gameProfile = Class.forName("com.mojang.authlib.GameProfile");
		} catch (ClassNotFoundException e) {
			return null;
		}
		List<?> found = Refl.searchGraph(owner, gameProfile, 5);
		// prefer a profile that already carries textures
		for (Object p : found) {
			if (fromTexturesProperty(p) != null) return p;
		}
		return found.isEmpty() ? null : found.get(0);
	}

	private static String fromTexturesProperty(Object profile) {
		Object properties = Refl.invokeNoArg(profile, "properties");
		if (properties == null) properties = Refl.invokeNoArg(profile, "getProperties");
		if (properties == null) return null;
		try {
			Method get = Refl.findMethod(properties.getClass(), m -> m.getName().equals("get") && m.getParameterCount() == 1);
			if (get == null) return null;
			Object values = get.invoke(properties, "textures");
			if (!(values instanceof Collection<?> c) || c.isEmpty()) return null;
			Object prop = c.iterator().next();
			Object value = Refl.invokeNoArg(prop, "value");
			if (value == null) value = Refl.invokeNoArg(prop, "getValue");
			if (!(value instanceof String b64)) return null;
			JsonObject json = JsonParser.parseString(new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8))
					.getAsJsonObject();
			JsonObject textures = json.getAsJsonObject("textures");
			if (textures == null || !textures.has("SKIN")) return null;
			String url = textures.getAsJsonObject("SKIN").get("url").getAsString();
			String hash = url.substring(url.lastIndexOf('/') + 1);
			return "skin:" + hash + "|" + url;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static String fromSkinManager(Object profile) {
		try {
			Object skinManager = Refl.invokeNoArg(Minecraft.getInstance(), "getSkinManager");
			if (skinManager == null) return null;
			for (Method m : Refl.allMethods(skinManager.getClass())) {
				if (m.getParameterCount() != 1 || !m.getParameterTypes()[0].isInstance(profile)) continue;
				m.setAccessible(true);
				Object result = m.invoke(skinManager, profile);
				if (result instanceof CompletableFuture<?> f) result = f.getNow(null);
				if (result instanceof Optional<?> o) result = o.orElse(null);
				if (result == null) continue;
				// PlayerSkin -> body texture -> its Identifier ("minecraft:skins/<hash>")
				for (Identifier id : Refl.searchGraph(result, Identifier.class, 3)) {
					if (id.getPath().startsWith("skins/")) return "skin:" + id.getPath().substring(6) + "|";
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			GltfExportClient.LOGGER.debug("Skin manager lookup failed", e);
		}
		return null;
	}

	/** Downloads a skin PNG (used when it isn't in the game's cache yet). */
	static byte[] download(String url) {
		if (url == null || url.isEmpty()) return null;
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(url.replace("http://", "https://")))
					.timeout(Duration.ofSeconds(10)).GET().build();
			HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
			return res.statusCode() == 200 ? res.body() : null;
		} catch (Exception e) {
			GltfExportClient.LOGGER.warn("Could not download skin {}: {}", url, e.toString());
			return null;
		}
	}
}
