package dev.gltfexport.export;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.imageio.ImageIO;

import dev.gltfexport.GltfExportClient;
import dev.gltfexport.util.Refl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/**
 * Resolves texture keys to images and bakes tints into them.
 *
 * <p>Key formats:
 * <ul>
 *   <li>{@code sprite:<namespace:path>[#anim]} – an atlas sprite; loaded from {@code textures/<path>.png}</li>
 *   <li>{@code tex:<namespace:textures/...png>} – a standalone texture (entity skins, etc.)</li>
 * </ul>
 * Source images are loaded on the client thread during capture; baking/encoding is thread-safe afterwards.
 */
public final class TextureCache {
	public static final String MISSING = "missing";

	private final Map<String, BufferedImage> sources = new HashMap<>();
	private final Map<Object, Optional<Identifier>> renderTypeTextures = new IdentityHashMap<>();
	private int missingCount;

	public static String spriteKey(TextureAtlasSprite sprite) {
		if (sprite == null) return MISSING;
		Identifier id = sprite.contents().name();
		boolean anim = false;
		try {
			anim = sprite.contents().isAnimated();
		} catch (RuntimeException ignored) {
		}
		return "sprite:" + id + (anim ? "#anim" : "");
	}

	public static String textureKey(Identifier id) {
		return id == null ? MISSING : "tex:" + id;
	}

	/** Maps a sprite-atlas UV to 0..1 within the sprite. */
	public static float localU(TextureAtlasSprite s, float u) {
		float w = s.getU1() - s.getU0();
		return w == 0 ? u : (u - s.getU0()) / w;
	}

	public static float localV(TextureAtlasSprite s, float v) {
		float h = s.getV1() - s.getV0();
		return h == 0 ? v : (v - s.getV0()) / h;
	}

	/** Finds the texture a RenderType samples, by searching its state for a texture Identifier. Cached. */
	public Optional<Identifier> textureOf(Object renderType) {
		if (renderType == null) return Optional.empty();
		return renderTypeTextures.computeIfAbsent(renderType, rt -> {
			List<Identifier> ids = Refl.searchGraph(rt, Identifier.class, 6);
			Identifier best = null;
			for (Identifier id : ids) {
				String p = id.getPath();
				if (p.contains("enchanted_glint") || p.contains("lightmap")) continue;
				if (p.endsWith(".png")) {
					best = id;
					break;
				}
				if (best == null && p.startsWith("textures/")) best = id;
			}
			return Optional.ofNullable(best);
		});
	}

	/** Loads (and caches) the source image for a key. Must run on the client thread. */
	public BufferedImage source(String key) {
		BufferedImage cached = sources.get(key);
		if (cached != null) return cached;
		BufferedImage img = null;
		try {
			img = load(key);
		} catch (Exception e) {
			GltfExportClient.LOGGER.debug("Could not load texture {}", key, e);
		}
		if (img == null) {
			missingCount++;
			img = checker();
		}
		sources.put(key, img);
		return img;
	}

	public int missingCount() {
		return missingCount;
	}

	private BufferedImage load(String key) throws IOException {
		if (key.equals(MISSING)) return null;
		if (key.startsWith("sprite:")) {
			String rest = key.substring("sprite:".length());
			boolean anim = rest.endsWith("#anim");
			if (anim) rest = rest.substring(0, rest.length() - 5);
			Identifier spriteId = Identifier.parse(rest);
			if (spriteId.getPath().equals("missingno")) return null;
			Identifier file = Identifier.fromNamespaceAndPath(spriteId.getNamespace(), "textures/" + spriteId.getPath() + ".png");
			BufferedImage img = readResource(file);
			if (img == null) return null;
			if (anim && img.getHeight() > img.getWidth()) {
				// animated strip: keep the first (square) frame
				img = img.getSubimage(0, 0, img.getWidth(), img.getWidth());
			}
			return toArgb(img);
		}
		if (key.startsWith("tex:")) {
			Identifier id = Identifier.parse(key.substring(4));
			BufferedImage img = readResource(id);
			if (img == null) img = readDynamicTexture(id);
			return img == null ? null : toArgb(img);
		}
		return null;
	}

	private static BufferedImage readResource(Identifier id) throws IOException {
		Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(id);
		if (res.isEmpty()) return null;
		try (InputStream in = res.get().open()) {
			return ImageIO.read(in);
		}
	}

	/** Skins, maps and other textures that only exist in memory. Read back through NativeImage via reflection. */
	private static BufferedImage readDynamicTexture(Identifier id) {
		try {
			Object tm = Minecraft.getInstance().getTextureManager();
			Method getTexture = Refl.findMethod(tm.getClass(), m -> m.getName().equals("getTexture")
					&& m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(Identifier.class));
			if (getTexture == null) return null;
			Object tex = getTexture.invoke(tm, id);
			if (tex == null) return null;
			Object nativeImage = Refl.invokeNoArg(tex, "getPixels");
			if (nativeImage == null) return null;
			int w = ((Number) Refl.invokeNoArg(nativeImage, "getWidth")).intValue();
			int h = ((Number) Refl.invokeNoArg(nativeImage, "getHeight")).intValue();
			Method getPixel = Refl.findMethod(nativeImage.getClass(), m -> m.getName().equals("getPixel") && m.getParameterCount() == 2);
			boolean abgr = false;
			if (getPixel == null) {
				getPixel = Refl.findMethod(nativeImage.getClass(), m -> m.getName().equals("getPixelRGBA") && m.getParameterCount() == 2);
				abgr = true;
			}
			if (getPixel == null) return null;
			BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int c = (Integer) getPixel.invoke(nativeImage, x, y);
					if (abgr) c = (c & 0xFF00FF00) | (c & 0xFF) << 16 | (c >> 16) & 0xFF;
					img.setRGB(x, y, c);
				}
			}
			return img;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static BufferedImage toArgb(BufferedImage src) {
		if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
		BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < src.getHeight(); y++) {
			for (int x = 0; x < src.getWidth(); x++) {
				out.setRGB(x, y, src.getRGB(x, y));
			}
		}
		return out;
	}

	private static BufferedImage checker() {
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				img.setRGB(x, y, ((x / 8 + y / 8) & 1) == 0 ? 0xFFF800F8 : 0xFF000000);
			}
		}
		return img;
	}

	/** Source image multiplied by an RGB tint. Thread-safe once sources are loaded. */
	public static BufferedImage tinted(BufferedImage src, int tintRgb) {
		tintRgb &= 0xFFFFFF;
		if (tintRgb == 0xFFFFFF) return src;
		int tr = tintRgb >> 16 & 255, tg = tintRgb >> 8 & 255, tb = tintRgb & 255;
		BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < src.getHeight(); y++) {
			for (int x = 0; x < src.getWidth(); x++) {
				int c = src.getRGB(x, y);
				int a = c >>> 24;
				int r = (c >> 16 & 255) * tr / 255;
				int g = (c >> 8 & 255) * tg / 255;
				int b = (c & 255) * tb / 255;
				out.setRGB(x, y, a << 24 | r << 16 | g << 8 | b);
			}
		}
		return out;
	}

	/** 0 = fully opaque, 1 = only on/off alpha, 2 = has partial alpha. */
	public static int alphaClass(BufferedImage img) {
		int cls = 0;
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				int a = img.getRGB(x, y) >>> 24;
				if (a == 255) continue;
				if (a == 0) cls = Math.max(cls, 1);
				else return 2;
			}
		}
		return cls;
	}

	public static byte[] png(BufferedImage img) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	public BufferedImage loaded(String key) {
		BufferedImage img = sources.get(key);
		return img != null ? img : checker();
	}
}
