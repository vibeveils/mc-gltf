package dev.gltfexport;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.gltfexport.export.ExportJob;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-only mod. Select two corners (golden hoe: left-click = corner 1, right-click = corner 2, or /gltf pos1|pos2)
 * and run /gltf export [name] to write .minecraft/gltf_exports/&lt;name&gt;.glb.
 */
public final class GltfExportClient implements ClientModInitializer {
	public static final String MOD_ID = "gltfexport";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final Item WAND = Items.GOLDEN_HOE;
	private static final long MAX_BLOCKS = 4_194_304L; // e.g. 256 x 64 x 256

	public static final ExportSettings SETTINGS = new ExportSettings();
	private static BlockPos pos1, pos2;
	private static boolean exporting;
	private static int tick;

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(buildCommand()));

		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (!level.isClientSide() || !player.getMainHandItem().is(WAND)) return InteractionResult.PASS;
			if (!pos.equals(pos1)) {
				pos1 = pos.immutable();
				announce(player, "Corner 1 set to " + pos1.toShortString());
			}
			return InteractionResult.FAIL; // don't break the block, don't tell the server
		});

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide() || !player.getItemInHand(hand).is(WAND)) return InteractionResult.PASS;
			BlockPos pos = hit.getBlockPos().immutable();
			if (!pos.equals(pos2)) {
				pos2 = pos;
				announce(player, "Corner 2 set to " + pos2.toShortString());
			}
			return InteractionResult.FAIL;
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (++tick % 8 == 0) drawSelection(client);
		});
	}

	// ------------------------------------------------------------------------------------------ commands

	private static LiteralArgumentBuilder<FabricClientCommandSource> buildCommand() {
		LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("gltf");

		root.then(ClientCommands.literal("pos1")
				.executes(ctx -> setCorner(ctx, 1, targetOrFeet(ctx.getSource())))
				.then(coords(1)));
		root.then(ClientCommands.literal("pos2")
				.executes(ctx -> setCorner(ctx, 2, targetOrFeet(ctx.getSource())))
				.then(coords(2)));

		root.then(ClientCommands.literal("export")
				.executes(ctx -> export(ctx, defaultName()))
				.then(ClientCommands.argument("name", StringArgumentType.word())
						.executes(ctx -> export(ctx, StringArgumentType.getString(ctx, "name")))));

		root.then(ClientCommands.literal("clear").executes(ctx -> {
			pos1 = pos2 = null;
			ctx.getSource().sendFeedback(Component.literal("Selection cleared."));
			return Command.SINGLE_SUCCESS;
		}));

		root.then(ClientCommands.literal("info").executes(ctx -> {
			FabricClientCommandSource src = ctx.getSource();
			src.sendFeedback(Component.literal("Corner 1: " + (pos1 == null ? "unset" : pos1.toShortString())
					+ "  Corner 2: " + (pos2 == null ? "unset" : pos2.toShortString())));
			if (pos1 != null && pos2 != null) {
				src.sendFeedback(Component.literal("Size: " + sizeX() + " x " + sizeY() + " x " + sizeZ()
						+ " (" + volume() + " blocks)"));
			}
			src.sendFeedback(Component.literal("Options: " + SETTINGS.describe()).withStyle(ChatFormatting.GRAY));
			src.sendFeedback(Component.literal("Wand: golden hoe (left-click = corner 1, right-click = corner 2)")
					.withStyle(ChatFormatting.GRAY));
			return Command.SINGLE_SUCCESS;
		}));

		var set = ClientCommands.literal("set");
		for (String option : ExportSettings.NAMES) {
			set.then(ClientCommands.literal(option)
					.then(ClientCommands.argument("value", BoolArgumentType.bool()).executes(ctx -> {
						boolean v = BoolArgumentType.getBool(ctx, "value");
						SETTINGS.set(option, v);
						ctx.getSource().sendFeedback(Component.literal(option + " = " + v));
						return Command.SINGLE_SUCCESS;
					})));
		}
		root.then(set);
		return root;
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource, Integer> coords(int corner) {
		return ClientCommands.argument("x", IntegerArgumentType.integer())
				.then(ClientCommands.argument("y", IntegerArgumentType.integer())
						.then(ClientCommands.argument("z", IntegerArgumentType.integer())
								.executes(ctx -> setCorner(ctx, corner, new BlockPos(
										IntegerArgumentType.getInteger(ctx, "x"),
										IntegerArgumentType.getInteger(ctx, "y"),
										IntegerArgumentType.getInteger(ctx, "z"))))));
	}

	private static BlockPos targetOrFeet(FabricClientCommandSource source) {
		HitResult hit = source.getClient().hitResult;
		if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) return bhr.getBlockPos().immutable();
		return source.getPlayer().blockPosition();
	}

	private static int setCorner(CommandContext<FabricClientCommandSource> ctx, int corner, BlockPos pos) {
		if (corner == 1) pos1 = pos;
		else pos2 = pos;
		ctx.getSource().sendFeedback(Component.literal("Corner " + corner + " set to " + pos.toShortString()));
		return Command.SINGLE_SUCCESS;
	}

	private static int export(CommandContext<FabricClientCommandSource> ctx, String name) {
		FabricClientCommandSource src = ctx.getSource();
		if (pos1 == null || pos2 == null) {
			src.sendError(Component.literal("Select two corners first (golden hoe, or /gltf pos1 and /gltf pos2)."));
			return 0;
		}
		if (exporting) {
			src.sendError(Component.literal("An export is already running."));
			return 0;
		}
		if (volume() > MAX_BLOCKS) {
			src.sendError(Component.literal("Selection is " + volume() + " blocks; the limit is " + MAX_BLOCKS + "."));
			return 0;
		}
		ClientLevel level = src.getLevel();
		String safe = name.replaceAll("[^A-Za-z0-9_.-]", "_");
		Path file = FabricLoader.getInstance().getGameDir().resolve("gltf_exports").resolve(safe + ".glb");

		src.sendFeedback(Component.literal("Exporting " + volume() + " blocks..."));
		exporting = true;
		Minecraft mc = src.getClient();
		try {
			ExportJob.start(level, pos1, pos2, file, SETTINGS, msg -> src.sendFeedback(Component.literal(msg)))
					.whenComplete((result, error) -> mc.execute(() -> {
						exporting = false;
						if (error != null) {
							src.sendError(Component.literal("Export failed: " + rootMessage(error)));
							return;
						}
						src.sendFeedback(Component.literal("Saved " + result.file().getFileName() + " ("
								+ (result.bytes() / 1024) + " KB) to " + result.file().getParent()).withStyle(ChatFormatting.GREEN));
						if (result.missingTextures() > 0) {
							src.sendFeedback(Component.literal(result.missingTextures()
									+ " texture(s) could not be read and use a placeholder; see the log.").withStyle(ChatFormatting.YELLOW));
						}
					}));
		} catch (RuntimeException e) {
			exporting = false;
			LOGGER.error("Export failed", e);
			src.sendError(Component.literal("Export failed: " + e));
			return 0;
		}
		return Command.SINGLE_SUCCESS;
	}

	private static String rootMessage(Throwable t) {
		while (t.getCause() != null) t = t.getCause();
		return t.toString();
	}

	private static String defaultName() {
		return "export_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
	}

	// ------------------------------------------------------------------------------------------ selection

	private static int sizeX() {
		return Math.abs(pos1.getX() - pos2.getX()) + 1;
	}

	private static int sizeY() {
		return Math.abs(pos1.getY() - pos2.getY()) + 1;
	}

	private static int sizeZ() {
		return Math.abs(pos1.getZ() - pos2.getZ()) + 1;
	}

	private static long volume() {
		return (long) sizeX() * sizeY() * sizeZ();
	}

	private static void announce(net.minecraft.world.entity.player.Player player, String msg) {
		if (player instanceof LocalPlayer lp) {
			String size = pos1 != null && pos2 != null ? "  (" + sizeX() + " x " + sizeY() + " x " + sizeZ() + ")" : "";
			lp.sendOverlayMessage(Component.literal(msg + size));
		}
	}

	/** Outlines the selection with particles along its 12 edges. */
	private static void drawSelection(Minecraft client) {
		if (!SETTINGS.showSelection || client.level == null || client.player == null) return;
		if (pos1 == null && pos2 == null) return;
		BlockPos a = pos1 != null ? pos1 : pos2;
		BlockPos b = pos2 != null ? pos2 : pos1;
		double x0 = Math.min(a.getX(), b.getX()), y0 = Math.min(a.getY(), b.getY()), z0 = Math.min(a.getZ(), b.getZ());
		double x1 = Math.max(a.getX(), b.getX()) + 1, y1 = Math.max(a.getY(), b.getY()) + 1, z1 = Math.max(a.getZ(), b.getZ()) + 1;
		if (client.player.distanceToSqr((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2) > 256 * 256) return;
		double longest = Math.max(x1 - x0, Math.max(y1 - y0, z1 - z0));
		double step = Math.max(0.5, longest / 48.0);
		edge(client.level, x0, y0, z0, x1, y0, z0, step);
		edge(client.level, x0, y1, z0, x1, y1, z0, step);
		edge(client.level, x0, y0, z1, x1, y0, z1, step);
		edge(client.level, x0, y1, z1, x1, y1, z1, step);
		edge(client.level, x0, y0, z0, x0, y1, z0, step);
		edge(client.level, x1, y0, z0, x1, y1, z0, step);
		edge(client.level, x0, y0, z1, x0, y1, z1, step);
		edge(client.level, x1, y0, z1, x1, y1, z1, step);
		edge(client.level, x0, y0, z0, x0, y0, z1, step);
		edge(client.level, x1, y0, z0, x1, y0, z1, step);
		edge(client.level, x0, y1, z0, x0, y1, z1, step);
		edge(client.level, x1, y1, z0, x1, y1, z1, step);
	}

	private static void edge(ClientLevel level, double ax, double ay, double az, double bx, double by, double bz, double step) {
		double len = Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
		int n = Math.max(1, (int) Math.ceil(len / step));
		for (int i = 0; i <= n; i++) {
			double t = (double) i / n;
			level.addParticle(ParticleTypes.HAPPY_VILLAGER, ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t, 0, 0, 0);
		}
	}
}
