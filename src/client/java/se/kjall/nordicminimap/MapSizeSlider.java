package se.kjall.nordicminimap;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

final class MapSizeSlider extends AbstractSliderButton {
	private final MinimapConfig config;
	MapSizeSlider(int x, int y, int width, MinimapConfig config) {
		super(x, y, width, 20, Component.empty(), (config.mapSize - 80) / 120.0);
		this.config = config;
		updateMessage();
	}
	@Override protected void updateMessage() { setMessage(Component.literal("Map Size: " + config.mapSize + " px")); }
	@Override protected void applyValue() {
		config.mapSize = 80 + (int)Math.round(value * 12) * 10;
		config.save();
		updateMessage();
	}
}
