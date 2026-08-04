package io.github.thomas16.minemate.client.debug;

import io.github.thomas16.minemate.debug.CompanionDebugRequestPayload;
import io.github.thomas16.minemate.debug.CompanionDebugSnapshot;
import io.github.thomas16.minemate.debug.CompanionDebugSnapshot.ActivityState;
import io.github.thomas16.minemate.debug.CompanionDebugSnapshot.EffectState;
import io.github.thomas16.minemate.debug.CompanionDebugSnapshot.GoalState;
import io.github.thomas16.minemate.debug.CompanionDebugSnapshot.OutcomeState;
import io.github.thomas16.minemate.debug.CompanionDebugSnapshot.ProgressState;
import io.github.thomas16.minemate.debug.CompanionDebugSnapshotPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Live, non-interactive view of server-owned companion state. */
public final class CompanionDebugScreen extends Screen {
    private static final int REFRESH_TICKS = 10;
    private static final int LIVE_TICKS = 20;
    private static final int DELAYED_TICKS = 40;
    private static final int PANEL_COLOR = 0xE610141A;
    private static final int CONTENT_COLOR = 0xD90B0E12;
    private static final int BORDER_COLOR = 0xFF59636F;
    private static final int TEXT_COLOR = 0xFFE5EAF0;
    private static final int MUTED_COLOR = 0xFF9BA7B4;
    private static final int HEADER_COLOR = 0xFF74C7EC;
    private static final int LIVE_COLOR = 0xFF62D26F;
    private static final int WARN_COLOR = 0xFFFFC857;
    private static final int ERROR_COLOR = 0xFFFF6B6B;
    private static final int SLOT_SIZE = 18;
    private static final int VITAL_CARD_HEIGHT = 50;
    private static final int VITAL_CARD_GAP = 6;
    private static final int HUD_ICON_SIZE = 9;
    private static final ResourceLocation HEART_CONTAINER_SPRITE = vanillaSprite("hud/heart/container");
    private static final ResourceLocation HEART_FULL_SPRITE = vanillaSprite("hud/heart/full");
    private static final ResourceLocation HEART_HALF_SPRITE = vanillaSprite("hud/heart/half");
    private static final ResourceLocation ABSORPTION_FULL_SPRITE = vanillaSprite("hud/heart/absorbing_full");
    private static final ResourceLocation ABSORPTION_HALF_SPRITE = vanillaSprite("hud/heart/absorbing_half");
    private static final ResourceLocation FOOD_EMPTY_SPRITE = vanillaSprite("hud/food_empty");
    private static final ResourceLocation FOOD_FULL_SPRITE = vanillaSprite("hud/food_full");
    private static final ResourceLocation FOOD_HALF_SPRITE = vanillaSprite("hud/food_half");
    private static final ResourceLocation ARMOR_EMPTY_SPRITE = vanillaSprite("hud/armor_empty");
    private static final ResourceLocation ARMOR_FULL_SPRITE = vanillaSprite("hud/armor_full");
    private static final ResourceLocation ARMOR_HALF_SPRITE = vanillaSprite("hud/armor_half");
    private static final ResourceLocation AIR_SPRITE = vanillaSprite("hud/air");
    private static final ResourceLocation AIR_BURSTING_SPRITE = vanillaSprite("hud/air_bursting");

    private final UUID companionId;
    private final int[] scrollOffsets = new int[Tab.values().length];
    private CompanionDebugSnapshot snapshot;
    private Tab tab = Tab.OVERVIEW;
    private boolean available;
    private String unavailableMessage = "";
    private int clientTicks;
    private int lastReceiveTick;
    private int lastContentHeight;
    private HoveredSlot hoveredSlot;

    public CompanionDebugScreen(CompanionDebugSnapshotPayload payload) {
        super(Component.literal("Companion Debug"));
        this.companionId = payload.companionId();
        receive(payload);
    }

    public boolean matches(UUID requestedCompanionId) {
        return companionId.equals(requestedCompanionId);
    }

    public void receive(CompanionDebugSnapshotPayload payload) {
        if (!matches(payload.companionId())) {
            return;
        }
        available = payload.available();
        unavailableMessage = payload.message();
        if (payload.snapshot() != null) {
            snapshot = payload.snapshot();
        }
        lastReceiveTick = clientTicks;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(width - 70, height - 25, 58, 20)
                .build());
    }

    @Override
    public void tick() {
        clientTicks++;
        if (clientTicks % REFRESH_TICKS == 0
                && ClientPlayNetworking.canSend(CompanionDebugRequestPayload.TYPE)) {
            ClientPlayNetworking.send(new CompanionDebugRequestPayload(companionId));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Screen.render() invokes this before rendering widgets. The vanilla implementation
        // applies the 1.21 menu blur, but this screen draws its own non-blurred dim layer.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Keep the live world visible and sharp behind the inspector.
        graphics.fill(0, 0, width, height, 0x50000000);
        hoveredSlot = null;

        int left = panelLeft();
        int right = panelRight();
        int top = 10;
        int bottom = height - 30;
        graphics.fill(left, top, right, bottom, PANEL_COLOR);
        graphics.renderOutline(left, top, right - left, bottom - top, BORDER_COLOR);
        graphics.drawString(font, title, left + 10, top + 8, TEXT_COLOR, true);
        drawConnectionStatus(graphics, right - 10, top + 8);
        drawTabs(graphics, mouseX, mouseY, left + 8, top + 25, right - left - 16);

        int contentLeft = left + 8;
        int contentRight = right - 8;
        int contentTop = top + 49;
        int contentBottom = bottom - 8;
        graphics.fill(contentLeft, contentTop, contentRight, contentBottom, CONTENT_COLOR);
        graphics.renderOutline(contentLeft, contentTop, contentRight - contentLeft,
                contentBottom - contentTop, 0xFF303943);

        if (snapshot == null) {
            graphics.drawCenteredString(font, "Waiting for companion snapshot...",
                    width / 2, contentTop + 18, MUTED_COLOR);
            lastContentHeight = font.lineHeight;
        } else if (tab == Tab.OVERVIEW) {
            renderOverview(graphics, contentLeft, contentTop, contentRight, contentBottom);
        } else if (tab == Tab.INVENTORY) {
            renderInventory(graphics, mouseX, mouseY, contentLeft, contentTop,
                    contentRight, contentBottom);
        } else {
            renderTextRows(graphics, executionRows(), contentLeft, contentTop,
                    contentRight, contentBottom);
        }

        if (!available && snapshot != null) {
            int messageWidth = Math.min(contentRight - contentLeft - 12,
                    font.width(unavailableMessage) + 12);
            int bannerLeft = contentLeft + (contentRight - contentLeft - messageWidth) / 2;
            graphics.fill(bannerLeft, contentTop + 5, bannerLeft + messageWidth,
                    contentTop + 20, 0xE66A1B1B);
            graphics.drawCenteredString(font, unavailableMessage,
                    (contentLeft + contentRight) / 2, contentTop + 8, 0xFFFFFFFF);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        if (hoveredSlot != null) {
            renderSlotTooltip(graphics, hoveredSlot, mouseX, mouseY);
        }
    }

    private void drawConnectionStatus(GuiGraphics graphics, int right, int y) {
        int age = clientTicks - lastReceiveTick;
        String label;
        int color;
        if (!available) {
            label = "UNAVAILABLE";
            color = ERROR_COLOR;
        } else if (age <= LIVE_TICKS) {
            label = "LIVE";
            color = LIVE_COLOR;
        } else if (age <= DELAYED_TICKS) {
            label = "DELAYED";
            color = WARN_COLOR;
        } else {
            label = "STALE";
            color = ERROR_COLOR;
        }
        graphics.fill(right - font.width(label) - 13, y - 1,
                right - font.width(label) - 8, y + 5, color);
        graphics.drawString(font, label, right - font.width(label), y, color, true);
    }

    private void drawTabs(GuiGraphics graphics, int mouseX, int mouseY,
            int left, int top, int availableWidth) {
        int tabWidth = availableWidth / Tab.values().length;
        for (Tab candidate : Tab.values()) {
            int x = left + candidate.ordinal() * tabWidth;
            int right = candidate == Tab.EXECUTION ? left + availableWidth : x + tabWidth - 2;
            boolean hovered = mouseX >= x && mouseX < right && mouseY >= top && mouseY < top + 19;
            int color = candidate == tab ? 0xFF38556A : hovered ? 0xFF293641 : 0xFF1A222B;
            graphics.fill(x, top, right, top + 19, color);
            graphics.renderOutline(x, top, right - x, 19,
                    candidate == tab ? HEADER_COLOR : 0xFF414B56);
            graphics.drawCenteredString(font, candidate.label, (x + right) / 2, top + 6,
                    candidate == tab ? 0xFFFFFFFF : MUTED_COLOR);
        }
    }

    private List<TextRow> overviewRows() {
        CompanionDebugSnapshot value = snapshot;
        List<TextRow> rows = new ArrayList<>();
        rows.add(TextRow.header("Identity"));
        rows.add(TextRow.value("Companion", value.companionId().toString()));
        rows.add(TextRow.value("Entity ID", Integer.toString(value.entityId())));
        rows.add(TextRow.value("Owner", value.ownerName() + " (" + value.ownerId() + ")"));
        rows.add(TextRow.value("Dimension", value.dimension()));

        rows.add(TextRow.header("Transform"));
        rows.add(TextRow.value("Position", format("%.3f, %.3f, %.3f", value.x(), value.y(), value.z())));
        rows.add(TextRow.value("Block", "%d, %d, %d".formatted(
                Mth.floor(value.x()), Mth.floor(value.y()), Mth.floor(value.z()))));
        rows.add(TextRow.value("Rotation", format("yaw %.2f, pitch %.2f", value.yaw(), value.pitch())));
        rows.add(TextRow.value("Velocity", format("%.4f, %.4f, %.4f",
                value.velocityX(), value.velocityY(), value.velocityZ())));
        rows.add(TextRow.value("Pose", value.pose()));

        rows.add(TextRow.header("Movement and flags"));
        rows.add(TextRow.plain(flags(value)));
        rows.add(TextRow.value("Damage state", "fire " + value.fireTicks() + " ticks, frozen "
                + value.frozenTicks() + "/" + value.freezeThreshold() + ", fall "
                + format("%.2f", value.fallDistance())));

        rows.add(TextRow.header("Active effects"));
        if (value.effects().isEmpty()) {
            rows.add(TextRow.plain("None"));
        } else {
            for (EffectState effect : value.effects()) {
                rows.add(TextRow.plain("%s level %d, %d ticks%s%s%s".formatted(
                        effect.id(), effect.amplifier() + 1, effect.duration(),
                        effect.ambient() ? ", ambient" : "",
                        effect.visible() ? ", particles" : "",
                        effect.showIcon() ? ", icon" : "")));
            }
        }
        return rows;
    }

    private void renderOverview(GuiGraphics graphics, int left, int top, int right, int bottom) {
        int innerLeft = left + 8;
        int innerWidth = Math.max(40, right - left - 22);
        int dashboardHeight = vitalsDashboardHeight(innerWidth);
        List<WrappedLine> lines = wrap(overviewRows(), innerWidth);
        int textHeight = lines.isEmpty() ? 0
                : lines.get(lines.size() - 1).y() + font.lineHeight;
        lastContentHeight = dashboardHeight + textHeight + 18;
        clampScroll(bottom - top);

        int baseY = top + 7 - scrollOffsets[tab.ordinal()];
        graphics.enableScissor(left + 1, top + 1, right - 1, bottom - 1);
        renderVitalsDashboard(graphics, innerLeft, baseY, innerWidth);
        int textY = baseY + dashboardHeight + 4;
        for (WrappedLine line : lines) {
            int y = textY + line.y();
            if (y + font.lineHeight >= top && y <= bottom) {
                graphics.drawString(font, line.text(), innerLeft + line.indent(), y,
                        line.color(), false);
            }
        }
        graphics.disableScissor();
        drawScrollBar(graphics, right, top, bottom);
    }

    private void renderVitalsDashboard(GuiGraphics graphics, int x, int y, int width) {
        graphics.drawString(font, "Vitals", x, y, HEADER_COLOR, false);
        int cardsTop = y + 13;
        boolean twoColumns = width >= 400;
        int columns = twoColumns ? 2 : 1;
        int cardWidth = (width - (columns - 1) * VITAL_CARD_GAP) / columns;

        for (VitalCard card : VitalCard.values()) {
            int column = card.ordinal() % columns;
            int row = card.ordinal() / columns;
            int cardX = x + column * (cardWidth + VITAL_CARD_GAP);
            int cardY = cardsTop + row * (VITAL_CARD_HEIGHT + VITAL_CARD_GAP);
            renderVitalCard(graphics, card, cardX, cardY, cardWidth);
        }

        int cardRows = twoColumns ? 2 : 4;
        int conditionsY = cardsTop + cardRows * VITAL_CARD_HEIGHT
                + (cardRows - 1) * VITAL_CARD_GAP + 7;
        renderConditions(graphics, x, conditionsY, width);
    }

    private void renderVitalCard(GuiGraphics graphics, VitalCard card,
            int x, int y, int width) {
        graphics.fill(x, y, x + width, y + VITAL_CARD_HEIGHT, 0xD9161C23);
        graphics.renderOutline(x, y, width, VITAL_CARD_HEIGHT, 0xFF3C4854);
        graphics.fill(x + 1, y + 1, x + 4, y + VITAL_CARD_HEIGHT - 1, card.accentColor);
        graphics.drawString(font, card.label, x + 8, y + 6, card.accentColor, false);

        String exact = switch (card) {
            case HEALTH -> format("%.2f / %.2f", snapshot.health(), snapshot.maxHealth());
            case HUNGER -> snapshot.food() + " / 20";
            case ARMOR -> snapshot.armor() + " / 20";
            case AIR -> snapshot.air() + " / " + snapshot.maxAir();
        };
        graphics.drawString(font, exact, x + width - font.width(exact) - 7,
                y + 6, TEXT_COLOR, false);

        int iconX = x + 8;
        int iconY = y + 20;
        switch (card) {
            case HEALTH -> {
                int heartContainers = Math.max(1, Math.min(10, Mth.ceil(snapshot.maxHealth() / 2.0F)));
                drawIconMeter(graphics, iconX, iconY, heartContainers,
                        Mth.ceil(snapshot.health()), HEART_CONTAINER_SPRITE,
                        HEART_FULL_SPRITE, HEART_HALF_SPRITE);
                drawAbsorption(graphics, x + 8, y + 36, width - 16);
            }
            case HUNGER -> {
                drawIconMeter(graphics, iconX, iconY, 10, snapshot.food(),
                        FOOD_EMPTY_SPRITE, FOOD_FULL_SPRITE, FOOD_HALF_SPRITE);
                drawCardDetail(graphics, x + 8, y + 36, width - 16,
                        format("Sat %.2f   Exh %.2f / 4", snapshot.saturation(), snapshot.exhaustion()),
                        0xFFE5B95C, Mth.clamp(snapshot.saturation() / 20.0F, 0.0F, 1.0F));
            }
            case ARMOR -> {
                drawIconMeter(graphics, iconX, iconY, 10, snapshot.armor(),
                        ARMOR_EMPTY_SPRITE, ARMOR_FULL_SPRITE, ARMOR_HALF_SPRITE);
                drawCardDetail(graphics, x + 8, y + 36, width - 16,
                        snapshot.armor() == 0 ? "No armor equipped" : "Defense points",
                        0xFFB8C4D2, Mth.clamp(snapshot.armor() / 20.0F, 0.0F, 1.0F));
            }
            case AIR -> {
                drawAirMeter(graphics, iconX, iconY);
                float ratio = snapshot.maxAir() <= 0 ? 0.0F
                        : Mth.clamp((float) snapshot.air() / snapshot.maxAir(), 0.0F, 1.0F);
                drawCardDetail(graphics, x + 8, y + 36, width - 16,
                        format("Breath %.0f%%", ratio * 100.0F), 0xFF63D6E6, ratio);
            }
        }
    }

    private void drawIconMeter(GuiGraphics graphics, int x, int y, int iconCount,
            int points, ResourceLocation empty, ResourceLocation full, ResourceLocation half) {
        for (int index = 0; index < iconCount; index++) {
            int iconX = x + index * HUD_ICON_SIZE;
            graphics.blitSprite(empty, iconX, y, HUD_ICON_SIZE, HUD_ICON_SIZE);
            int pointsInIcon = points - index * 2;
            if (pointsInIcon >= 2) {
                graphics.blitSprite(full, iconX, y, HUD_ICON_SIZE, HUD_ICON_SIZE);
            } else if (pointsInIcon == 1) {
                graphics.blitSprite(half, iconX, y, HUD_ICON_SIZE, HUD_ICON_SIZE);
            }
        }
    }

    private void drawAbsorption(GuiGraphics graphics, int x, int y, int width) {
        int absorptionPoints = Mth.ceil(snapshot.absorption());
        graphics.drawString(font, "Absorption", x, y, MUTED_COLOR, false);
        int iconsX = x + Math.min(62, Math.max(48, width - 92));
        int shownIcons = Math.min(6, Mth.ceil(absorptionPoints / 2.0F));
        for (int index = 0; index < shownIcons; index++) {
            int pointsInIcon = absorptionPoints - index * 2;
            graphics.blitSprite(pointsInIcon >= 2 ? ABSORPTION_FULL_SPRITE : ABSORPTION_HALF_SPRITE,
                    iconsX + index * HUD_ICON_SIZE, y - 1, HUD_ICON_SIZE, HUD_ICON_SIZE);
        }
        String value = "+" + format("%.2f", snapshot.absorption());
        graphics.drawString(font, value, x + width - font.width(value), y, 0xFFFFD866, false);
    }

    private void drawAirMeter(GuiGraphics graphics, int x, int y) {
        float scaled = snapshot.maxAir() <= 0 ? 0.0F
                : Mth.clamp(snapshot.air() * 10.0F / snapshot.maxAir(), 0.0F, 10.0F);
        int full = Mth.floor(scaled);
        boolean partial = scaled > full && full < 10;
        for (int index = 0; index < 10; index++) {
            int iconX = x + index * HUD_ICON_SIZE;
            graphics.fill(iconX + 1, y + 1, iconX + 8, y + 8, 0x382D6470);
            if (index < full) {
                graphics.blitSprite(AIR_SPRITE, iconX, y, HUD_ICON_SIZE, HUD_ICON_SIZE);
            } else if (index == full && partial) {
                graphics.blitSprite(AIR_BURSTING_SPRITE, iconX, y, HUD_ICON_SIZE, HUD_ICON_SIZE);
            }
        }
    }

    private void drawCardDetail(GuiGraphics graphics, int x, int y, int width,
            String label, int color, float progress) {
        int barWidth = Math.min(58, Math.max(28, width / 3));
        int barX = x + width - barWidth;
        graphics.drawString(font, label, x, y, MUTED_COLOR, false);
        graphics.fill(barX, y + 8, barX + barWidth, y + 10, 0xFF26303A);
        graphics.fill(barX, y + 8, barX + Mth.floor(barWidth * progress), y + 10, color);
    }

    private void renderConditions(GuiGraphics graphics, int x, int y, int width) {
        int gap = 4;
        int chipWidth = (width - gap * 2) / 3;
        String fireLabel = snapshot.fireTicks() > 0
                ? "FIRE " + snapshot.fireTicks() + "t" : "FIRE CLEAR";
        drawConditionChip(graphics, x, y, chipWidth, fireLabel,
                snapshot.fireTicks() > 0 ? ERROR_COLOR : LIVE_COLOR);
        drawConditionChip(graphics, x + chipWidth + gap, y, chipWidth,
                "FROST " + snapshot.frozenTicks() + "/" + snapshot.freezeThreshold(),
                snapshot.frozenTicks() > 0 ? 0xFF66D9EF : LIVE_COLOR);
        drawConditionChip(graphics, x + (chipWidth + gap) * 2, y,
                width - (chipWidth + gap) * 2,
                "FALL " + format("%.2f", snapshot.fallDistance()),
                snapshot.fallDistance() > 3.0F ? WARN_COLOR : LIVE_COLOR);
    }

    private void drawConditionChip(GuiGraphics graphics, int x, int y, int width,
            String label, int color) {
        graphics.fill(x, y, x + width, y + 17, 0xD912171D);
        graphics.renderOutline(x, y, width, 17, 0xFF35404B);
        graphics.fill(x + 5, y + 6, x + 9, y + 10, color);
        String visible = font.width(label) <= width - 17
                ? label : font.plainSubstrByWidth(label, Math.max(1, width - 19));
        graphics.drawString(font, visible, x + 13, y + 5, color, false);
    }

    private int vitalsDashboardHeight(int width) {
        int rows = width >= 400 ? 2 : 4;
        return 13 + rows * VITAL_CARD_HEIGHT + (rows - 1) * VITAL_CARD_GAP + 24;
    }

    private List<TextRow> executionRows() {
        CompanionDebugSnapshot value = snapshot;
        List<TextRow> rows = new ArrayList<>();
        rows.add(TextRow.header("PlayerEngine"));
        rows.add(TextRow.value("Status", emptyAs(value.engine().status(), "none")));
        rows.add(TextRow.value("Current chain", emptyAs(value.engine().currentChain(), "none")));
        rows.add(TextRow.value("Flags", "pathing=" + value.engine().pathing()
                + ", paused=" + value.engine().paused() + ", stopping=" + value.engine().stopping()));

        rows.add(TextRow.header("Current goal"));
        GoalState goal = value.goal();
        if (goal == null) {
            rows.add(TextRow.plain("Idle"));
        } else {
            rows.add(TextRow.value("ID / kind", goal.id() + " / " + goal.kind()));
            rows.add(TextRow.value("Description", goal.description()));
            rows.add(TextRow.value("Profile", goal.scope() + ", acquires prerequisites="
                    + goal.mayAcquirePrerequisites()));
            rows.add(TextRow.value("Side effects", emptyAs(goal.sideEffects(), "none")));
            rows.add(TextRow.value("Budgets", "timeout=" + ticks(goal.timeoutTicks())
                    + ", stall=" + ticks(goal.stallTicks())));
        }

        ProgressState progress = value.progress();
        rows.add(TextRow.header("Progress and activity"));
        if (progress == null) {
            rows.add(TextRow.plain("No sampled progress"));
        } else {
            rows.add(TextRow.value("Progress", progress.have() + " / " + progress.want()
                    + ", advance=" + format("%.4f", progress.advance())));
            rows.add(TextRow.value("Text", progress.text()));
        }
        ActivityState activity = value.activity();
        if (activity == null) {
            rows.add(TextRow.plain("No active PlayerEngine task"));
        } else {
            rows.add(TextRow.value("Elapsed", activity.elapsedTicks() + " / "
                    + (activity.timeoutTicks() == 0 ? "unbounded" : activity.timeoutTicks()) + " ticks"));
            rows.add(TextRow.value("Phase", emptyAs(activity.phase(), "none")));
            rows.add(TextRow.value("Preempting chain", emptyAs(activity.preemptingChain(), "none")));
            rows.add(TextRow.label("Task tree"));
            rows.add(TextRow.block(emptyAs(activity.taskTree(), "none")));
        }

        rows.add(TextRow.header("Reflex"));
        rows.add(TextRow.value("Kind / chain", value.reflex().kind() + " / "
                + emptyAs(value.reflex().chain(), "none")));
        rows.add(TextRow.value("Detail", emptyAs(value.reflex().detail(), "none")));
        rows.add(TextRow.value("Started at", Long.toString(value.reflex().startedAtTick())));
        rows.add(TextRow.value("Owner distance", value.reflex().ownerDistance() < 0
                ? "unavailable" : format("%.2f blocks", value.reflex().ownerDistance())));
        rows.add(TextRow.value("Defense suppressed", Boolean.toString(value.reflex().defenseSuppressed())));

        rows.add(TextRow.header("Last outcome"));
        OutcomeState outcome = value.lastOutcome();
        if (outcome == null) {
            rows.add(TextRow.plain("None"));
        } else {
            rows.add(TextRow.value("Goal / kind", outcome.goalId() + " / " + outcome.kind()));
            rows.add(TextRow.value("Status / reason", outcome.status() + " / " + outcome.reason()));
            rows.add(TextRow.value("Detail", outcome.detail()));
            rows.add(TextRow.value("Counts", outcome.have() + " / " + outcome.want()
                    + ", moved=" + outcome.moved()));
            rows.add(TextRow.value("Elapsed", outcome.elapsedMs() + " ms"));
            rows.add(TextRow.value("Engine state", emptyAs(outcome.engineState(), "none")));
            rows.add(TextRow.value("Engine log", emptyAs(outcome.engineLog(), "none")));
        }
        return rows;
    }

    private void renderTextRows(GuiGraphics graphics, List<TextRow> rows,
            int left, int top, int right, int bottom) {
        int wrapWidth = Math.max(40, right - left - 22);
        List<WrappedLine> lines = wrap(rows, wrapWidth);
        int totalHeight = lines.isEmpty() ? 0
                : lines.get(lines.size() - 1).y() + font.lineHeight;
        lastContentHeight = totalHeight + 12;
        clampScroll(bottom - top);

        graphics.enableScissor(left + 1, top + 1, right - 1, bottom - 1);
        int baseY = top + 7 - scrollOffsets[tab.ordinal()];
        for (WrappedLine line : lines) {
            int y = baseY + line.y();
            if (y + font.lineHeight >= top && y <= bottom) {
                graphics.drawString(font, line.text(), left + 8 + line.indent(), y,
                        line.color(), false);
            }
        }
        graphics.disableScissor();
        drawScrollBar(graphics, right, top, bottom);
    }

    private List<WrappedLine> wrap(List<TextRow> rows, int width) {
        List<WrappedLine> result = new ArrayList<>();
        int y = 0;
        for (TextRow row : rows) {
            y += row.gapBefore();
            List<FormattedCharSequence> wrapped = font.split(Component.literal(row.text()),
                    Math.max(20, width - row.indent()));
            if (wrapped.isEmpty()) {
                y += font.lineHeight;
                continue;
            }
            for (FormattedCharSequence text : wrapped) {
                result.add(new WrappedLine(text, y, row.color(), row.indent()));
                y += font.lineHeight + 1;
            }
        }
        return result;
    }

    private void renderInventory(GuiGraphics graphics, int mouseX, int mouseY,
            int left, int top, int right, int bottom) {
        int contentWidth = right - left - 16;
        boolean sideEquipment = contentWidth >= 226;
        int gridWidth = SLOT_SIZE * 9;
        int gridX = sideEquipment
                ? left + Math.max(8, (contentWidth - gridWidth - 58) / 2)
                : left + Math.max(8, (contentWidth - gridWidth) / 2);
        int equipmentX = sideEquipment ? gridX + gridWidth + 20 : gridX;
        int equipmentTop = sideEquipment ? 13 : 116;
        lastContentHeight = sideEquipment ? 140 : 245;
        clampScroll(bottom - top);
        int originY = top + 8 - scrollOffsets[tab.ordinal()];

        graphics.enableScissor(left + 1, top + 1, right - 1, bottom - 1);
        graphics.drawString(font, "Main inventory [9-35]", gridX, originY, HEADER_COLOR, false);
        int storageTop = originY + 13;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                int slot = 9 + row * 9 + column;
                renderSlot(graphics, snapshot.mainInventory().get(slot), "main[" + slot + "]",
                        gridX + column * SLOT_SIZE, storageTop + row * SLOT_SIZE,
                        false, mouseX, mouseY);
            }
        }

        int hotbarLabelY = storageTop + 3 * SLOT_SIZE + 7;
        graphics.drawString(font, "Hotbar [0-8]", gridX, hotbarLabelY, HEADER_COLOR, false);
        int hotbarTop = hotbarLabelY + 13;
        for (int slot = 0; slot < 9; slot++) {
            renderSlot(graphics, snapshot.mainInventory().get(slot),
                    "main[" + slot + "] (hotbar " + (slot + 1) + ")",
                    gridX + slot * SLOT_SIZE, hotbarTop,
                    slot == snapshot.selectedSlot(), mouseX, mouseY);
        }

        int equipY = originY + equipmentTop;
        graphics.drawString(font, "Equipment", equipmentX, equipY, HEADER_COLOR, false);
        equipY += 13;
        String[] armorNames = {"head armor[3]", "chest armor[2]", "legs armor[1]", "feet armor[0]"};
        for (int displayIndex = 0; displayIndex < 4; displayIndex++) {
            int inventoryIndex = 3 - displayIndex;
            renderSlot(graphics, snapshot.armorInventory().get(inventoryIndex), armorNames[displayIndex],
                    equipmentX, equipY + displayIndex * SLOT_SIZE, false, mouseX, mouseY);
            if (sideEquipment) {
                graphics.drawString(font, armorNames[displayIndex].split(" ")[0],
                        equipmentX + SLOT_SIZE + 4, equipY + displayIndex * SLOT_SIZE + 5,
                        MUTED_COLOR, false);
            }
        }
        int offhandY = equipY + 4 * SLOT_SIZE + 5;
        renderSlot(graphics, snapshot.offhand(), "offhand[0]", equipmentX, offhandY,
                false, mouseX, mouseY);
        graphics.drawString(font, "offhand", equipmentX + SLOT_SIZE + 4, offhandY + 5,
                MUTED_COLOR, false);
        if (mouseX < left + 1 || mouseX >= right - 1 || mouseY < top + 1 || mouseY >= bottom - 1) {
            hoveredSlot = null;
        }
        graphics.disableScissor();
        drawScrollBar(graphics, right, top, bottom);
    }

    private void renderSlot(GuiGraphics graphics, ItemStack stack, String label,
            int x, int y, boolean selected, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + SLOT_SIZE
                && mouseY >= y && mouseY < y + SLOT_SIZE;
        int border = selected ? 0xFFFFD166 : hovered ? 0xFFFFFFFF : 0xFF6D7782;
        graphics.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, border);
        graphics.fill(x + 1, y + 1, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, 0xFF191D22);
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, x + 1, y + 1);
            graphics.renderItemDecorations(font, stack, x + 1, y + 1);
        }
        if (hovered) {
            graphics.fill(x + 1, y + 1, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, 0x40FFFFFF);
            hoveredSlot = new HoveredSlot(stack, label);
        }
    }

    private void renderSlotTooltip(GuiGraphics graphics, HoveredSlot hovered, int mouseX, int mouseY) {
        ItemStack stack = hovered.stack();
        List<Component> tooltip = stack.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(Screen.getTooltipFromItem(minecraft, stack));
        if (stack.isEmpty()) {
            tooltip.add(Component.literal("Empty slot").withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.empty());
        tooltip.add(Component.literal("Slot: " + hovered.label()).withStyle(ChatFormatting.DARK_GRAY));
        if (!stack.isEmpty()) {
            tooltip.add(Component.literal("ID: " + BuiltInRegistries.ITEM.getKey(stack.getItem()))
                    .withStyle(ChatFormatting.DARK_GRAY));
            tooltip.add(Component.literal("Count: " + stack.getCount() + " / " + stack.getMaxStackSize())
                    .withStyle(ChatFormatting.DARK_GRAY));
            if (stack.isDamageableItem()) {
                tooltip.add(Component.literal("Damage: " + stack.getDamageValue() + " / " + stack.getMaxDamage())
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        graphics.renderTooltip(font, tooltip, Optional.empty(), mouseX, mouseY);
    }

    private void drawScrollBar(GuiGraphics graphics, int right, int top, int bottom) {
        int viewport = bottom - top - 2;
        if (lastContentHeight <= viewport) {
            return;
        }
        int trackTop = top + 2;
        int trackHeight = bottom - top - 4;
        int thumbHeight = Math.max(12, trackHeight * viewport / lastContentHeight);
        int maxScroll = Math.max(1, lastContentHeight - viewport);
        int thumbY = trackTop + (trackHeight - thumbHeight)
                * scrollOffsets[tab.ordinal()] / maxScroll;
        graphics.fill(right - 5, trackTop, right - 2, bottom - 2, 0xFF202832);
        graphics.fill(right - 5, thumbY, right - 2, thumbY + thumbHeight, 0xFF748494);
    }

    private void clampScroll(int viewportHeight) {
        int maximum = Math.max(0, lastContentHeight - Math.max(1, viewportHeight - 2));
        scrollOffsets[tab.ordinal()] = Mth.clamp(scrollOffsets[tab.ordinal()], 0, maximum);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int left = panelLeft() + 8;
            int availableWidth = panelRight() - panelLeft() - 16;
            int tabWidth = availableWidth / Tab.values().length;
            int top = 35;
            if (mouseY >= top && mouseY < top + 19 && mouseX >= left
                    && mouseX < left + availableWidth) {
                int index = Math.min(Tab.values().length - 1, (int) (mouseX - left) / tabWidth);
                tab = Tab.values()[index];
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
            double horizontalAmount, double verticalAmount) {
        int contentTop = 59;
        int contentBottom = height - 38;
        if (mouseX >= panelLeft() + 8 && mouseX < panelRight() - 8
                && mouseY >= contentTop && mouseY < contentBottom) {
            int viewport = contentBottom - contentTop;
            int maximum = Math.max(0, lastContentHeight - Math.max(1, viewport - 2));
            scrollOffsets[tab.ordinal()] = Mth.clamp(
                    scrollOffsets[tab.ordinal()] - (int) Math.round(verticalAmount * 18.0),
                    0, maximum);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private int panelLeft() {
        int panelWidth = Math.min(620, Math.max(220, width - 24));
        return (width - panelWidth) / 2;
    }

    private int panelRight() {
        return width - panelLeft();
    }

    private static String flags(CompanionDebugSnapshot value) {
        return "alive=" + value.alive() + ", onGround=" + value.onGround()
                + ", sprinting=" + value.sprinting() + ", crouching=" + value.crouching()
                + ", swimming=" + value.swimming() + ", fallFlying=" + value.fallFlying()
                + ", inWater=" + value.inWater() + ", inLava=" + value.inLava()
                + ", glowing=" + value.glowing() + ", invulnerable=" + value.invulnerable()
                + ", noGravity=" + value.noGravity();
    }

    private static String ticks(int value) {
        return value == 0 ? "unbounded" : value + " ticks";
    }

    private static String emptyAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String format(String pattern, Object... arguments) {
        return String.format(Locale.ROOT, pattern, arguments);
    }

    private static ResourceLocation vanillaSprite(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    private enum VitalCard {
        HEALTH("HEALTH", 0xFFFF6B6B),
        HUNGER("HUNGER", 0xFFE5B95C),
        ARMOR("ARMOR", 0xFFB8C4D2),
        AIR("AIR", 0xFF63D6E6);

        private final String label;
        private final int accentColor;

        VitalCard(String label, int accentColor) {
            this.label = label;
            this.accentColor = accentColor;
        }
    }

    private enum Tab {
        OVERVIEW("Overview"),
        INVENTORY("Inventory"),
        EXECUTION("Execution");

        private final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private record TextRow(String text, int color, int gapBefore, int indent) {
        private static TextRow header(String text) {
            return new TextRow(text, HEADER_COLOR, 7, 0);
        }

        private static TextRow value(String label, String value) {
            return new TextRow(label + ": " + value, TEXT_COLOR, 1, 0);
        }

        private static TextRow label(String text) {
            return new TextRow(text + ":", TEXT_COLOR, 1, 0);
        }

        private static TextRow plain(String text) {
            return new TextRow(text, TEXT_COLOR, 1, 0);
        }

        private static TextRow block(String text) {
            return new TextRow(text, MUTED_COLOR, 1, 8);
        }
    }

    private record WrappedLine(FormattedCharSequence text, int y, int color, int indent) {
    }

    private record HoveredSlot(ItemStack stack, String label) {
    }
}
