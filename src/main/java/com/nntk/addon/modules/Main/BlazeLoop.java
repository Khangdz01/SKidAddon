package com.nntk.addon.modules.Main;

import com.nntk.addon.AISKID;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.DataComponentTypes;

public class BlazeLoop extends Module {
    private static final String TITLE_SHOP = "shop";
    private static final String TITLE_SHOP_NETHER = "shop -> nether";
    private static final String TITLE_BUY = "mua que lua";
    private static final String TITLE_ORDER_LIST = "don hang";
    private static final String TITLE_DROP = "bo vat pham vao de giao";
    private static final String TITLE_CONFIRM = "xac nhan giao hang";
    private static final int ORDER_ITEM_SLOTS = 45;
    private static final String CHAT_OVERFLOW = "so luong giao vuot qua so luong con lai";
    private static final String CHAT_ERROR = "co loi xay ra khi giao hang";
    private static final int RELOAD_SLOT = 49;
    private static final int RELOAD_CLICKS = 3;
    private static final int CMD_ROUNDTRIP = 8;
    private static final int GUI_MIN_WAIT = 1;

    private State state = State.BUY_OPEN_SHOP;
    private int delay = 0;
    private int timeout = 0;
    private int needQty = 0;
    private int droppedQty = 0;
    private int splitTarget = 0;
    private int splitDone = 0;
    private int splitPickupSlot = -1;
    private int reloadCount = 0;

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgDelays = this.settings.createGroup("Delays");
    private final SettingGroup sgSlots = this.settings.createGroup("Fallback Slots");
    private final SettingGroup sgBlacklist = this.settings.createGroup("Blacklist");

    private final Setting<String> minPrice = this.sgGeneral.add(new StringSetting.Builder().name("min-price").description("Giá tối thiểu/rod. VD: 150, 1.5K").defaultValue("150").build());
    private final Setting<Boolean> notifications = this.sgGeneral.add(new BoolSetting.Builder().name("notifications").defaultValue(true).build());
    private final Setting<Boolean> debug = this.sgGeneral.add(new BoolSetting.Builder().name("debug").defaultValue(false).build());
    private final Setting<Integer> buyInterval = this.sgDelays.add(new IntSetting.Builder().name("buy-interval").defaultValue(0).min(0).max(10).build());
    private final Setting<Integer> splitInterval = this.sgDelays.add(new IntSetting.Builder().name("split-interval").defaultValue(1).min(0).max(5).build());
    private final Setting<Integer> orderTimeout = this.sgDelays.add(new IntSetting.Builder().name("order-timeout").defaultValue(60).min(20).max(200).build());
    private final Setting<Integer> dropTimeout = this.sgDelays.add(new IntSetting.Builder().name("drop-timeout").defaultValue(60).min(20).max(200).build());
    private final Setting<Integer> confirmTimeout = this.sgDelays.add(new IntSetting.Builder().name("confirm-timeout").defaultValue(80).min(20).max(200).build());
    private final Setting<Integer> fbSlotNether = this.sgSlots.add(new IntSetting.Builder().name("nether-slot").defaultValue(12).min(0).max(53).build());
    private final Setting<Integer> fbSlotBlaze = this.sgSlots.add(new IntSetting.Builder().name("blaze-slot").defaultValue(9).min(0).max(53).build());
    private final Setting<Integer> fbSlotQty64 = this.sgSlots.add(new IntSetting.Builder().name("qty64-slot").defaultValue(17).min(0).max(53).build());
    private final Setting<Integer> fbSlotBuyOk = this.sgSlots.add(new IntSetting.Builder().name("buy-ok-slot").defaultValue(23).min(0).max(53).build());
    private final Setting<Integer> fbSlotOrderOk = this.sgSlots.add(new IntSetting.Builder().name("order-ok-slot").defaultValue(16).min(0).max(53).build());
    private final Setting<List<String>> blacklist = this.sgBlacklist.add(new StringListSetting.Builder().name("blacklisted-players").defaultValue(List.of()).build());

    public BlazeLoop() {
        super(AISKID.CATEGORY, "auto-blaze-rod-loop", "Chu trình khép kín: Mua Blaze Rod -> Trả đơn -> Lặp. Fast polling.");
    }

    public void onActivate() {
        if (this.parsePrice(this.minPrice.get()) < 0.0) {
            ChatUtils.error("Giá tối thiểu không hợp lệ!");
            this.toggle();
            return;
        }
        this.reset();
        if (this.notifications.get()) {
            this.info("BlazeRodLoop bắt đầu.");
        }
    }

    public void onDeactivate() {
        this.reset();
    }

    private void reset() {
        this.state = State.BUY_OPEN_SHOP;
        this.delay = 0;
        this.timeout = 0;
        this.needQty = 0;
        this.droppedQty = 0;
        this.splitTarget = 0;
        this.splitDone = 0;
        this.splitPickupSlot = -1;
        this.reloadCount = 0;
    }

    @EventHandler
    private void onChat(ReceiveMessageEvent event) {
        if (this.mc.player == null || !this.isActive()) return;
        String msg = this.normFull(event.getMessage().getString());
        if (msg.contains(CHAT_OVERFLOW)) {
            if (this.notifications.get()) {
                this.info("⚠️ Vượt số lượng -> xác nhận giao.");
            }
            this.handleOverflow();
        } else if (msg.contains(CHAT_ERROR)) {
            if (this.notifications.get()) {
                this.info("⚠️ Lỗi giao hàng -> đóng GUI -> reload đơn.");
            }
            this.handleDeliveryError();
        }
    }

    private void handleOverflow() {
        GenericContainerScreenHandler gui = this.getGui();
        String title = this.guiTitle();
        if (gui != null && title.contains(TITLE_DROP)) {
            this.mc.player.closeHandledScreen();
            this.go(State.ORDER_WAIT_CONFIRM, 0);
        } else if (gui != null && title.contains(TITLE_CONFIRM)) {
            this.go(State.ORDER_DO_CONFIRM, 0);
        } else {
            this.go(State.ORDER_WAIT_CONFIRM, 0);
        }
    }

    private void handleDeliveryError() {
        this.needQty = 0;
        this.droppedQty = 0;
        this.reloadCount = 0;
        this.go(State.ORDER_ERROR_CLOSE, 2);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (this.mc.player == null || this.mc.world == null || this.mc.interactionManager == null) return;
        if (this.mc.player.hurtTime > 0) {
            this.delay = 10;
            return;
        }
        if (this.delay > 0) {
            --this.delay;
            return;
        }
        GenericContainerScreenHandler gui = this.getGui();
        String title = this.guiTitle();
        switch (this.state) {
            case BUY_OPEN_SHOP -> this.tickBuyOpenShop(gui);
            case BUY_WAIT_SHOP -> this.tickBuyWaitShop(title);
            case BUY_CLICK_NETHER -> this.tickBuyClickNether(gui);
            case BUY_WAIT_NETHER -> this.tickBuyWaitNether(title);
            case BUY_CLICK_BLAZE -> this.tickBuyClickBlaze(gui);
            case BUY_WAIT_BUY_GUI -> this.tickBuyWaitBuyGui(title);
            case BUY_CLICK_QTY64 -> this.tickBuyClickQty64(gui);
            case BUY_WAIT_QTY -> this.tickBuyWaitQty(gui, title);
            case BUY_CONFIRM_LOOP -> this.tickBuyConfirmLoop(gui, title);
            case ORDER_OPEN -> this.tickOrderOpen(gui);
            case ORDER_WAIT_GUI -> this.tickOrderWaitGui(title);
            case ORDER_SCAN -> this.tickOrderScan(gui, title);
            case ORDER_WAIT_DROP -> this.tickOrderWaitDrop(title);
            case DROP_SHIFT_ALL -> this.tickDropShiftAll(gui, title);
            case DROP_SPLIT_PICKUP -> this.tickDropSplitPickup(gui, title);
            case DROP_SPLIT_PLACING -> this.tickDropSplitPlacing(gui, title);
            case DROP_SPLIT_PUTBACK -> this.tickDropSplitPutback(gui);
            case ORDER_CLOSE_DROP -> this.tickOrderCloseDrop();
            case ORDER_WAIT_CONFIRM -> this.tickOrderWaitConfirm(title);
            case ORDER_DO_CONFIRM -> this.tickOrderDoConfirm(gui);
            case ORDER_WAIT_NEXT -> this.tickOrderWaitNext(title);
            case ORDER_ERROR_CLOSE -> this.tickOrderErrorClose(gui);
            case ORDER_RELOAD -> this.tickOrderReload(gui, title);
            case ORDER_RELOAD_WAIT -> this.tickOrderReloadWait(title);
            case CLOSE_THEN_BUY -> this.tickCloseThenBuy(gui);
        }
    }

    private void tickBuyOpenShop(GenericContainerScreenHandler gui) {
        if (this.getEmptySlots() == 0) {
            this.go(State.ORDER_OPEN, 0);
            return;
        }
        if (gui != null) {
            this.mc.player.closeHandledScreen();
            this.delay = 3;
            return;
        }
        ChatUtils.sendPlayerMsg("/shop");
        this.go(State.BUY_WAIT_SHOP, 8);
    }

    private void tickBuyWaitShop(String title) {
        if (title.contains(TITLE_SHOP) && !title.contains(TITLE_SHOP_NETHER)) {
            this.go(State.BUY_CLICK_NETHER, 0);
        } else {
            ++this.timeout;
            if (this.timeout > this.orderTimeout.get()) {
                this.info("⚠️ Timeout shop -> thử lại.");
                this.go(State.BUY_OPEN_SHOP, 20);
            }
        }
    }

    private void tickBuyClickNether(GenericContainerScreenHandler gui) {
        if (gui == null) {
            this.go(State.BUY_OPEN_SHOP, 3);
            return;
        }
        int t = this.findByName(gui, "nether");
        if (t == -1) t = this.fbSlotNether.get();
        this.click(gui, t, 0, SlotActionType.PICKUP);
        this.go(State.BUY_WAIT_NETHER, 1);
    }

    private void tickBuyWaitNether(String title) {
        if (title.contains(TITLE_SHOP_NETHER)) {
            this.go(State.BUY_CLICK_BLAZE, 0);
        } else {
            ++this.timeout;
            if (this.timeout > 40) this.go(State.BUY_OPEN_SHOP, 10);
        }
    }

    private void tickBuyClickBlaze(GenericContainerScreenHandler gui) {
        if (gui == null) {
            this.go(State.BUY_OPEN_SHOP, 3);
            return;
        }
        int t = this.findByName(gui, "que lua");
        if (t == -1) t = this.findByTranslationKey(gui, "blaze_rod");
        if (t == -1) t = this.fbSlotBlaze.get();
        this.click(gui, t, 0, SlotActionType.PICKUP);
        this.go(State.BUY_WAIT_BUY_GUI, 1);
    }

    private void tickBuyWaitBuyGui(String title) {
        if (title.contains(TITLE_BUY)) {
            this.go(State.BUY_CLICK_QTY64, 0);
        } else {
            ++this.timeout;
            if (this.timeout > 40) this.go(State.BUY_OPEN_SHOP, 10);
        }
    }

    private void tickBuyClickQty64(GenericContainerScreenHandler gui) {
        if (gui == null) {
            this.go(State.BUY_OPEN_SHOP, 3);
            return;
        }
        int t = this.findByName(gui, "dat thanh 64");
        if (t == -1) t = this.findByName(gui, "thanh 64");
        if (t == -1) t = this.fbSlotQty64.get();
        this.click(gui, t, 0, SlotActionType.PICKUP);
        this.go(State.BUY_WAIT_QTY, 1);
    }

    private void tickBuyWaitQty(GenericContainerScreenHandler gui, String title) {
        if (title.contains(TITLE_BUY) && gui != null) {
            int t = this.findByName(gui, "xac nhan");
            if (t == -1) t = this.fbSlotBuyOk.get();
            if (t < gui.slots.size() && !gui.slots.get(t).getStack().isEmpty()) {
                this.go(State.BUY_CONFIRM_LOOP, 0);
            } else {
                ++this.timeout;
                if (this.timeout > 20) this.go(State.BUY_CLICK_QTY64, 3);
            }
        } else {
            ++this.timeout;
            if (this.timeout > 20) this.go(State.BUY_OPEN_SHOP, 10);
        }
    }

    private void tickBuyConfirmLoop(GenericContainerScreenHandler gui, String title) {
        if (gui == null || !title.contains(TITLE_BUY)) {
            this.go(State.BUY_OPEN_SHOP, 3);
            return;
        }
        if (this.getEmptySlots() == 0) {
            if (this.notifications.get()) this.info("Túi đầy! Bắt đầu trả đơn.");
            this.mc.player.closeHandledScreen();
            this.go(State.ORDER_OPEN, 3);
            return;
        }
        int t = this.findByName(gui, "xac nhan");
        if (t == -1) t = this.fbSlotBuyOk.get();
        if (t < gui.slots.size() && !gui.slots.get(t).getStack().isEmpty()) {
            this.click(gui, t, 0, SlotActionType.PICKUP);
            this.delay = this.buyInterval.get();
        } else {
            this.delay = 1;
        }
    }

    private void tickOrderOpen(GenericContainerScreenHandler gui) {
        if (this.countRods() == 0) {
            this.go(State.CLOSE_THEN_BUY, 0);
            return;
        }
        if (gui != null) {
            this.mc.player.closeHandledScreen();
            this.delay = 3;
            return;
        }
        ChatUtils.sendPlayerMsg("/order blaze rod");
        this.go(State.ORDER_WAIT_GUI, 8);
    }

    private void tickOrderWaitGui(String title) {
        if (title.contains(TITLE_ORDER_LIST)) {
            this.go(State.ORDER_SCAN, 0);
        } else {
            ++this.timeout;
            if (this.timeout > this.orderTimeout.get()) {
                this.info("⚠️ Timeout order GUI -> thử lại.");
                this.go(State.ORDER_OPEN, 20);
            }
        }
    }

    private void tickOrderScan(GenericContainerScreenHandler gui, String title) {
        if (gui == null || !title.contains(TITLE_ORDER_LIST)) {
            this.go(State.ORDER_OPEN, 3);
            return;
        }
        double minPr = this.parsePrice(this.minPrice.get());
        int rods = this.countRods();
        int bestSlot = -1;
        double bestPr = -1.0;
        int bestNeed = 0;
        String bestWho = null;

        for (int i = 0; i < 45 && i < gui.slots.size(); ++i) {
            ItemStack s = gui.slots.get(i).getStack();
            if (s.isEmpty()) continue;
            String lore = this.normFull(String.join(" ", this.getLore(s)));
            if (!lore.contains("blaze rod")) continue;
            String poster = this.getPoster(s);
            if (this.isBlacklisted(poster)) continue;
            double price = this.parseOrderPrice(lore);
            if (price < minPr) continue;
            int total = this.parseQty(lore);
            int delivered = this.parseDelivered(lore);
            int need = total > 0 && delivered >= 0 ? total - delivered : total;
            if (need <= 0 || rods <= 0 || price <= bestPr) continue;
            bestPr = price;
            bestSlot = i;
            bestNeed = need;
            bestWho = poster;
        }

        if (bestSlot == -1) {
            if (this.notifications.get()) this.info("⚠️ Không có đơn hợp lệ -> mua thêm.");
            this.mc.player.closeHandledScreen();
            this.go(State.BUY_OPEN_SHOP, 5);
            return;
        }

        this.needQty = bestNeed;
        this.droppedQty = 0;
        if (this.notifications.get()) {
            this.info("✅ Đơn $%s/rod, cần %d, túi %d rod — %s", this.fmtPrice(bestPr), this.needQty, rods, bestWho != null ? bestWho : "?");
        }
        if (this.debug.get()) {
            this.info("§eDEBUG scan: slot=%d needQty=%d price=%.1f", bestSlot, this.needQty, bestPr);
        }
        this.click(gui, bestSlot, 0, SlotActionType.PICKUP);
        this.go(State.ORDER_WAIT_DROP, 1);
    }

    private void tickOrderWaitDrop(String title) {
        if (title.contains(TITLE_DROP)) {
            this.go(State.DROP_SHIFT_ALL, 0);
        } else if (title.contains(TITLE_ORDER_LIST)) {
            this.go(State.ORDER_SCAN, 0);
        } else if (title.contains(TITLE_CONFIRM)) {
            this.go(State.ORDER_DO_CONFIRM, 0);
        } else {
            ++this.timeout;
            if (this.timeout > this.dropTimeout.get()) {
                this.info("⚠️ Timeout drop GUI -> thử lại.");
                this.go(State.ORDER_OPEN, 20);
            }
        }
    }

    private void tickDropShiftAll(GenericContainerScreenHandler gui, String title) {
        if (gui == null || !title.contains(TITLE_DROP)) {
            this.go(State.ORDER_WAIT_CONFIRM, 0);
            return;
        }
        int stillNeed = this.needQty - this.droppedQty;
        if (stillNeed <= 0) {
            this.go(State.ORDER_CLOSE_DROP, 0);
            return;
        }
        if (stillNeed < 64) {
            this.go(State.DROP_SPLIT_PICKUP, 0);
            return;
        }
        int invStart = gui.getRows() * 9;
        for (int i = invStart; i < gui.slots.size(); ++i) {
            ItemStack s = gui.slots.get(i).getStack();
            if (s.isEmpty() || !s.isOf(Items.BLAZE_ROD)) continue;
            if (this.droppedQty + s.getCount() > this.needQty) {
                this.go(State.DROP_SPLIT_PICKUP, 0);
                return;
            }
            this.click(gui, i, 0, SlotActionType.QUICK_MOVE);
            this.droppedQty += s.getCount();
            this.delay = 1;
            return;
        }
        this.go(State.ORDER_CLOSE_DROP, 0);
    }

    private void tickDropSplitPickup(GenericContainerScreenHandler gui, String title) {
        if (gui == null || !title.contains(TITLE_DROP)) {
            this.go(State.ORDER_WAIT_CONFIRM, 0);
            return;
        }
        int stillNeed = this.needQty - this.droppedQty;
        if (stillNeed <= 0) {
            this.go(State.ORDER_CLOSE_DROP, 0);
            return;
        }
        this.splitTarget = Math.min(stillNeed, 64);
        this.splitDone = 0;
        this.splitPickupSlot = -1;
        int invStart = gui.getRows() * 9;
        for (int i = invStart; i < gui.slots.size(); ++i) {
            ItemStack s = gui.slots.get(i).getStack();
            if (s.isEmpty() || !s.isOf(Items.BLAZE_ROD)) continue;
            this.splitPickupSlot = i;
            break;
        }
        if (this.splitPickupSlot == -1) {
            this.go(State.ORDER_CLOSE_DROP, 0);
            return;
        }
        this.click(gui, this.splitPickupSlot, 0, SlotActionType.PICKUP);
        this.go(State.DROP_SPLIT_PLACING, this.splitInterval.get() + 1);
    }

    private void tickDropSplitPlacing(GenericContainerScreenHandler gui, String title) {
        if (gui == null || !title.contains(TITLE_DROP)) {
            this.dropCursorToInv(gui);
            this.go(State.ORDER_WAIT_CONFIRM, 0);
            return;
        }
        if (this.splitDone >= this.splitTarget) {
            this.go(State.DROP_SPLIT_PUTBACK, this.splitInterval.get() + 1);
            return;
        }
        int invStart = gui.getRows() * 9;
        int emptySlot = -1;
        for (int i = 0; i < invStart; ++i) {
            if (!gui.slots.get(i).getStack().isEmpty()) continue;
            emptySlot = i;
            break;
        }
        if (emptySlot == -1) {
            this.go(State.DROP_SPLIT_PUTBACK, 1);
            return;
        }
        this.click(gui, emptySlot, 1, SlotActionType.PICKUP);
        ++this.splitDone;
        ++this.droppedQty;
        this.delay = this.splitInterval.get();
    }

    private void tickDropSplitPutback(GenericContainerScreenHandler gui) {
        if (gui == null) {
            this.go(State.ORDER_WAIT_CONFIRM, 0);
            return;
        }
        int invStart = gui.getRows() * 9;
        for (int i = invStart; i < gui.slots.size(); ++i) {
            if (!gui.slots.get(i).getStack().isEmpty()) continue;
            this.click(gui, i, 0, SlotActionType.PICKUP);
            break;
        }
        int stillNeed = this.needQty - this.droppedQty;
        if (stillNeed <= 0 || this.countRods() == 0) {
            this.go(State.ORDER_CLOSE_DROP, 0);
        } else if (stillNeed < 64) {
            this.go(State.DROP_SPLIT_PICKUP, this.splitInterval.get() + 1);
        } else {
            this.go(State.DROP_SHIFT_ALL, this.splitInterval.get() + 1);
        }
    }

    private void tickOrderCloseDrop() {
        if (this.notifications.get()) {
            this.info("Đã bỏ %d rod, đóng GUI...", this.droppedQty);
        }
        if (this.mc.player != null) {
            this.mc.player.closeHandledScreen();
        }
        this.go(State.ORDER_WAIT_CONFIRM, 0);
    }

    private void tickOrderWaitConfirm(String title) {
        if (title.contains(TITLE_CONFIRM)) {
            this.go(State.ORDER_DO_CONFIRM, 0);
        } else if (title.contains(TITLE_DROP)) {
            this.go(State.DROP_SHIFT_ALL, 0);
        } else if (title.contains(TITLE_ORDER_LIST)) {
            this.go(State.ORDER_SCAN, 0);
        } else {
            ++this.timeout;
            if (this.timeout > this.confirmTimeout.get()) {
                this.info("⚠️ Timeout confirm GUI -> thử lại.");
                this.go(State.ORDER_OPEN, 20);
            }
        }
    }

    private void tickOrderDoConfirm(GenericContainerScreenHandler gui) {
        if (gui == null) {
            this.go(State.ORDER_WAIT_NEXT, 0);
            return;
        }
        int t = this.findByName(gui, "xac nhan");
        if (t == -1) t = this.fbSlotOrderOk.get();
        this.click(gui, t, 0, SlotActionType.PICKUP);
        this.go(State.ORDER_WAIT_NEXT, 1);
    }

    private void tickOrderWaitNext(String title) {
        if (title.contains(TITLE_DROP)) {
            this.needQty -= this.droppedQty;
            this.droppedQty = 0;
            this.go(State.DROP_SHIFT_ALL, 0);
        } else if (title.contains(TITLE_ORDER_LIST)) {
            this.needQty = 0;
            this.droppedQty = 0;
            this.go(State.ORDER_SCAN, 0);
        } else if (title.contains(TITLE_CONFIRM)) {
            this.go(State.ORDER_DO_CONFIRM, 0);
        } else {
            ++this.timeout;
            if (this.timeout > 40) {
                this.needQty = 0;
                this.droppedQty = 0;
                this.go(this.countRods() > 0 ? State.ORDER_OPEN : State.CLOSE_THEN_BUY, 3);
            }
        }
    }

    private void tickOrderErrorClose(GenericContainerScreenHandler gui) {
        if (gui != null) {
            this.mc.player.closeHandledScreen();
            this.delay = 4;
            return;
        }
        if (this.notifications.get()) {
            this.info("GUI đã đóng, bắt đầu reload đơn...");
        }
        this.go(State.ORDER_RELOAD, 3);
    }

    private void tickOrderReload(GenericContainerScreenHandler gui, String title) {
        if (gui == null || !title.contains(TITLE_ORDER_LIST)) {
            if (gui != null) {
                this.mc.player.closeHandledScreen();
                this.delay = 3;
                return;
            }
            ChatUtils.sendPlayerMsg("/order blaze rod");
            this.go(State.ORDER_RELOAD_WAIT, 8);
            return;
        }
        if (this.reloadCount >= 3) {
            if (this.notifications.get()) {
                this.info("Reload xong, scan lại...");
            }
            this.reloadCount = 0;
            this.go(State.ORDER_SCAN, 0);
            return;
        }
        this.click(gui, 49, 0, SlotActionType.PICKUP);
        ++this.reloadCount;
        if (this.debug.get()) {
            this.info("§eDEBUG reload %d/%d", this.reloadCount, 3);
        }
        this.delay = 2;
    }

    private void tickOrderReloadWait(String title) {
        if (title.contains(TITLE_ORDER_LIST)) {
            this.go(State.ORDER_RELOAD, 0);
        } else {
            ++this.timeout;
            if (this.timeout > this.orderTimeout.get()) {
                this.info("⚠️ Timeout reload -> thử lại.");
                this.go(State.ORDER_OPEN, 20);
            }
        }
    }

    private void tickCloseThenBuy(GenericContainerScreenHandler gui) {
        if (gui != null) {
            this.mc.player.closeHandledScreen();
            this.delay = 3;
            return;
        }
        if (this.notifications.get()) {
            this.info("Hết Blaze Rod -> quay lại mua.");
        }
        this.go(State.BUY_OPEN_SHOP, 0);
    }

    private void go(State next, int d) {
        this.state = next;
        this.timeout = 0;
        this.delay = d;
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

    private void click(GenericContainerScreenHandler h, int slot, int button, SlotActionType type) {
        if (slot < 0 || slot >= h.slots.size()) return;
        if (this.mc.player == null || this.mc.interactionManager == null) return;
        try {
            this.mc.interactionManager.clickSlot(h.syncId, slot, button, type, this.mc.player);
        } catch (Exception ignored) {}
    }

    private void dropCursorToInv(GenericContainerScreenHandler h) {
        if (h == null || this.mc.player == null) return;
        int invStart = h.getRows() * 9;
        for (int i = invStart; i < h.slots.size(); ++i) {
            if (!h.slots.get(i).getStack().isEmpty()) continue;
            this.click(h, i, 0, SlotActionType.PICKUP);
            return;
        }
    }

    private int getEmptySlots() {
        if (this.mc.player == null) return 0;
        int n = 0;
        for (int i = 0; i < 36; ++i) {
            if (!this.mc.player.getInventory().getStack(i).isEmpty()) continue;
            ++n;
        }
        return n;
    }

    private int countRods() {
        if (this.mc.player == null) return 0;
        int n = 0;
        for (int i = 0; i < 36; ++i) {
            ItemStack s = this.mc.player.getInventory().getStack(i);
            if (s.isEmpty() || !s.isOf(Items.BLAZE_ROD)) continue;
            n += s.getCount();
        }
        return n;
    }

    private int findByName(GenericContainerScreenHandler h, String keyword) {
        String kw = this.normFull(keyword);
        for (int i = 0; i < h.slots.size(); ++i) {
            ItemStack s = h.slots.get(i).getStack();
            if (s.isEmpty() || !this.normFull(s.getName().getString()).contains(kw)) continue;
            return i;
        }
        return -1;
    }

    private int findByTranslationKey(GenericContainerScreenHandler h, String key) {
        for (int i = 0; i < h.slots.size(); ++i) {
            ItemStack s = h.slots.get(i).getStack();
            if (s.isEmpty() || !s.getItem().getTranslationKey().contains(key)) continue;
            return i;
        }
        return -1;
    }

    private List<String> getLore(ItemStack s) {
        LoreComponent lore = s.get(DataComponentTypes.LORE);
        if (lore == null) return List.of();
        ArrayList<String> out = new ArrayList<>();
        for (Text t : lore.lines()) {
            out.add(t.getString());
        }
        return out;
    }

    private String getPoster(ItemStack s) {
        Pattern p = Pattern.compile("cua\\s+(\\S+)");
        Matcher m = p.matcher(this.normFull(s.getName().getString()));
        if (m.find()) return m.group(1);
        m = p.matcher(this.normFull(String.join(" ", this.getLore(s))));
        return m.find() ? m.group(1) : null;
    }

    private boolean isBlacklisted(String name) {
        if (name == null) return false;
        return this.blacklist.get().stream().anyMatch(b -> b.equalsIgnoreCase(name));
    }

    private double parseOrderPrice(String lore) {
        Matcher m = Pattern.compile("gia\\s*moi\\s*item\\s*[:\\-]?\\s*\\$?\\s*([\\d]+(?:[.,][\\d]+)?)\\s*([kmb])?").matcher(lore);
        if (!m.find() && !(m = Pattern.compile("\\$\\s*([\\d]+(?:[.,][\\d]+)?)\\s*([kmb])?").matcher(lore)).find()) {
            return -1.0;
        }
        return this.applyMultiplier(m.group(1).replace(",", ""), m.group(2));
    }

    private int parseQty(String lore) {
        Matcher m = Pattern.compile("so\\s*luong\\s*[:\\-]?\\s*([\\d,]+)").matcher(lore);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {}
        }
        if ((m = Pattern.compile("([\\d,]+)\\s*blaze\\s*rod").matcher(lore)).find()) {
            try {
                return Integer.parseInt(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private int parseDelivered(String lore) {
        Matcher m = Pattern.compile("da\\s*giao\\s*[:\\-]?\\s*([\\d,]+)\\s*/\\s*[\\d,]+").matcher(lore);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {}
        }
        m = Pattern.compile("([\\d,]+)\\s*/\\s*([\\d,]+)").matcher(lore);
        while (m.find()) {
            if (m.start() > 0 && lore.charAt(m.start() - 1) == '$') continue;
            try {
                return Integer.parseInt(m.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private double applyMultiplier(String num, String suf) {
        try {
            double base = Double.parseDouble(num);
            if (suf == null) return base;
            return base * (switch (suf.toLowerCase(Locale.ROOT)) {
                case "k" -> 1000.0;
                case "m" -> 1000000.0;
                case "b" -> 1.0E9;
                default -> 1.0;
            });
        } catch (NumberFormatException e) {
            return -1.0;
        }
    }

    private double parsePrice(String raw) {
        if (raw == null || raw.isBlank()) return -1.0;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        String suf = "";
        if (s.endsWith("b") || s.endsWith("m") || s.endsWith("k")) {
            suf = String.valueOf(s.charAt(s.length() - 1));
            s = s.substring(0, s.length() - 1);
        }
        return this.applyMultiplier(s.replace(",", ""), suf.isEmpty() ? null : suf);
    }

    private String fmtPrice(double p) {
        if (p >= 1.0E9) return String.format("%.1fB", p / 1.0E9);
        if (p >= 1000000.0) return String.format("%.1fM", p / 1000000.0);
        if (p >= 1000.0) return String.format("%.1fK", p / 1000.0);
        return String.format("%.0f", p);
    }

    private String normFull(String s) {
        if (s == null) return "";
        String r = s.toLowerCase(Locale.ROOT)
            .replaceAll("§[0-9a-fk-or]", "")
            .replace("ᴅ", "d").replace("ʜ", "h").replace("ɪ", "i").replace("ɴ", "n").replace("ᴏ", "o")
            .replace("ʀ", "r").replace("ᴛ", "t").replace("ᴠ", "v").replace("ʙ", "b").replace("ᴄ", "c")
            .replace("ᴇ", "e").replace("ɢ", "g").replace("ᴋ", "k").replace("ʟ", "l").replace("ᴍ", "m")
            .replace("ᴘ", "p").replace("ǫ", "q").replace("ᴜ", "u").replace("ʏ", "y").replace("ᴀ", "a")
            .replace("ᴡ", "w").replace("ᴢ", "z").replace("ᴊ", "j").replace("ꜰ", "f").replace("ꜱ", "s")
            .replace("ɘ", "e").replace("ᴙ", "r").replace("ғ", "f");
        String nfd = Normalizer.normalize(r, Normalizer.Form.NFD);
        return nfd.replaceAll("\\p{InCombiningDiacriticalMarks}", "").replace("đ", "d").replace("Đ", "d");
    }

    private enum State {
        BUY_OPEN_SHOP,
        BUY_WAIT_SHOP,
        BUY_CLICK_NETHER,
        BUY_WAIT_NETHER,
        BUY_CLICK_BLAZE,
        BUY_WAIT_BUY_GUI,
        BUY_CLICK_QTY64,
        BUY_WAIT_QTY,
        BUY_CONFIRM_LOOP,
        ORDER_OPEN,
        ORDER_WAIT_GUI,
        ORDER_SCAN,
        ORDER_WAIT_DROP,
        DROP_SHIFT_ALL,
        DROP_SPLIT_PICKUP,
        DROP_SPLIT_PLACING,
        DROP_SPLIT_PUTBACK,
        ORDER_CLOSE_DROP,
        ORDER_WAIT_CONFIRM,
        ORDER_DO_CONFIRM,
        ORDER_WAIT_NEXT,
        ORDER_ERROR_CLOSE,
        ORDER_RELOAD,
        ORDER_RELOAD_WAIT,
        CLOSE_THEN_BUY
    }
}
