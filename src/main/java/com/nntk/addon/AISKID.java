package com.nntk.addon;

import com.nntk.addon.commands.CommandAISKID;
import com.nntk.addon.hud.HudAISKID;
import com.nntk.addon.modules.Main.AutoLogin;
import com.nntk.addon.modules.Main.BlazeLoop;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class AISKID extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("AISKID");
    public static final HudGroup HUD_GROUP = new HudGroup("AISKID");

    @Override
    public void onInitialize() {
        LOG.info("Initializing AISKID Addon");

        // Modules
        Modules.get().add(new BlazeLoop());
        Modules.get().add(new AutoLogin());

        // Commands
        Commands.add(new CommandAISKID());

        // HUD
        Hud.get().register(HudAISKID.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.nntk.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("NNTK", "AISKID");
    }
}
