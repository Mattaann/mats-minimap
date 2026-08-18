package se.kjall.nordicminimap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class MinimapSettingsScreen extends Screen {
	private final MinimapConfig config = MinimapConfig.INSTANCE;
	private int page;
	private int waypointListPage;
	private EditBox waypointName;

	MinimapSettingsScreen() { super(Component.literal("Mat's Minimap")); }

	@Override protected void init() {
		int center = width / 2;
		addRenderableWidget(Button.builder(Component.literal("Map"), b -> { page = 0; rebuildWidgets(); })
				.bounds(center - 155, 42, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Waypoints"), b -> { page = 1; rebuildWidgets(); })
				.bounds(center - 50, 42, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Misc"), b -> { page = 2; rebuildWidgets(); })
				.bounds(center + 55, 42, 100, 20).build());
		if (page == 2) initMiscOptions(center); else if (page == 1) initWaypoints(center); else initMapOptions(center);
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(center - 75, height - 34, 150, 20).build());
	}

	private void initMapOptions(int center) {
		addRenderableWidget(CycleButton.booleanBuilder(Component.literal("North Locked"), Component.literal("Player Direction"), config.northLocked)
				.create(center - 100, 76, 200, 20, Component.literal("Orientation"), (button, value) -> { config.northLocked = value; config.save(); }));
		addRenderableWidget(CycleButton.booleanBuilder(Component.literal("Circle"), Component.literal("Square"), config.circular)
				.create(center - 100, 104, 200, 20, Component.literal("Shape"), (button, value) -> { config.circular = value; config.save(); }));
		addRenderableWidget(new MapSizeSlider(center - 100, 132, 200, config));
		addRenderableWidget(CycleButton.<Double>builder(value -> Component.literal(String.format("%.1fx", value)), config.zoom)
				.withValues(0.5, 1.0, 2.0)
				.create(center - 100, 160, 200, 20, Component.literal("Zoom"), (button, value) -> { config.zoom = value; config.save(); }));
		addRenderableWidget(new ResolutionSlider(center - 100, 188, 200, config));
		addRenderableWidget(CycleButton.onOffBuilder(config.borderEnabled)
				.create(center - 100, 216, 200, 20, Component.literal("Border"),
						(button, value) -> { config.borderEnabled = value; config.save(); }));
		addRenderableWidget(CycleButton.<String>builder(value -> Component.literal(value.equals("modern") ? "Modern" : "Old"), config.borderStyle)
				.withValues("old", "modern")
				.create(center - 100, 244, 200, 20, Component.literal("Border Style"),
						(button, value) -> { config.borderStyle = value; config.save(); }));
		addRenderableWidget(CycleButton.<String>builder(value -> Component.literal(value.equals("arrow") ? "Red Arrow" : "Gray Dot"), config.playerMarker)
				.withValues("dot", "arrow")
				.create(center - 100, 272, 200, 20, Component.literal("Player Marker"),
						(button, value) -> { config.playerMarker = value; config.save(); }));
	}

	private void initMiscOptions(int center) {
		addRenderableWidget(CycleButton.onOffBuilder(config.showEntities)
				.create(center - 100, 82, 200, 20, Component.literal("Show Entities"),
						(button, value) -> { config.showEntities = value; config.save(); }));
		addRenderableWidget(CycleButton.onOffBuilder(config.worldWaypoints)
				.create(center - 100, 110, 200, 20, Component.literal("World Waypoints"),
						(button, value) -> { config.worldWaypoints = value; config.save(); }));
		addRenderableWidget(CycleButton.onOffBuilder(config.displayItems)
				.create(center - 100, 138, 200, 20, Component.literal("Display Items"),
						(button, value) -> { config.displayItems = value; config.save(); }));
		addRenderableWidget(CycleButton.onOffBuilder(config.deathpoint)
				.create(center - 100, 166, 200, 20, Component.literal("Deathpoint"),
						(button, value) -> { config.deathpoint = value; config.save(); }));
	}

	private void initWaypoints(int center) {
		waypointName = new EditBox(font, center - 155, 76, 205, 20, Component.literal("Waypoint name"));
		waypointName.setHint(Component.literal("Waypoint name"));
		waypointName.setMaxLength(32);
		addRenderableWidget(waypointName);
		addRenderableWidget(Button.builder(Component.literal("Add Current Position"), b -> addCurrentWaypoint())
				.bounds(center + 55, 76, 155, 20).build());

		int pageCount = Math.max(1, (config.waypoints.size() + 5) / 6);
		waypointListPage = Math.min(waypointListPage, pageCount - 1);
		int offset = waypointListPage * 6;
		int shown = Math.min(6, config.waypoints.size() - offset);
		for (int i = 0; i < shown; i++) {
			int index = offset + i;
			MinimapConfig.Waypoint w = config.waypoints.get(index);
			String label = w.name() + "  [" + w.x() + ", " + w.y() + ", " + w.z() + "]";
			addRenderableWidget(Button.builder(Component.literal(label), b -> Minecraft.getInstance().setScreenAndShow(new WaypointEditScreen(this, index)))
					.bounds(center - 155, 108 + i * 24, 260, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
				config.waypoints.remove(index); config.save(); rebuildWidgets();
			}).bounds(center + 110, 108 + i * 24, 100, 20).build());
		}
		if (pageCount > 1) {
			addRenderableWidget(Button.builder(Component.literal("Previous"), b -> { waypointListPage--; rebuildWidgets(); })
					.bounds(center - 155, 256, 100, 20).build()).active = waypointListPage > 0;
			addRenderableWidget(Button.builder(Component.literal("Next"), b -> { waypointListPage++; rebuildWidgets(); })
					.bounds(center + 110, 256, 100, 20).build()).active = waypointListPage < pageCount - 1;
		}
	}

	private void addCurrentWaypoint() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return;
		String name = waypointName.getValue().trim();
		if (name.isEmpty()) name = "Waypoint " + (config.waypoints.size() + 1);
		var p = mc.player.blockPosition();
		config.waypoints.add(new MinimapConfig.Waypoint(name, p.getX(), p.getY(), p.getZ(), mc.level.dimension().identifier().toString(), 0xFFFFD53D));
		config.save();
		rebuildWidgets();
	}

	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, height, 0x7010141A);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, "Mat's Minimap", width / 2, 18, 0xFFFFFFFF);
		if (page == 1 && config.waypoints.size() > 6)
			graphics.centeredText(font, "Page " + (waypointListPage + 1) + " / " + ((config.waypoints.size() + 5) / 6), width / 2, height - 52, 0xFFAAAAAA);
	}

	@Override public void onClose() {
		config.save();
		Minecraft.getInstance().setScreenAndShow(null);
	}

	@Override public boolean isPauseScreen() { return false; }
}
