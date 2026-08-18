package se.kjall.nordicminimap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

final class WaypointCreateScreen extends Screen {
	static final List<Integer> COLORS = List.of(
			0xFFFF5555, 0xFFFF9F43, 0xFFFFD53D, 0xFF55CC66,
			0xFF4DDDDD, 0xFF5599FF, 0xFFB46CFF, 0xFFFFFFFF);
	private EditBox name;
	private int color = 0xFFFFD53D;

	WaypointCreateScreen() { super(Component.literal("Create Waypoint")); }

	@Override protected void init() {
		int center = width / 2;
		name = new EditBox(font, center - 110, height / 2 - 42, 220, 20, Component.literal("Waypoint name"));
		name.setHint(Component.literal("Home, End Portal, Village..."));
		name.setMaxLength(32);
		addRenderableWidget(name);
		addRenderableWidget(CycleButton.<Integer>builder(WaypointCreateScreen::colorName, color)
				.withValues(COLORS).create(center - 110, height / 2 - 14, 220, 20, Component.literal("Color"), (button, value) -> color = value));
		addRenderableWidget(Button.builder(Component.literal("Create"), b -> create())
				.bounds(center - 110, height / 2 + 22, 105, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
				.bounds(center + 5, height / 2 + 22, 105, 20).build());
		setInitialFocus(name);
	}

	private void create() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;
		String label = name.getValue().trim();
		if (label.isEmpty()) label = "Waypoint " + (MinimapConfig.INSTANCE.waypoints.size() + 1);
		var pos = mc.player.blockPosition();
		MinimapConfig.INSTANCE.waypoints.add(new MinimapConfig.Waypoint(label, pos.getX(), pos.getY(), pos.getZ(),
				mc.level.dimension().identifier().toString(), color));
		MinimapConfig.INSTANCE.save();
		onClose();
	}

	static Component colorName(int color) {
		return Component.literal(switch (color) {
			case 0xFFFF5555 -> "Red"; case 0xFFFF9F43 -> "Orange"; case 0xFFFFD53D -> "Yellow";
			case 0xFF55CC66 -> "Green"; case 0xFF4DDDDD -> "Cyan"; case 0xFF5599FF -> "Blue";
			case 0xFFB46CFF -> "Purple"; default -> "White";
		});
	}

	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, height, 0x6010141A);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, "Create Waypoint", width / 2, height / 2 - 70, 0xFFFFFFFF);
		graphics.fill(width / 2 - 4, height / 2 + 12, width / 2 + 5, height / 2 + 17, color);
	}

	@Override public void onClose() { Minecraft.getInstance().setScreenAndShow(null); }
	@Override public boolean isPauseScreen() { return false; }
}
