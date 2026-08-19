package com.equipmock.agent.config;

import java.util.Objects;

/**
 * settings.json 的不可变快照（03 §1）：activeGroup + mockEnabled。
 */
public final class SettingsSnapshot {

    public static final SettingsSnapshot DEFAULTS = new SettingsSnapshot("default", true);

    public final String activeGroup;
    public final boolean mockEnabled;

    public SettingsSnapshot(String activeGroup, boolean mockEnabled) {
        this.activeGroup = activeGroup;
        this.mockEnabled = mockEnabled;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SettingsSnapshot)) {
            return false;
        }
        SettingsSnapshot that = (SettingsSnapshot) o;
        return mockEnabled == that.mockEnabled
                && Objects.equals(activeGroup, that.activeGroup);
    }

    @Override
    public int hashCode() {
        return Objects.hash(activeGroup, mockEnabled);
    }

    @Override
    public String toString() {
        return "Settings{activeGroup=" + activeGroup + ", mockEnabled=" + mockEnabled + '}';
    }
}
