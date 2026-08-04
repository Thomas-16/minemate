package io.github.thomas16.minemate.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A point-in-time view of the companion and its surroundings: the canonical state, plus a compact
 * ASCII rendering sized for a planner prompt.
 *
 * <p>The record is the source of truth; {@link #render()} is one lossy view of it. Callers that need
 * to reason about the world (tests, the debug UI) read fields, and only the planner reads the text.
 * Keeping those separate is what lets the wire format change without breaking anything else.
 */
public record WorldSnapshot(
        long gameTime, long dayTime, DayPhase phase, boolean raining, boolean thundering,
        String dimension, String biome,
        SelfState self, OwnerState owner,
        List<ThreatState> threats, List<EntityState> entities,
        List<ItemState> hotbar, List<ItemState> equipment, List<ItemState> notableInventory,
        int notableKindsOmitted,
        ActivityState activity,
        OutcomeState lastOutcome,
        ReflexState reflex,
        List<JournalEntry> recentEvents) {

    public enum DayPhase { DAY, SUNSET, NIGHT, SUNRISE }

    /** Character budget for a rendered snapshot; ~4 characters per token. */
    public record RenderBudget(int maxChars) {
        public static final RenderBudget DEFAULT = new RenderBudget(1200);
    }

    public record SelfState(int blockX, int blockY, int blockZ, double x, double y, double z,
            float health, float maxHealth, int food, float saturation, int armor, int lightLevel,
            boolean onGround, boolean inWater, boolean inLava, boolean burning, int airPercent,
            String mainHand, String standingOn) {
    }

    /** {@code present} is false when the owner is unset, offline, or in another dimension. */
    public record OwnerState(boolean present, String name, boolean sameDimension, double distance,
            int dx, int dy, int dz, String direction, float health) {

        public static OwnerState absent() {
            return new OwnerState(false, "", false, -1.0, 0, 0, 0, "", 0.0F);
        }
    }

    public record ThreatState(UUID id, String type, double distance, String direction,
            float health, float maxHealth, boolean targetingCompanion, boolean visible) {
    }

    public record EntityState(UUID id, String type, double distance, String direction) {
    }

    public record ItemState(String id, int count) {
        public static final ItemState EMPTY = new ItemState("", 0);

        public boolean present() {
            return !id.isEmpty() && count > 0;
        }
    }

    public record ActivityState(String goalId, String kind, String description, String scope,
            int elapsedTicks, int timeoutTicks, String progressText, String preemptingChain,
            String planId, int planStep, int planSteps) {
    }

    public record OutcomeState(String kind, String status, String reason,
            int have, int want, long elapsedMs) {
    }

    /**
     * @param mobDefenseLeashed true when MineMate is holding PlayerEngine's mob defense back.
     *                          Rendered as a word, never a bare boolean: the underlying engine flag
     *                          is inverted relative to its name and reads backwards to a reader.
     * @param activeTicks       how long the current reflex has been running, or 0 when idle
     */
    public record ReflexState(String kind, String detail, boolean mobDefenseLeashed, int activeTicks) {
    }

    public WorldSnapshot {
        Objects.requireNonNull(phase, "phase");
        threats = List.copyOf(threats);
        entities = List.copyOf(entities);
        hotbar = List.copyOf(hotbar);
        equipment = List.copyOf(equipment);
        notableInventory = List.copyOf(notableInventory);
        recentEvents = List.copyOf(recentEvents);
    }

    public String render() {
        return render(RenderBudget.DEFAULT);
    }

    /** Rough token estimate for budget checks and telemetry. */
    public int estimatedTokens() {
        return render().length() / 4;
    }

    /**
     * Compact ASCII lines, degraded in a fixed order when over budget.
     *
     * <p>Truncation drops whole low-value sections from the bottom of a priority list rather than
     * chopping the tail, so the planner never receives a half-line and never silently loses the
     * threat list to a long inventory.
     */
    public String render(RenderBudget budget) {
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("SELF", renderSelf());
        sections.put("WORLD", renderWorld());
        if (owner.present()) {
            sections.put("OWNER", renderOwner());
        }
        if (!threats.isEmpty()) {
            sections.put("THREATS", renderThreats());
        }
        if (!entities.isEmpty()) {
            sections.put("NEAR", renderEntities());
        }
        String gear = renderGear();
        if (!gear.isEmpty()) {
            sections.put("GEAR", gear);
        }
        String hotbarLine = renderHotbar();
        if (!hotbarLine.isEmpty()) {
            sections.put("HOTBAR", hotbarLine);
        }
        String items = renderItems();
        if (!items.isEmpty()) {
            sections.put("ITEMS", items);
        }
        if (activity != null) {
            sections.put("NOW", renderActivity());
        }
        String reflexLine = renderReflex();
        if (!reflexLine.isEmpty()) {
            sections.put("REFLEX", reflexLine);
        }
        if (lastOutcome != null) {
            sections.put("LAST", renderOutcome());
        }
        List<JournalEntry> events = new ArrayList<>(recentEvents);

        // Lowest value first. SELF, WORLD, OWNER, THREATS and NOW are never dropped.
        List<String> droppable = List.of("ITEMS", "NEAR", "HOTBAR", "GEAR");
        int dropIndex = 0;
        while (length(sections, events) > budget.maxChars() && dropIndex < droppable.size()) {
            sections.remove(droppable.get(dropIndex++));
        }
        while (length(sections, events) > budget.maxChars() && !events.isEmpty()) {
            events.remove(events.size() - 1);
        }
        if (length(sections, events) > budget.maxChars()) {
            sections.remove("LAST");
        }
        return assemble(sections, events);
    }

    private int length(Map<String, String> sections, List<JournalEntry> events) {
        return assemble(sections, events).length();
    }

    private String assemble(Map<String, String> sections, List<JournalEntry> events) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> section : sections.entrySet()) {
            text.append(section.getKey()).append(' ').append(section.getValue()).append('\n');
        }
        if (!events.isEmpty()) {
            text.append("EVENTS\n");
            for (JournalEntry entry : events) {
                text.append(entry.render(gameTime)).append('\n');
            }
        }
        return text.toString();
    }

    private String renderSelf() {
        StringBuilder text = new StringBuilder();
        text.append("hp=").append(trim(self.health())).append('/').append(trim(self.maxHealth()))
                .append(" food=").append(self.food())
                .append(" armor=").append(self.armor())
                .append(" pos=").append(self.blockX()).append(',').append(self.blockY())
                .append(',').append(self.blockZ())
                .append(" light=").append(self.lightLevel())
                .append(" on=").append(blank(self.standingOn()));
        if (!self.mainHand().isEmpty()) {
            text.append(" hand=").append(self.mainHand());
        }
        if (self.burning()) {
            text.append(" burning");
        }
        if (self.inLava()) {
            text.append(" in_lava");
        } else if (self.inWater()) {
            text.append(" in_water air=").append(self.airPercent()).append('%');
        }
        return bounded(text.toString(), 140);
    }

    private String renderWorld() {
        String weather = thundering ? "storm" : raining ? "rain" : "clear";
        return bounded("%s/%s t=%d %s %s".formatted(blank(dimension), blank(biome), dayTime,
                phase.name().toLowerCase(java.util.Locale.ROOT), weather), 100);
    }

    private String renderOwner() {
        return bounded("%s %.1fm %s dy=%+d hp=%s".formatted(blank(owner.name()), owner.distance(),
                blank(owner.direction()), owner.dy(), trim(owner.health())), 100);
    }

    private String renderThreats() {
        StringBuilder text = new StringBuilder().append(threats.size()).append(": ");
        for (int index = 0; index < threats.size(); index++) {
            ThreatState threat = threats.get(index);
            if (index > 0) {
                text.append(" | ");
            }
            text.append(threat.type()).append(' ').append("%.1fm".formatted(threat.distance()))
                    .append(' ').append(threat.direction())
                    .append(" hp=").append(trim(threat.health())).append('/').append(trim(threat.maxHealth()));
            if (threat.targetingCompanion()) {
                text.append(" aggro");
            }
            if (threat.visible()) {
                text.append(" los");
            }
        }
        return bounded(text.toString(), 220);
    }

    private String renderEntities() {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < entities.size(); index++) {
            EntityState entity = entities.get(index);
            if (index > 0) {
                text.append(", ");
            }
            text.append(entity.type()).append(' ').append("%.1fm".formatted(entity.distance()))
                    .append(' ').append(entity.direction());
        }
        return bounded(text.toString(), 140);
    }

    private String renderGear() {
        // Fixed slot order: helmet, chest, legs, boots, offhand.
        String[] labels = {"helmet", "chest", "legs", "boots", "off"};
        StringBuilder text = new StringBuilder();
        boolean any = false;
        for (int index = 0; index < labels.length; index++) {
            ItemState slot = index < equipment.size() ? equipment.get(index) : ItemState.EMPTY;
            if (index > 0) {
                text.append(' ');
            }
            text.append(labels[index]).append('=').append(slot.present() ? withCount(slot) : "-");
            any |= slot.present();
        }
        return any ? bounded(text.toString(), 160) : "";
    }

    private String renderHotbar() {
        StringBuilder text = new StringBuilder();
        boolean any = false;
        for (int index = 0; index < hotbar.size(); index++) {
            if (index > 0) {
                text.append(", ");
            }
            ItemState slot = hotbar.get(index);
            text.append(slot.present() ? withCount(slot) : "-");
            any |= slot.present();
        }
        return any ? bounded(text.toString(), 200) : "";
    }

    private String renderItems() {
        if (notableInventory.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < notableInventory.size(); index++) {
            if (index > 0) {
                text.append(", ");
            }
            text.append(withCount(notableInventory.get(index)));
        }
        if (notableKindsOmitted > 0) {
            text.append(" (+").append(notableKindsOmitted).append(" more)");
        }
        return bounded(text.toString(), 220);
    }

    private String renderActivity() {
        StringBuilder text = new StringBuilder(activity.description())
                .append(" [").append(activity.scope()).append(']');
        if (!activity.planId().isEmpty()) {
            text.append(" step ").append(activity.planStep()).append('/').append(activity.planSteps())
                    .append(" of ").append(activity.planId());
        }
        if (!activity.progressText().isEmpty()) {
            text.append(" - ").append(activity.progressText());
        }
        text.append(" - ").append(activity.elapsedTicks()).append('/')
                .append(activity.timeoutTicks() == 0 ? "unbounded" : activity.timeoutTicks()).append('t');
        if (!activity.preemptingChain().isEmpty()) {
            text.append(" - paused: ").append(activity.preemptingChain());
        }
        return bounded(text.toString(), 240);
    }

    private String renderReflex() {
        String leash = reflex.mobDefenseLeashed() ? "mob_defense=leashed" : "mob_defense=active";
        if (reflex.kind().isEmpty() || "NONE".equalsIgnoreCase(reflex.kind())) {
            return bounded("none " + leash, 80);
        }
        String detail = reflex.detail().isEmpty() ? "" : " " + reflex.detail();
        return bounded("%s%s (%dt) %s".formatted(reflex.kind().toLowerCase(java.util.Locale.ROOT),
                detail, reflex.activeTicks(), leash), 120);
    }

    private String renderOutcome() {
        return bounded("%s %s %s %d/%d %ds".formatted(lastOutcome.kind(), lastOutcome.status(),
                lastOutcome.reason(), lastOutcome.have(), lastOutcome.want(),
                lastOutcome.elapsedMs() / 1000L), 120);
    }

    private static String withCount(ItemState item) {
        return item.count() > 1 ? item.id() + " x" + item.count() : item.id();
    }

    private static String trim(float value) {
        return value == Math.floor(value) ? String.valueOf((int) value) : "%.1f".formatted(value);
    }

    private static String blank(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static String bounded(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum - 3) + "...";
    }
}
