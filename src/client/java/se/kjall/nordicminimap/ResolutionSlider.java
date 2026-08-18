package se.kjall.nordicminimap;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

final class ResolutionSlider extends AbstractSliderButton {
	private static final int[] SCALES = {1, 3, 5, 7};
	private static final String[] NAMES = {"Low", "Medium", "High", "Ultra"};
	private final MinimapConfig config;

	ResolutionSlider(int x, int y, int width, MinimapConfig config) {
		super(x, y, width, 20, Component.empty(), indexOf(config.resolutionScale) / 3.0);
		this.config = config;
		updateMessage();
	}

	@Override protected void updateMessage() {
		int index = indexOf(config.resolutionScale), pixels = 100 * SCALES[index];
		setMessage(Component.literal("Texture Resolution: " + NAMES[index] + " (" + pixels + "x" + pixels + ")"));
	}

	@Override protected void applyValue() {
		config.resolutionScale = SCALES[(int)Math.round(value * 3)];
		config.save();
		updateMessage();
	}

	private static int indexOf(int scale) {
		for (int i = 0; i < SCALES.length; i++) if (SCALES[i] == scale) return i;
		return 0;
	}
}
