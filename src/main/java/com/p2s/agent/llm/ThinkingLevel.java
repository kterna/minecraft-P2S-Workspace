package com.p2s.agent.llm;

import java.util.Locale;

/**
 * Provider-neutral thinking level (pi: ThinkingLevel). A null level in the configuration means
 * "not configured": no thinking field is sent and the model default applies.
 */
public enum ThinkingLevel {
    OFF("off"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max");

    private final String id;

    ThinkingLevel(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Null for blank, "auto", "default" or unknown values. */
    public static ThinkingLevel parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (ThinkingLevel level : values()) {
            if (level.id.equals(value)) {
                return level;
            }
        }
        return null;
    }

    public boolean enabled() {
        return this != OFF;
    }

    /** Default token budgets for budget-based APIs (pi: DEFAULT_THINKING_BUDGETS). xhigh/max clamp to high. */
    public int defaultBudget() {
        return switch (this) {
            case OFF -> 0;
            case MINIMAL -> 1024;
            case LOW -> 2048;
            case MEDIUM -> 8192;
            case HIGH, XHIGH, MAX -> 16384;
        };
    }

    /** Cycles unset -> off -> minimal -> ... -> max -> unset (for the config screen). */
    public static ThinkingLevel cycle(ThinkingLevel current) {
        if (current == null) {
            return OFF;
        }
        return current == MAX ? null : values()[current.ordinal() + 1];
    }
}
