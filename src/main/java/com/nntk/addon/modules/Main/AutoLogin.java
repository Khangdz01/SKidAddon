package com.nntk.addon.modules.Main;

import com.nntk.addon.AISKID;
import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

public class AutoLogin extends Module {
    private final SettingGroup sgGeneral;
    private final SettingGroup sgLobby;
    private final Setting<Integer> delayTicks;
    private final Setting<String> universalCommand;
    private final Setting<Boolean> chatTrigger;

    private final Setting<Boolean> autoJoinLobby;
    private final Setting<Integer> lobbyDelayTicks;
    private final Setting<String> targetServerItemName;
    private final Setting<Integer> fallbackSlot;

    private final Map<String, String> serverCommands;

    private State state;
    private int timer;
    private String cachedCommand;
    private int lobbyTimeout;
    private int lobbyRetryCount;

    public AutoLogin() {
        super(AISKID.CATEGORY, "auto-login", "Tự động đăng nhập (/dn) khi vào/reconnect server & tự chọn KingSMP qua đồng hồ/lệnh.");
        this.sgGeneral = this.settings.getDefaultGroup();
        this.sgLobby = this.settings.createGroup("Lobby Auto-Join");

        this.delayTicks = this.sgGeneral.add(new IntSetting.Builder()
                .name("delay-ticks")
                .description("Thời gian chờ trước khi gửi lệnh đăng nhập (20 ticks = 1s).")
                .defaultValue(25)
                .min(0)
                .sliderMax(200)
                .build());

        this.universalCommand = this.sgGeneral.add(new StringSetting.Builder()
                .name("universal-command")
                .description("Lệnh đăng nhập (VD: /dn MatKhau hoặc chỉ mật khẩu).")
                .defaultValue("")
                .build());

        this.chatTrigger = this.sgGeneral.add(new BoolSetting.Builder()
                .name("chat-trigger")
                .description("Tự động gửi đăng nhập ngay khi thấy tin nhắn yêu cầu đăng nhập.")
                .defaultValue(true)
                .build());

        this.autoJoinLobby = this.sgLobby.add(new BoolSetting.Builder()
                .name("auto-join-smp")
                .description("Tự động dùng đồng hồ (slot 4) và click KingSMP sau khi login.")
                .defaultValue(true)
                .build());

        this.lobbyDelayTicks = this.sgLobby.add(new IntSetting.Builder()
                .name("lobby-delay-ticks")
                .description("Delay (ticks) sau khi login xong mới mở menu lobby.")
                .defaultValue(25)
                .min(10)
                .sliderMax(200)
                .build());

        this.targetServerItemName = this.sgLobby.add(new StringSetting.Builder()
                .name("server-item-name")
                .description("Tên server cần click trong GUI.")
                .defaultValue("kingsmp")
                .build());

        this.fallbackSlot = this.sgLobby.add(new IntSetting.Builder()
                .name("fallback-slot")
                .description("Slot dự phòng nếu không tìm thấy tên (VD: 21, 24).")
                .defaultValue(21)
                .min(0)
                .max(53)
                .build());

        this.serverCommands = new LinkedHashMap<>();
        this.resetState();
    }

    @Override
    public void onActivate() {
        this.resetState();
    }

    @Override
    public void onDeactivate() {
        this.resetState();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        this.resetState();
        this.state = State.WAITING_SPAWN;
    }

    @EventHandler
    private void onChat(ReceiveMessageEvent event) {
        if (!this.isActive()) return;
        String raw = this.normFull(event.getMessage().getString());

        // Chat trigger to initiate or hasten login
        if (this.chatTrigger.get() && (this.state == State.IDLE || this.state == State.WAITING_SPAWN || this.state == State.COUNTING_DOWN)) {
            if (raw.contains("/dn") || raw.contains("/login") || raw.contains("/register") || raw.contains("dang nhap") || raw.contains("dang ky")) {
                String cmd = this.resolveCommand(Utils.getWorldName());
                if (cmd != null && !cmd.isBlank()) {
                    this.cachedCommand = cmd;
                    this.timer = Math.min(this.timer, 5);
                    this.state = State.COUNTING_DOWN;
                }
            }
        }

        // Detect successful login from server messages
        if (this.state == State.LOGIN_SENT || this.state == State.COUNTING_DOWN || this.state == State.WAITING_SPAWN) {
            if (raw.contains("thanh cong") || raw.contains("welcome") || raw.contains("chao mung") || raw.contains("ban da dang nhap") || raw.contains("dang nhap thanh cong")) {
                if (this.autoJoinLobby.get()) {
                    this.timer = this.lobbyDelayTicks.get();
                    this.state = State.LOBBY_DELAY;
                } else {
                    this.state = State.DONE;
                }
            }
        }

        // If player already entered KingSMP world, stop any lobby retry loop
        if (raw.contains("kingsmp") || raw.contains("don hang chua lay") || raw.contains("donate key")) {
            this.state = State.DONE;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!this.isActive()) return;

        switch (this.state) {
            case IDLE -> {
                if (this.mc.world != null && this.mc.player != null) {
                    this.state = State.WAITING_SPAWN;
                }
            }
            case WAITING_SPAWN -> {
                if (this.mc.player == null || this.mc.world == null) return;
                String serverName = Utils.getWorldName();
                this.cachedCommand = this.resolveCommand(serverName);
                if (this.cachedCommand != null && !this.cachedCommand.isBlank()) {
                    this.timer = this.delayTicks.get();
                    this.state = State.COUNTING_DOWN;
                } else {
                    this.state = State.DONE;
                }
            }
            case COUNTING_DOWN -> {
                if (this.mc.player == null || this.mc.world == null) {
                    this.resetState();
                    return;
                }
                if (this.timer > 0) {
                    --this.timer;
                    return;
                }
                this.executeLogin();
            }
            case LOGIN_SENT -> {
                if (this.timer > 0) {
                    --this.timer;
                    return;
                }
                if (this.autoJoinLobby.get()) {
                    this.timer = this.lobbyDelayTicks.get();
                    this.state = State.LOBBY_DELAY;
                } else {
                    this.state = State.DONE;
                }
            }
            case LOBBY_DELAY -> {
                if (this.timer > 0) {
                    --this.timer;
                    return;
                }
                this.openLobbyMenu();
            }
            case LOBBY_WAIT_GUI -> {
                GenericContainerScreenHandler gui = this.getGui();
                String title = this.guiTitle();
                if (gui != null || title.contains("menu") || title.contains("king")) {
                    this.selectServer(gui);
                } else {
                    ++this.lobbyTimeout;
                    if (this.lobbyTimeout > 60) {
                        ++this.lobbyRetryCount;
                        if (this.lobbyRetryCount >= 3) {
                            // After 3 attempts assume we already joined or in SMP
                            this.state = State.DONE;
                            return;
                        }
                        this.info("Đang mở lại menu lobby...");
                        this.openLobbyMenu();
                    }
                }
            }
            case DONE -> {
                if (this.mc.world == null) {
                    this.resetState();
                }
            }
        }
    }

    private void executeLogin() {
        if (this.cachedCommand == null || this.cachedCommand.isBlank()) return;
        String raw = this.cachedCommand.trim();
        String commandNoSlash;

        if (raw.startsWith("/")) {
            commandNoSlash = raw.substring(1);
        } else if (raw.toLowerCase(Locale.ROOT).startsWith("dn ") || raw.toLowerCase(Locale.ROOT).startsWith("login ") || raw.toLowerCase(Locale.ROOT).startsWith("register ")) {
            commandNoSlash = raw;
        } else {
            commandNoSlash = "dn " + raw;
        }

        // Send chat message with slash (which server command interpreters and custom auth plugins accept uniformly)
        ChatUtils.sendPlayerMsg("/" + commandNoSlash);

        this.info("✅ Đã gửi lệnh auto-login: /%s", commandNoSlash);
        this.timer = 40;
        this.state = State.LOGIN_SENT;
    }

    private void openLobbyMenu() {
        if (this.mc.player == null || this.mc.interactionManager == null) return;
        this.lobbyTimeout = 0;

        // Method 1: Right-click Clock Item in hotbar (Slot 4 - Đồng Hồ)
        int clockSlot = -1;
        for (int i = 0; i < 9; ++i) {
            ItemStack stack = this.mc.player.getInventory().getStack(i);
            if (stack.isOf(Items.CLOCK) || this.normFull(stack.getName().getString()).contains("menu") || this.normFull(stack.getName().getString()).contains("dong ho")) {
                clockSlot = i;
                break;
            }
        }

        if (clockSlot != -1) {
            this.mc.player.getInventory().setSelectedSlot(clockSlot);
            this.mc.interactionManager.interactItem(this.mc.player, net.minecraft.util.Hand.MAIN_HAND);
            this.info("Đã dùng Đồng Hồ để mở Menu Lobby...");
        } else {
            // If no clock item in hotbar, we are likely already in SMP world
            this.info("Không có Đồng Hồ trong hotbar (đã vào SMP), hoàn tất.");
            this.state = State.DONE;
            return;
        }

        this.state = State.LOBBY_WAIT_GUI;
    }

    private void selectServer(GenericContainerScreenHandler gui) {
        if (gui == null && this.mc.player != null) {
            ScreenHandler h = this.mc.player.currentScreenHandler;
            if (h instanceof GenericContainerScreenHandler g) gui = g;
        }
        if (gui == null) return;

        String target = this.normFull(this.targetServerItemName.get());
        int targetSlot = -1;

        for (int i = 0; i < gui.slots.size(); ++i) {
            ItemStack s = gui.slots.get(i).getStack();
            if (s.isEmpty()) continue;
            String name = this.normFull(s.getName().getString());
            String lore = this.normFull(String.join(" ", this.getLore(s)));
            if (name.contains(target) || lore.contains(target) || name.contains("kingsmp") || lore.contains("kingsmp")) {
                targetSlot = i;
                break;
            }
        }

        if (targetSlot == -1) {
            targetSlot = this.fallbackSlot.get();
        }

        this.info("✅ Đã bấm vào slot %d để kết nối KingSMP!", targetSlot);
        this.click(gui, targetSlot, 0, SlotActionType.PICKUP);
        this.state = State.DONE;
    }

    private void click(GenericContainerScreenHandler h, int slot, int button, SlotActionType type) {
        if (slot < 0 || slot >= h.slots.size()) return;
        if (this.mc.player == null || this.mc.interactionManager == null) return;
        try {
            this.mc.interactionManager.clickSlot(h.syncId, slot, button, type, this.mc.player);
        } catch (Exception ignored) {}
    }

    private GenericContainerScreenHandler getGui() {
        if (this.mc.player == null) return null;
        ScreenHandler h = this.mc.player.currentScreenHandler;
        return h instanceof GenericContainerScreenHandler g ? g : null;
    }

    private String guiTitle() {
        if (this.mc.currentScreen == null) return "";
        Text t = this.mc.currentScreen.getTitle();
        return t == null ? "" : this.normFull(t.getString());
    }

    private List<String> getLore(ItemStack s) {
        LoreComponent lore = s.get(DataComponentTypes.LORE);
        if (lore == null) return List.of();
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (Text t : lore.lines()) {
            out.add(t.getString());
        }
        return out;
    }

    private String normFull(String s) {
        if (s == null) return "";
        String r = s.toLowerCase(Locale.ROOT)
                .replaceAll("§[0-9a-fk-or]", "");
        String nfd = Normalizer.normalize(r, Normalizer.Form.NFD);
        return nfd.replaceAll("\\p{InCombiningDiacriticalMarks}", "").replace("đ", "d").replace("Đ", "d");
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = super.toTag();
        if (tag == null) {
            tag = new NbtCompound();
        }
        NbtCompound serversTag = new NbtCompound();
        this.serverCommands.forEach(serversTag::putString);
        tag.put("serverCommands", (NbtElement) serversTag);
        return tag;
    }

    @Override
    public Module fromTag(NbtCompound tag) {
        super.fromTag(tag);
        if (tag != null && tag.contains("serverCommands")) {
            tag.getCompound("serverCommands").ifPresent(serversTag -> {
                this.serverCommands.clear();
                for (String key : serversTag.getKeys()) {
                    serversTag.getString(key).ifPresent(value -> this.serverCommands.put(key, value));
                }
            });
        }
        return this;
    }

    private void resetState() {
        this.state = State.IDLE;
        this.timer = 0;
        this.cachedCommand = null;
        this.lobbyTimeout = 0;
        this.lobbyRetryCount = 0;
    }

    private String resolveCommand(String serverName) {
        String uni = this.universalCommand.get();
        if (uni != null && !uni.isBlank()) {
            return uni;
        }
        if (serverName != null) {
            for (Map.Entry<String, String> entry : this.serverCommands.entrySet()) {
                String key = entry.getKey();
                if (key.equals("*") || !serverName.toLowerCase().contains(key.toLowerCase()))
                    continue;
                return entry.getValue();
            }
        }
        return this.serverCommands.get("*");
    }

    public void setCommand(String server, String command) {
        this.serverCommands.put(server, command);
    }

    public Map<String, String> getServerCommands() {
        return Collections.unmodifiableMap(this.serverCommands);
    }

    private enum State {
        IDLE,
        WAITING_SPAWN,
        COUNTING_DOWN,
        LOGIN_SENT,
        LOBBY_DELAY,
        LOBBY_WAIT_GUI,
        DONE
    }
}
