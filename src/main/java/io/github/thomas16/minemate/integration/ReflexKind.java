package io.github.thomas16.minemate.integration;

/** PlayerEngine chains that may take control of the body without a MineMate goal. */
public enum ReflexKind {
    NONE,
    MOB_DEFENSE,
    PLAYER_DEFENSE,
    EATING,
    MLG_CLUTCH,
    EQUIPMENT,
    UNSTUCK,
    WORLD_SURVIVAL,
    OTHER
}
