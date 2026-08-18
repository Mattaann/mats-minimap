package se.kjall.nordicminimap;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import org.joml.Vector3fc;

import java.util.LinkedHashMap;
import java.util.Map;

public final class NordicMinimapClient implements ClientModInitializer {
	private static final int BASE_VISIBLE_BLOCKS = 96, VIEW = 192, PADDING = 8, TEX = VIEW + PADDING * 2;
	private static final Identifier MAP_ID = Identifier.fromNamespaceAndPath("nordic_minimap", "live_map");
	private static final Identifier ROTATED_MAP_ID = Identifier.fromNamespaceAndPath("nordic_minimap", "rotated_live_map");
	private static final Identifier CIRCLE_MAP_ID = Identifier.fromNamespaceAndPath("nordic_minimap", "circle_live_map");
	private static final Identifier FRAME_ID = Identifier.fromNamespaceAndPath("nordic_minimap", "textures/gui/minimap_frame.png");
	private static final Identifier SQUARE_FRAME_ID = Identifier.fromNamespaceAndPath("nordic_minimap", "textures/gui/minimap_square_frame.png");
	private static final Identifier MODERN_FRAME_ID = Identifier.fromNamespaceAndPath("nordic_minimap", "textures/gui/minimap_frame_modern.png");
	private static final Identifier MODERN_SQUARE_FRAME_ID = Identifier.fromNamespaceAndPath("nordic_minimap", "textures/gui/minimap_square_frame_modern.png");
	private static final int[][] COLOURS = new int[TEX][TEX];
	private static final int[][] HEIGHTS = new int[TEX][TEX];
	private static final boolean[][] KNOWN = new boolean[TEX][TEX];
	private static DynamicTexture texture;
	private static DynamicTexture rotatedTexture;
	private static DynamicTexture circleTexture;
	private static int circleTextureSize;
	private static KeyMapping waypointKey;
	private static int loadedTextureScale;
	private static int anchorX = Integer.MIN_VALUE, anchorY, anchorZ;
	private static String activeDimension = "";
	private static int currentStride = 1;
	private static boolean caveMode;
	private static boolean playerWasDead;
	private static int refreshCursor;
	private static int textureGeneration, rotatedGeneration = -1;
	private static float rotatedYaw = Float.NaN;
	private static int circleGeneration = -1;
	private static boolean circleRotated;
	private static float circleYaw = Float.NaN;
	private static float circleU0, circleU1, circleV0, circleV1;
	private static final Map<SurfaceKey, Sample> SURFACE_CACHE = new LinkedHashMap<>(32768, .75F, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<SurfaceKey, Sample> eldest) { return size() > 250_000; }
	};

	@Override public void onInitializeClient() {
		waypointKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.mats_minimap.create_waypoint",
				InputConstants.Type.KEYSYM, InputConstants.KEY_B, KeyMapping.Category.GAMEPLAY));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (waypointKey.consumeClick()) {
				if (client.player != null && client.level != null) client.setScreenAndShow(new WaypointCreateScreen());
			}
			if (client.player != null && client.level != null) {
				boolean dead = client.player.isDeadOrDying();
				if (dead && !playerWasDead && MinimapConfig.INSTANCE.deathpoint) createDeathpoint(client);
				if (!dead && MinimapConfig.INSTANCE.deathpoint) removeReachedDeathpoint(client);
				playerWasDead = dead;
			} else playerWasDead = false;
		});
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath("nordic_minimap", "map"), NordicMinimapClient::render);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
				ClientCommands.literal("matsminimap").executes(command -> {
					Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreenAndShow(new MinimapSettingsScreen()));
					return 1;
				})));
	}

	private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;
		ensureTexture(mc);
		updateForMovement(mc);
		refreshNewlyLoadedTerrain(mc);

		MinimapConfig config = MinimapConfig.INSTANCE;
		// Preserve the configured pixel size in normal/large windows and only shrink it when the GUI becomes cramped.
		float windowScale = Math.max(.75F, Math.min(1.0F, Math.min(g.guiWidth() / 854.0F, g.guiHeight() / 480.0F)));
		int mapWidth = Math.round(config.mapSize * windowScale);
		int mapHeight = mapWidth;
		boolean modernBorder = config.borderStyle.equals("modern");
		int outerFramePadding = !config.borderEnabled ? 0 : config.circular ? modernBorder ? Math.max(8, mapWidth / 10) : Math.max(10, mapWidth / 7)
				: modernBorder ? Math.max(8, mapWidth / 10) : Math.max(12, mapWidth / 6);
		// Position the map by the frame's outer edge, keeping every border pixel on-screen at every map size.
		int left = g.guiWidth() - mapWidth - 14 - outerFramePadding, top = 14 + outerFramePadding;
		int centerX = left + mapWidth / 2, centerY = top + mapHeight / 2, radius = mapWidth / 2;
		float offsetX = (float)(mc.player.getX() - (anchorX + .5)) / currentStride;
		float offsetZ = (float)(mc.player.getZ() - (anchorZ + .5)) / currentStride;
		float requestedVisibleBlocks = (float)(BASE_VISIBLE_BLOCKS / config.zoom);
		float visibleBlocks = coveredVisibleBlocks(mc, requestedVisibleBlocks);
		float visibleCells = visibleBlocks / currentStride;
		float textureCenter = PADDING + VIEW / 2.0F;
		float u0 = (textureCenter + offsetX - visibleCells / 2) / TEX;
		float visibleVerticalCells = visibleCells * mapHeight / mapWidth;
		float v0 = (textureCenter + offsetZ - visibleVerticalCells / 2) / TEX;
		float u1 = (textureCenter + offsetX + visibleCells / 2) / TEX;
		float v1 = (textureCenter + offsetZ + visibleVerticalCells / 2) / TEX;

		if (!config.circular) {
			g.fill(left - 2, top - 2, left + mapWidth + 2, top + mapHeight + 2, 0xD8000000);
		}

		boolean rotateGeometry = !config.northLocked && !config.circular;
		if (rotateGeometry) {
			g.enableScissor(left, top, left + mapWidth, top + mapHeight);
			g.pose().pushMatrix();
			g.pose().translate(centerX, centerY);
			g.pose().rotate((float)Math.toRadians(180 - mc.player.getYRot()));
			g.pose().scale(config.circular ? 1.08F : 1.42F, config.circular ? 1.08F : 1.42F);
			g.pose().translate(-centerX, -centerY);
		}
		Identifier selectedMap = MAP_ID;
		if (config.circular) refreshCircleTexture(mc, mapWidth, u0, u1, v0, v1, !config.northLocked);
		renderMapTexture(g, config.circular ? CIRCLE_MAP_ID : selectedMap,
				left, top, mapWidth, mapHeight, radius, u0, u1, v0, v1, config.circular);
		if (rotateGeometry) {
			g.pose().popMatrix();
			g.disableScissor();
		}
		if (config.showEntities) renderEntities(g, mc, centerX, centerY, mapWidth, mapHeight, visibleBlocks, config);
		if (config.displayItems) renderItems(g, mc, centerX, centerY, mapWidth, mapHeight, visibleBlocks, config);
		renderWaypoints(g, mc, centerX, centerY, mapWidth, mapHeight, visibleBlocks, config);
		if (config.borderEnabled && config.circular) {
			int frameYOffset = modernBorder ? 2 : 0;
			g.blit(modernBorder ? MODERN_FRAME_ID : FRAME_ID, left - outerFramePadding, top - outerFramePadding + frameYOffset,
					left + mapWidth + outerFramePadding, top + mapHeight + outerFramePadding + frameYOffset, 0, 1, 0, 1);
		} else if (config.borderEnabled) {
			// The artwork has a broad transparent opening, so enlarge it until that opening aligns with the map edge.
			g.blit(modernBorder ? MODERN_SQUARE_FRAME_ID : SQUARE_FRAME_ID, left - outerFramePadding, top - outerFramePadding,
					left + mapWidth + outerFramePadding, top + mapHeight + outerFramePadding, 0, 1, 0, 1);
		}
		drawNorthIndicator(g, mc, centerX, centerY, mapWidth, config.circular, outerFramePadding, config.northLocked);
		if (config.playerMarker.equals("arrow"))
			drawPlayerArrow(g, centerX, centerY, config.northLocked ? mc.player.getYRot() : 180);
		else drawPlayerDot(g, centerX, centerY);
		if (config.worldWaypoints) renderWorldWaypoints(g, mc);
	}

	private static void createDeathpoint(Minecraft mc) {
		MinimapConfig config = MinimapConfig.INSTANCE;
		config.waypoints.removeIf(w -> w.name().equals("Deathpoint"));
		BlockPos pos = mc.player.blockPosition();
		config.waypoints.add(new MinimapConfig.Waypoint("Deathpoint", pos.getX(), pos.getY(), pos.getZ(),
				mc.level.dimension().identifier().toString(), 0xFFFF5555));
		config.save();
	}

	private static void removeReachedDeathpoint(Minecraft mc) {
		String dimension = mc.level.dimension().identifier().toString();
		boolean removed = MinimapConfig.INSTANCE.waypoints.removeIf(w -> w.name().equals("Deathpoint")
				&& w.dimension().equals(dimension)
				&& mc.player.distanceToSqr(w.x() + .5, w.y() + .5, w.z() + .5) <= 16.0);
		if (removed) MinimapConfig.INSTANCE.save();
	}

	private static void drawNorthIndicator(GuiGraphicsExtractor g, Minecraft mc, int centerX, int centerY, int mapWidth,
			boolean circular, int framePadding, boolean northLocked) {
		double angle = northLocked ? 0 : Math.toRadians(180 - mc.player.getYRot());
		double directionX = Math.sin(angle), directionY = -Math.cos(angle);
		double outerRadius = mapWidth / 2.0 + framePadding;
		double distance = circular ? outerRadius + 5
				: outerRadius / Math.max(.001, Math.max(Math.abs(directionX), Math.abs(directionY))) + 5;
		int x = centerX + (int)Math.round(directionX * distance) - 3;
		int y = centerY + (int)Math.round(directionY * distance) - 4;
		g.text(mc.font, "N", x, y, 0xFFFFFFFF, true);
	}

	private static float coveredVisibleBlocks(Minecraft mc, float requested) {
		if (requested <= 192) return requested;
		int centerX = mc.player.blockPosition().getX(), centerZ = mc.player.blockPosition().getZ();
		int half = (int)Math.ceil(requested / 2.0);
		double nearestMissing = half + 16.0;
		// Probe the chunk grid inside the requested circle. Keep a safety band so interpolation never exposes its edge.
		for (int dz = -half; dz <= half; dz += 16) for (int dx = -half; dx <= half; dx += 16) {
			double distance = Math.sqrt((double)dx * dx + (double)dz * dz);
			if (distance > half) continue;
			BlockPos pos = new BlockPos(centerX + dx, mc.player.blockPosition().getY(), centerZ + dz);
			if (!mc.level.hasChunkAt(pos)) nearestMissing = Math.min(nearestMissing, distance);
		}
		float covered = (float)Math.max(96.0, (nearestMissing - 28.0) * 2.0);
		return Math.min(requested, covered);
	}

	private static void renderEntities(GuiGraphicsExtractor g, Minecraft mc, int centerX, int centerY, int width, int height,
			float visibleBlocks, MinimapConfig config) {
		double maxDistance = visibleBlocks * .5;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity == mc.player || entity.isRemoved() || !(entity instanceof LivingEntity)) continue;
			if (Math.abs(entity.getY() - mc.player.getY()) > 10.0) continue;
			double worldDx = entity.getX() - mc.player.getX(), worldDz = entity.getZ() - mc.player.getZ();
			if (worldDx * worldDx + worldDz * worldDz > maxDistance * maxDistance) continue;
			double px = worldDx * width / visibleBlocks, py = worldDz * width / visibleBlocks;
			if (!config.northLocked) {
				double angle = Math.toRadians(180 - mc.player.getYRot());
				double rotatedX = px * Math.cos(angle) - py * Math.sin(angle);
				py = px * Math.sin(angle) + py * Math.cos(angle); px = rotatedX;
			}
			if (config.circular && px * px + py * py > Math.pow(width / 2.0 - 4, 2)) continue;
			if (!config.circular && (Math.abs(px) > width / 2.0 - 3 || Math.abs(py) > height / 2.0 - 3)) continue;
			int x = centerX + (int)Math.round(px), y = centerY + (int)Math.round(py);
			g.fill(x - 1, y - 2, x + 2, y + 3, 0xCC0B223D);
			g.fill(x - 2, y - 1, x + 3, y + 2, 0xCC0B223D);
			g.fill(x, y - 1, x + 1, y + 2, 0xFF4FA8FF);
			g.fill(x - 1, y, x + 2, y + 1, 0xFF4FA8FF);
		}
	}

	private static void renderItems(GuiGraphicsExtractor g, Minecraft mc, int centerX, int centerY, int width, int height,
			float visibleBlocks, MinimapConfig config) {
		double maxDistance = visibleBlocks * .5;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof ItemEntity) || entity.isRemoved() || Math.abs(entity.getY() - mc.player.getY()) > 10.0) continue;
			double worldDx = entity.getX() - mc.player.getX(), worldDz = entity.getZ() - mc.player.getZ();
			if (worldDx * worldDx + worldDz * worldDz > maxDistance * maxDistance) continue;
			double px = worldDx * width / visibleBlocks, py = worldDz * width / visibleBlocks;
			if (!config.northLocked) {
				double angle = Math.toRadians(180 - mc.player.getYRot());
				double rotatedX = px * Math.cos(angle) - py * Math.sin(angle);
				py = px * Math.sin(angle) + py * Math.cos(angle); px = rotatedX;
			}
			if (config.circular && px * px + py * py > Math.pow(width / 2.0 - 4, 2)) continue;
			if (!config.circular && (Math.abs(px) > width / 2.0 - 3 || Math.abs(py) > height / 2.0 - 3)) continue;
			int x = centerX + (int)Math.round(px), y = centerY + (int)Math.round(py);
			g.fill(x - 1, y - 2, x + 2, y + 3, 0xCC301010);
			g.fill(x - 2, y - 1, x + 3, y + 2, 0xCC301010);
			g.fill(x, y - 1, x + 1, y + 2, 0xFFFF4A4A);
			g.fill(x - 1, y, x + 2, y + 1, 0xFFFF4A4A);
		}
	}

	private static void renderMapTexture(GuiGraphicsExtractor g, Identifier textureId, int left, int top, int width, int height, int radius,
			float u0, float u1, float v0, float v1, boolean circular) {
		if (circular) {
			g.blit(textureId, left, top, left + width, top + height, 0, 1, 0, 1);
			return;
		} else {
			g.blit(textureId, left, top, left + width, top + height, u0, u1, v0, v1);
			return;
		}
	}

	private static void renderWaypoints(GuiGraphicsExtractor g, Minecraft mc, int centerX, int centerY, int width, int height,
			float visibleBlocks, MinimapConfig config) {
		String dimension = mc.level.dimension().identifier().toString();
		for (MinimapConfig.Waypoint waypoint : MinimapConfig.INSTANCE.waypoints) {
			if (!waypoint.dimension().equals(dimension)) continue;
			double worldDx = waypoint.x() + .5 - mc.player.getX(), worldDz = waypoint.z() + .5 - mc.player.getZ();
			double distance = Math.sqrt(worldDx * worldDx + worldDz * worldDz);
			double px = worldDx * width / visibleBlocks, py = worldDz * width / visibleBlocks;
			if (!config.northLocked) {
				double angle = Math.toRadians(180 - mc.player.getYRot());
				double rotatedX = px * Math.cos(angle) - py * Math.sin(angle);
				py = px * Math.sin(angle) + py * Math.cos(angle); px = rotatedX;
			}
			boolean outside;
			if (config.circular) {
				double limit = width / 2.0 - 7;
				double length = Math.sqrt(px * px + py * py);
				outside = length > limit;
				if (outside && length > 0) { px = px / length * limit; py = py / length * limit; }
			} else {
				double maxX = width / 2.0 - 7, maxY = height / 2.0 - 7;
				outside = Math.abs(px) > maxX || Math.abs(py) > maxY;
				if (outside) {
					double scale = Math.min(maxX / Math.max(.001, Math.abs(px)), maxY / Math.max(.001, Math.abs(py)));
					px *= scale; py *= scale;
				}
			}
			int markerX = centerX + (int)Math.round(px), markerY = centerY + (int)Math.round(py);
			drawWaypointDot(g, markerX, markerY, waypoint.color());
			if (!outside && distance <= 32) g.text(mc.font, waypoint.name(), markerX + 5, markerY - 4, waypoint.color(), true);
		}
	}

	private static void drawWaypointDot(GuiGraphicsExtractor g, int x, int y, int color) {
		g.fill(x - 3, y - 1, x + 4, y + 2, 0xE0000000);
		g.fill(x - 1, y - 3, x + 2, y + 4, 0xE0000000);
		g.fill(x - 2, y - 1, x + 3, y + 2, color);
		g.fill(x - 1, y - 2, x + 2, y + 3, color);
	}

	private static void renderWorldWaypoints(GuiGraphicsExtractor g, Minecraft mc) {
		String dimension = mc.level.dimension().identifier().toString();
		Camera camera = mc.gameRenderer.mainCamera();
		var cameraPos = camera.position();
		Vector3fc forward = camera.forwardVector(), up = camera.upVector(), left = camera.leftVector();
		double tanHalfFov = Math.tan(Math.toRadians(camera.getFov()) * .5);
		double aspect = (double)g.guiWidth() / g.guiHeight();
		for (MinimapConfig.Waypoint waypoint : MinimapConfig.INSTANCE.waypoints) {
			if (!waypoint.dimension().equals(dimension)) continue;
			double dx = waypoint.x() + .5 - cameraPos.x, dy = waypoint.y() + .5 - cameraPos.y, dz = waypoint.z() + .5 - cameraPos.z;
			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (distance < 4) continue;
			double cameraZ = dx * forward.x() + dy * forward.y() + dz * forward.z();
			if (cameraZ <= .1) continue;
			double cameraX = -(dx * left.x() + dy * left.y() + dz * left.z());
			double cameraY = dx * up.x() + dy * up.y() + dz * up.z();
			double normalizedX = cameraX / (cameraZ * tanHalfFov * aspect);
			double normalizedY = cameraY / (cameraZ * tanHalfFov);
			if (Math.abs(normalizedX) > .94 || Math.abs(normalizedY) > .9) continue;
			int x = g.guiWidth() / 2 + (int)Math.round(normalizedX * g.guiWidth() / 2);
			int y = g.guiHeight() / 2 - (int)Math.round(normalizedY * g.guiHeight() / 2);
			if (distance > 150) {
				drawWorldWaypointCircle(g, x, y, waypoint.color());
			} else {
				String label = waypoint.name() + "  " + (int)Math.round(distance) + "m";
				g.centeredText(mc.font, label, x, y, waypoint.color());
				drawWaypointDot(g, x, y - 5, waypoint.color());
			}
		}
	}

	private static void drawWorldWaypointCircle(GuiGraphicsExtractor g, int x, int y, int color) {
		g.fill(x - 2, y - 3, x + 3, y + 4, 0xE0000000);
		g.fill(x - 3, y - 2, x + 4, y + 3, 0xE0000000);
		g.fill(x - 1, y - 2, x + 2, y + 3, color);
		g.fill(x - 2, y - 1, x + 3, y + 2, color);
	}

	private static void ensureTexture(Minecraft mc) {
		int requestedScale = MinimapConfig.INSTANCE.resolutionScale;
		if (texture != null && loadedTextureScale == requestedScale) return;
		if (texture != null) {
			mc.getTextureManager().release(MAP_ID);
		}
		loadedTextureScale = requestedScale;
		int textureSize = TEX * loadedTextureScale;
		texture = new DynamicTexture(() -> "Mat's Minimap", textureSize, textureSize, true);
		mc.getTextureManager().register(MAP_ID, texture);
		if (anchorX != Integer.MIN_VALUE) upload();
	}

	private static void refreshRotatedTexture(Minecraft mc) {
		float yaw = mc.player.getYRot();
		float difference = Float.isNaN(rotatedYaw) ? 360 : Math.abs((yaw - rotatedYaw + 540) % 360 - 180);
		if (rotatedGeneration == textureGeneration && difference < 2.0F) return;
		NativeImage source = texture.getPixels(), destination = rotatedTexture.getPixels();
		if (source == null || destination == null) return;
		int size = TEX * loadedTextureScale;
		double center = (size - 1) * .5;
		double angle = Math.toRadians(180 - yaw), cosine = Math.cos(angle), sine = Math.sin(angle);
		for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
			double dx = x - center, dy = y - center;
			double sourceX = center + cosine * dx + sine * dy;
			double sourceY = center - sine * dx + cosine * dy;
			destination.setPixel(x, y, sourceX >= 0 && sourceX < size - 1 && sourceY >= 0 && sourceY < size - 1
					? rotatedPixel(source, sourceX, sourceY, loadedTextureScale <= 2) : 0xFF252B2E);
		}
		rotatedTexture.upload();
		rotatedGeneration = textureGeneration;
		rotatedYaw = yaw;
	}

	private static int rotatedPixel(NativeImage image, double x, double y, boolean smooth) {
		if (!smooth) return image.getPixel((int)Math.round(x), (int)Math.round(y));
		int x0 = (int)x, y0 = (int)y, x1 = x0 + 1, y1 = y0 + 1;
		double fx = x - x0, fy = y - y0;
		int a = image.getPixel(x0, y0), b = image.getPixel(x1, y0), c = image.getPixel(x0, y1), d = image.getPixel(x1, y1);
		int alpha = bilinearChannel(a >>> 24, b >>> 24, c >>> 24, d >>> 24, fx, fy);
		int red = bilinearChannel(a >> 16 & 255, b >> 16 & 255, c >> 16 & 255, d >> 16 & 255, fx, fy);
		int green = bilinearChannel(a >> 8 & 255, b >> 8 & 255, c >> 8 & 255, d >> 8 & 255, fx, fy);
		int blue = bilinearChannel(a & 255, b & 255, c & 255, d & 255, fx, fy);
		return alpha << 24 | red << 16 | green << 8 | blue;
	}

	private static int bilinearChannel(int a, int b, int c, int d, double fx, double fy) {
		return clamp((int)Math.round((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy));
	}

	private static void refreshCircleTexture(Minecraft mc, int size, float u0, float u1, float v0, float v1, boolean rotated) {
		int generation = textureGeneration;
		float yaw = mc.player.getYRot();
		float yawDifference = Float.isNaN(circleYaw) ? 360 : Math.abs((yaw - circleYaw + 540) % 360 - 180);
		float uThreshold = Math.abs(u1 - u0) / size * .75F, vThreshold = Math.abs(v1 - v0) / size * .75F;
		boolean coordinatesChanged = Math.abs(u0 - circleU0) > uThreshold || Math.abs(u1 - circleU1) > uThreshold
				|| Math.abs(v0 - circleV0) > vThreshold || Math.abs(v1 - circleV1) > vThreshold;
		if (circleTexture != null && circleTextureSize == size && circleGeneration == generation
				&& circleRotated == rotated && (!rotated || yawDifference < 2.0F) && !coordinatesChanged) return;
		if (circleTexture == null || circleTextureSize != size) {
			if (circleTexture != null) mc.getTextureManager().release(CIRCLE_MAP_ID);
			circleTextureSize = size;
			circleTexture = new DynamicTexture(() -> "Mat's Minimap Circle", size, size, true);
			mc.getTextureManager().register(CIRCLE_MAP_ID, circleTexture);
		}
		NativeImage source = texture.getPixels();
		NativeImage destination = circleTexture.getPixels();
		if (source == null || destination == null) return;
		int sourceSize = TEX * loadedTextureScale;
		double radius = size * .5 - .5;
		double centerSourceX = (u0 + u1) * .5 * sourceSize - .5;
		double centerSourceY = (v0 + v1) * .5 * sourceSize - .5;
		double angle = rotated ? Math.toRadians(180 - yaw) : 0;
		double cosine = Math.cos(angle), sine = Math.sin(angle);
		for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
			double localX = ((x + .5) / size - .5) * (u1 - u0) * sourceSize;
			double localY = ((y + .5) / size - .5) * (v1 - v0) * sourceSize;
			double sourceX = Math.max(0, Math.min(sourceSize - 1.001, centerSourceX + cosine * localX + sine * localY));
			double sourceY = Math.max(0, Math.min(sourceSize - 1.001, centerSourceY - sine * localX + cosine * localY));
			int colour = rotatedPixel(source, sourceX, sourceY, loadedTextureScale <= 2);
			double dx = x + .5 - size * .5, dy = y + .5 - size * .5;
			double coverage = Math.max(0, Math.min(1, radius + .75 - Math.sqrt(dx * dx + dy * dy)));
			int alpha = (int)Math.round((colour >>> 24) * coverage);
			destination.setPixel(x, y, colour & 0x00FFFFFF | alpha << 24);
		}
		circleTexture.upload();
		circleGeneration = generation;
		circleRotated = rotated;
		circleYaw = yaw;
		circleU0 = u0; circleU1 = u1; circleV0 = v0; circleV1 = v1;
	}

	private static void updateForMovement(Minecraft mc) {
		int x = mc.player.blockPosition().getX(), y = mc.player.blockPosition().getY(), z = mc.player.blockPosition().getZ();
		String dimension = mc.level.dimension().identifier().toString();
		boolean dimensionChanged = !dimension.equals(activeDimension);
		// The full 0.5x view is sampled block-for-block; zooming out no longer skips every other block.
		int requestedStride = 1;
		int targetX = Math.floorDiv(x, requestedStride) * requestedStride;
		int targetZ = Math.floorDiv(z, requestedStride) * requestedStride;
		int surfaceY = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		int burialDepth = surfaceY - y;
		// Hysteresis prevents trees and ordinary roofs from toggling cave mode while retaining deep caves.
		boolean newCaveMode = caveMode ? burialDepth >= 5 : burialDepth >= 8;
		if (anchorX == Integer.MIN_VALUE || dimensionChanged || newCaveMode != caveMode || requestedStride != currentStride) {
			currentStride = requestedStride;
			anchorX = targetX; anchorY = y; anchorZ = targetZ; caveMode = newCaveMode; activeDimension = dimension;
			resampleAll(mc); upload(); return;
		}
		if (caveMode && Math.abs(y - anchorY) >= 2) {
			anchorX = targetX; anchorY = y; anchorZ = targetZ;
			resampleAll(mc); upload(); return;
		}
		int dx = (targetX - anchorX) / currentStride, dz = (targetZ - anchorZ) / currentStride;
		if (dx == 0 && dz == 0) return;
		if (Math.abs(dx) >= PADDING || Math.abs(dz) >= PADDING) {
			anchorX = targetX; anchorY = y; anchorZ = targetZ; resampleAll(mc); upload(); return;
		}

		int[][] oldColours = copy(COLOURS), oldHeights = copy(HEIGHTS);
		boolean[][] oldKnown = copy(KNOWN);
		anchorX = targetX; anchorY = y; anchorZ = targetZ;
		for (int py = 0; py < TEX; py++) for (int px = 0; px < TEX; px++) {
			int sx = px + dx, sy = py + dz;
			if (sx >= 0 && sx < TEX && sy >= 0 && sy < TEX) {
				COLOURS[py][px] = oldColours[sy][sx]; HEIGHTS[py][px] = oldHeights[sy][sx]; KNOWN[py][px] = oldKnown[sy][sx];
			} else sampleInto(mc, px, py);
		}
		upload();
	}

	private static int[][] copy(int[][] source) {
		int[][] result = new int[TEX][TEX];
		for (int y = 0; y < TEX; y++) System.arraycopy(source[y], 0, result[y], 0, TEX);
		return result;
	}

	private static boolean[][] copy(boolean[][] source) {
		boolean[][] result = new boolean[TEX][TEX];
		for (int y = 0; y < TEX; y++) System.arraycopy(source[y], 0, result[y], 0, TEX);
		return result;
	}

	private static void resampleAll(Minecraft mc) {
		refreshCursor = 0;
		for (int y = 0; y < TEX; y++) for (int x = 0; x < TEX; x++) sampleInto(mc, x, y);
	}

	private static void sampleInto(Minecraft mc, int px, int py) {
		int worldX = anchorX + (px - TEX / 2) * currentStride;
		int worldZ = anchorZ + (py - TEX / 2) * currentStride;
		KNOWN[py][px] = terrainReady(mc, worldX, worldZ);
		Sample s = sample(mc, worldX, worldZ, anchorY);
		COLOURS[py][px] = s.colour; HEIGHTS[py][px] = s.height;
	}

	private static void refreshNewlyLoadedTerrain(Minecraft mc) {
		boolean changed = false;
		int checked = 0, total = TEX * TEX;
		while (checked++ < 1024) {
			int index = refreshCursor++ % total;
			int px = index % TEX, py = index / TEX;
			if (KNOWN[py][px]) continue;
			int worldX = anchorX + (px - TEX / 2) * currentStride;
			int worldZ = anchorZ + (py - TEX / 2) * currentStride;
			if (!terrainReady(mc, worldX, worldZ)) continue;
			sampleInto(mc, px, py);
			changed = true;
		}
		if (changed) upload();
	}

	private static boolean terrainReady(Minecraft mc, int worldX, int worldZ) {
		if (!mc.level.hasChunkAt(new BlockPos(worldX, anchorY, worldZ))) return false;
		if (caveMode) return true;
		int surfaceY = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1;
		if (surfaceY < mc.level.getMinY()) return false;
		return !mc.level.getBlockState(new BlockPos(worldX, surfaceY, worldZ)).isAir();
	}

	private static void upload() {
		NativeImage image = texture.getPixels();
		if (image == null) return;
		int textureScale = loadedTextureScale;
		int renderTextureSize = TEX * textureScale;
		int[][] shaded = new int[TEX][TEX];
		int[][] smoothHeights = new int[TEX][TEX];
		for (int y = 0; y < TEX; y++) for (int x = 0; x < TEX; x++) {
			int west = HEIGHTS[y][Math.max(0, x - 1)], east = HEIGHTS[y][Math.min(TEX - 1, x + 1)];
			int north = HEIGHTS[Math.max(0, y - 1)][x], south = HEIGHTS[Math.min(TEX - 1, y + 1)][x];
			smoothHeights[y][x] = (HEIGHTS[y][x] * 4 + west + east + north + south) / 8;
		}
		for (int y = 0; y < TEX; y++) for (int x = 0; x < TEX; x++) {
			int current = smoothHeights[y][x];
			int west = smoothHeights[y][Math.max(0, x - 1)], east = smoothHeights[y][Math.min(TEX - 1, x + 1)];
			int north = smoothHeights[Math.max(0, y - 1)][x], south = smoothHeights[Math.min(TEX - 1, y + 1)][x];
			int gradientX = east - west, gradientZ = south - north;
			int directionalLight = (gradientX + gradientZ) * 5;
			int slope = Math.abs(gradientX) + Math.abs(gradientZ);
			int higherNeighbour = Math.max(Math.max(west, east), Math.max(north, south));
			int lowerNeighbour = Math.min(Math.min(west, east), Math.min(north, south));
			int sideShadow = Math.max(0, higherNeighbour - current) * 5;
			int crestLight = Math.max(0, current - lowerNeighbour) * 2;
			int relief = directionalLight - Math.min(12, slope) - sideShadow + Math.min(12, crestLight);
			relief = Math.max(-40, Math.min(34, relief));
			shaded[y][x] = adjustContrast(COLOURS[y][x], relief);
		}
		// Material colours stay flat; all light/dark variation above comes only from terrain height and slope.
		for (int y = 0; y < renderTextureSize; y++) for (int x = 0; x < renderTextureSize; x++) {
			int sourceX = x / textureScale, sourceY = y / textureScale;
			image.setPixel(x, y, shaded[sourceY][sourceX]);
		}
		texture.upload();
		textureGeneration++;
	}


	private static Sample sample(Minecraft mc, int x, int z, int playerY) {
		BlockPos check = new BlockPos(x, playerY, z);
		SurfaceKey cacheKey = new SurfaceKey(mc.level.dimension().identifier().toString(), x, z);
		if (!mc.level.hasChunkAt(check)) {
			if (!caveMode) {
				Sample cached = SURFACE_CACHE.get(cacheKey);
				if (cached != null) return cached;
			}
			return new Sample(0xFF252B2E, playerY);
		}
		if (!caveMode) {
			int y = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
			BlockPos pos = new BlockPos(x, y, z);
			BlockState surface = mc.level.getBlockState(pos);
			Sample result = surface.is(Blocks.WATER) ? sampleWater(mc, x, y, z) : new Sample(baseColour(surface, mc, pos), y);
			SURFACE_CACHE.put(cacheKey, result);
			return result;
		}
		// Only inspect the player's underground layer. Never fall back to the surface heightmap.
		for (int y = playerY + 3; y >= playerY - 32; y--) {
			BlockPos pos = new BlockPos(x, y, z);
			BlockState open = mc.level.getBlockState(pos), floor = mc.level.getBlockState(pos.below());
			if (open.isAir() && !floor.isAir() && floor.getFluidState().isEmpty())
				return new Sample(adjust(baseColour(floor, mc, pos.below()), -20 + (y - playerY) * 3), y - 1);
		}
		return new Sample(0xFF171A1D, playerY - 20);
	}

	private static Sample sampleWater(Minecraft mc, int x, int surfaceY, int z) {
		int bottomY = surfaceY;
		while (bottomY > mc.level.getMinY() && surfaceY - bottomY < 32) {
			BlockState state = mc.level.getBlockState(new BlockPos(x, bottomY, z));
			if (state.getFluidState().isEmpty()) break;
			bottomY--;
		}
		BlockPos bottomPos = new BlockPos(x, bottomY, z);
		int bottom = baseColour(mc.level.getBlockState(bottomPos), mc, bottomPos);
		int depth = Math.max(1, surfaceY - bottomY);
		int biomeWater = 0xFF000000 | mc.level.getBiome(new BlockPos(x, surfaceY, z)).value().getWaterColor();
		float blueAmount = Math.min(.72F, .40F + depth * .035F);
		return new Sample(blend(bottom, biomeWater, blueAmount), surfaceY);
	}

	private static int baseColour(BlockState state, Minecraft mc, BlockPos pos) {
		if (state.is(BlockTags.LEAVES)) return 0xFF3F6841;
		if (state.is(BlockTags.LOGS)) return 0xFF725A42;
		if (state.is(Blocks.WATER)) return 0xFF3F7EB8;
		if (state.is(Blocks.LAVA)) return 0xFFD06A35;
		if (state.is(Blocks.GRASS_BLOCK)) return 0xFF718D59;
		if (state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS) || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)) return 0xFF688357;
		if (state.is(Blocks.MOSS_BLOCK)) return 0xFF58734A;
		if (state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT)) return 0xFF78634D;
		if (state.is(Blocks.STONE) || state.is(Blocks.ANDESITE) || state.is(Blocks.GRAVEL)) return 0xFF7B8080;
		if (state.is(Blocks.GRANITE)) return 0xFF8A6A5C;
		if (state.is(Blocks.DIORITE)) return 0xFFAAA9A3;
		if (state.is(Blocks.SAND) || state.is(Blocks.SANDSTONE)) return 0xFFC1B17E;
		if (state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.SNOW)) return 0xFFDDE2E3;
		if (state.is(Blocks.DEEPSLATE)) return 0xFF50545A;
		if (state.is(Blocks.NETHERRACK)) return 0xFF7A4642;
		if (state.is(Blocks.END_STONE)) return 0xFFC6C49A;
		return 0xFF000000 | muted(state.getMapColor(mc.level, pos).col);
	}

	private static void drawPlayerDot(GuiGraphicsExtractor g, int cx, int cy) {
		g.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xD0000000);
		g.fill(cx, cy - 1, cx + 1, cy + 2, 0xFFB8BDC3);
		g.fill(cx - 1, cy, cx + 2, cy + 1, 0xFFB8BDC3);
	}

	private static void drawPlayerArrow(GuiGraphicsExtractor g, int cx, int cy, float yawDegrees) {
		double angle = Math.toRadians(yawDegrees);
		int tipX = cx + (int)Math.round(-Math.sin(angle) * 4), tipY = cy + (int)Math.round(Math.cos(angle) * 4);
		int leftX = cx + (int)Math.round(-Math.sin(angle + 2.35) * 3), leftY = cy + (int)Math.round(Math.cos(angle + 2.35) * 3);
		int rightX = cx + (int)Math.round(-Math.sin(angle - 2.35) * 3), rightY = cy + (int)Math.round(Math.cos(angle - 2.35) * 3);
		drawLine(g, tipX, tipY, leftX, leftY, 0xE0000000, 3);
		drawLine(g, tipX, tipY, rightX, rightY, 0xE0000000, 3);
		drawLine(g, leftX, leftY, rightX, rightY, 0xE0000000, 3);
		drawLine(g, tipX, tipY, leftX, leftY, 0xFFFF3028, 1);
		drawLine(g, tipX, tipY, rightX, rightY, 0xFFFF3028, 1);
		drawLine(g, leftX, leftY, rightX, rightY, 0xFFFF3028, 1);
		g.fill(cx, cy, cx + 1, cy + 1, 0xFFFF3028);
	}

	private static void drawLine(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int colour, int size) {
		int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1, dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1, err = dx + dy;
		while (true) {
			g.fill(x0 - size / 2, y0 - size / 2, x0 + (size + 1) / 2, y0 + (size + 1) / 2, colour);
			if (x0 == x1 && y0 == y1) break;
			int e2 = 2 * err; if (e2 >= dy) { err += dy; x0 += sx; } if (e2 <= dx) { err += dx; y0 += sy; }
		}
	}

	private static int muted(int rgb) {
		int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255, avg = (r + g + b) / 3;
		r = (r * 4 + avg) / 5; g = (g * 4 + avg) / 5; b = (b * 4 + avg) / 5;
		return r << 16 | g << 8 | b;
	}
	private static int adjust(int argb, int n) { return argb & 0xFF000000 | clamp((argb >> 16 & 255) + n) << 16 | clamp((argb >> 8 & 255) + n) << 8 | clamp((argb & 255) + n); }
	private static int adjustContrast(int argb, int light) {
		int r = argb >> 16 & 255, g = argb >> 8 & 255, b = argb & 255;
		// A mild contrast curve keeps flat terrain readable while making shaded rock faces distinct.
		r = clamp(128 + (r - 128) * 11 / 10 + light);
		g = clamp(128 + (g - 128) * 11 / 10 + light);
		b = clamp(128 + (b - 128) * 11 / 10 + light);
		return argb & 0xFF000000 | r << 16 | g << 8 | b;
	}
	private static int colourDistance(int a, int b) {
		return Math.abs((a >> 16 & 255) - (b >> 16 & 255))
				+ Math.abs((a >> 8 & 255) - (b >> 8 & 255)) + Math.abs((a & 255) - (b & 255));
	}
	private static int blend(int from, int to, float amount) {
		float inverse = 1.0F - amount;
		int r = clamp(Math.round((from >> 16 & 255) * inverse + (to >> 16 & 255) * amount));
		int g = clamp(Math.round((from >> 8 & 255) * inverse + (to >> 8 & 255) * amount));
		int b = clamp(Math.round((from & 255) * inverse + (to & 255) * amount));
		return 0xFF000000 | r << 16 | g << 8 | b;
	}
	private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
	private record SurfaceKey(String dimension, int x, int z) { }
	private record Sample(int colour, int height) { }
}
