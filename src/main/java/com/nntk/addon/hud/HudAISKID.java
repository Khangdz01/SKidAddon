package com.nntk.addon.hud;

import com.nntk.addon.AISKID;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

public class HudAISKID extends HudElement {
    /**
     * The {@code name} parameter should be in kebab-case.
     */
    public static final HudElementInfo<HudAISKID> INFO = new HudElementInfo<>(AISKID.HUD_GROUP, "aiskid", "AISKID HUD element.", HudAISKID::new);

    public HudAISKID() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        setSize(renderer.textWidth("AISKID", true), renderer.textHeight(true));

        // Render background
        renderer.quad(x, y, getWidth(), getHeight(), Color.LIGHT_GRAY);

        // Render text
        renderer.text("AISKID", x, y, Color.WHITE, true);
    }
}
