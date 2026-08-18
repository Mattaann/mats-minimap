package se.kjall.nordicminimap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class WaypointEditScreen extends Screen {
	private final Screen parent;
	private final int index;
	private EditBox name;
	private int color;

	WaypointEditScreen(Screen parent, int index) {
		super(Component.literal("Edit Waypoint"));
		this.parent = parent;
		this.index = index;
		this.color = MinimapConfig.INSTANCE.waypoints.get(index).color();
	}

	@Override protected void init() {
		int center = width / 2;
		MinimapConfig.Waypoint waypoint = MinimapConfig.INSTANCE.waypoints.get(index);
		name = new EditBox(font, center - 110, height / 2 - 42, 220, 20, Component.literal("Waypoint name"));
		name.setValue(waypoint.name());
		name.setMaxLength(32);
		addRenderableWidget(name);
		addRenderableWidget(CycleButton.<Integer>builder(WaypointCreateScreen::colorName, color)
				.withValues(WaypointCreateScreen.COLORS)
				.create(center - 110, height / 2 - 14, 220, 20, Component.literal("Color"), (button, value) -> color = value));
		addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
				.bounds(center - 110, height / 2 + 22, 105, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
				.bounds(center + 5, height / 2 + 22, 105, 20).build());
		setInitialFocus(name);
	}

	private void save() {
		MinimapConfig config = MinimapConfig.INSTANCE;
		if (index >= config.waypoints.size()) { onClose(); return; }
		MinimapConfig.Waypoint old = config.waypoints.get(index);
		String newName = name.getValue().trim();
		if (newName.isEmpty()) newName = old.name();
		config.waypoints.set(index, new MinimapConfig.Waypoint(newName, old.x(), old.y(), old.z(), old.dimension(), color));
		config.save();
		onClose();
	}

	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, height, 0x7010141A);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, "Edit Waypoint", width / 2, height / 2 - 70, 0xFFFFFFFF);
		graphics.fill(width / 2 - 4, height / 2 + 12, width / 2 + 5, height / 2 + 17, color);
	}

	@Override public void onClose() { Minecraft.getInstance().setScreenAndShow(parent); }
	@Override public boolean isPauseScreen() { return false; }
}
