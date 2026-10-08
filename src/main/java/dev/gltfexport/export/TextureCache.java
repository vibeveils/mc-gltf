package dev.gltfexport.export;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
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
 * Resolves texture keys to images. Each texture is exported once; tints are applied as vertex colours.
 *
 * <p>Key formats:
 * <ul>
 *   <li>{@code sprite:<namespace:path>[#anim]} – an atlas sprite; loaded from {@code textures/<path>.png}</li>
 *   <li>{@code tex:<namespace:textures/...png>} – a standalone texture (entity skins, etc.)</li>
 * </ul>
 * Source images are loaded on the client thread during capture; encoding is thread-safe afterwards.
 */
public final class TextureCache {
	public static final String MISSING = "missing";

	private final Map<String, BufferedImage> sources = new java.util.concurrent.ConcurrentHashMap<>();
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
		if (key.startsWith("skin:")) {
			String rest = key.substring(5);
			int bar = rest.indexOf('|');
			String hash = bar >= 0 ? rest.substring(0, bar) : rest;
			String url = bar >= 0 ? rest.substring(bar + 1) : "";
			BufferedImage img = readSkinHash(hash);
			if (img == null) {
				byte[] png = SkinResolver.download(url);
				if (png != null) img = ImageIO.read(new java.io.ByteArrayInputStream(png));
				if (img != null) img = upgradeLegacySkin(img);
			}
			if (img == null) img = readDynamicTexture(Identifier.fromNamespaceAndPath("minecraft", "skins/" + hash));
			return img == null ? null : toArgb(img);
		}
		if (key.startsWith("tex:")) {
			Identifier id = Identifier.parse(key.substring(4));
			BufferedImage img = readResource(id);
			if (img == null && id.getPath().startsWith("skins/")) img = readCachedSkin(id);
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

	private static java.nio.file.Path skinsDir;

	/**
	 * Player skins (also used by player heads) are downloaded and cached on disk as {@code <skins>/<hash[0:2]>/<hash>};
	 * their texture id is {@code minecraft:skins/<hash>}. Read the cached PNG.
	 */
	private static BufferedImage readCachedSkin(Identifier id) {
		return readSkinHash(id.getPath().substring("skins/".length()));
	}

	private static BufferedImage readSkinHash(String hash) {
		if (hash.length() < 2 || hash.contains("/")) return null;
		for (java.nio.file.Path dir : skinDirectories()) {
			java.nio.file.Path file = dir.resolve(hash.substring(0, 2)).resolve(hash);
			if (java.nio.file.Files.isRegularFile(file)) {
				try (InputStream in = java.nio.file.Files.newInputStream(file)) {
					BufferedImage img = ImageIO.read(in);
					if (img != null) return upgradeLegacySkin(img);
				} catch (IOException e) {
					GltfExportClient.LOGGER.debug("Could not read skin {}", file, e);
				}
			}
		}
		GltfExportClient.LOGGER.info("Skin {} is not in the skin cache; downloading it", hash);
		return null;
	}

	private static List<java.nio.file.Path> skinDirectories() {
		List<java.nio.file.Path> dirs = new java.util.ArrayList<>();
		if (skinsDir == null) {
			// the skin manager keeps its cache root as a Path field
			Object skinManager = Refl.invokeNoArg(Minecraft.getInstance(), "getSkinManager");
			for (java.nio.file.Path p : Refl.searchGraph(skinManager, java.nio.file.Path.class, 4)) {
				if (p.getFileName() != null && p.getFileName().toString().equals("skins")) {
					skinsDir = p;
					break;
				}
			}
		}
		if (skinsDir != null) dirs.add(skinsDir);
		java.nio.file.Path game = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
		dirs.add(game.resolve("assets").resolve("skins"));
		if (game.getParent() != null) dirs.add(game.getParent().resolve("assets").resolve("skins"));
		String home = System.getProperty("user.home");
		if (home != null) {
			dirs.add(java.nio.file.Path.of(home, ".minecraft", "assets", "skins"));
			dirs.add(java.nio.file.Path.of(home, "AppData", "Roaming", ".minecraft", "assets", "skins"));
			dirs.add(java.nio.file.Path.of(home, "Library", "Application Support", "minecraft", "assets", "skins"));
		}
		String appdata = System.getenv("APPDATA");
		if (appdata != null) dirs.add(java.nio.file.Path.of(appdata, "ModrinthApp", "meta", "assets", "skins"));
		return dirs;
	}

	/** Old 64x32 skins are converted to the 64x64 layout the model UVs expect, as the game does on load. */
	private static BufferedImage upgradeLegacySkin(BufferedImage img) {
		img = toArgb(img);
		if (img.getWidth() != 64 || img.getHeight() != 32) return img;
		BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = out.createGraphics();
		g.drawImage(img, 0, 0, null);
		g.dispose();
		// legacy skins mirror the right leg/arm onto the left (copyRect calls from vanilla's legacy skin processing)
		int[][] copies = {
				{4, 16, 16, 32, 4, 4, 1}, {8, 16, 16, 32, 4, 4, 1}, {0, 20, 24, 32, 4, 12, 1}, {4, 20, 16, 32, 4, 12, 1},
				{8, 20, 8, 32, 4, 12, 1}, {12, 20, 16, 32, 4, 12, 1}, {44, 16, -8, 32, 4, 4, 1}, {48, 16, -8, 32, 4, 4, 1},
				{40, 20, 0, 32, 4, 12, 1}, {44, 20, -8, 32, 4, 12, 1}, {48, 20, -16, 32, 4, 12, 1}, {52, 20, -8, 32, 4, 12, 1}};
		for (int[] c : copies) {
			for (int y = 0; y < c[5]; y++) {
				for (int x = 0; x < c[4]; x++) {
					int sx = c[0] + x, sy = c[1] + y;
					int dx = c[0] + c[2] + (c[6] == 1 ? c[4] - 1 - x : x), dy = c[1] + c[3] + y;
					if (dx >= 0 && dx < 64 && dy >= 0 && dy < 64) out.setRGB(dx, dy, out.getRGB(sx, sy));
				}
			}
		}
		return out;
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
