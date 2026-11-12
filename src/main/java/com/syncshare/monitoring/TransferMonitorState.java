package com.syncshare.monitoring;

import com.syncshare.ui.design.SemanticTone;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Validated lifecycle states for real-time transfer monitoring.
 *
 * <p>{@code PAUSED} and {@code RESUMING} are intentionally omitted because the
 * underlying SyncShare Protocol v2 transport does not support interactive
 * mid-transfer pause/resume commands (it only supports automatic checkpoint
 * recovery across separate connection attempts).
 */
public enum TransferMonitorState {
    QUEUED("Queued", SemanticTone.NEUTRAL, true),
    CONNECTING("Connecting", SemanticTone.INFO, true),
    RUNNING("Running", SemanticTone.TEAL, true),
    COMPLETED("Completed", SemanticTone.SUCCESS, false),
    FAILED("Failed", SemanticTone.ERROR, false),
    CANCELLED("Cancelled", SemanticTone.WARNING, false);

    private static final Map<TransferMonitorState, Set<TransferMonitorState>> VALID_TRANSITIONS =
        buildTransitionMap();

    private final String displayName;
    private final SemanticTone tone;
    private final boolean active;

    TransferMonitorState(String displayName, SemanticTone tone, boolean active) {
        this.displayName = displayName;
        this.tone = tone;
        this.active = active;
    }

    private static Map<TransferMonitorState, Set<TransferMonitorState>> buildTransitionMap() {
        EnumMap<TransferMonitorState, Set<TransferMonitorState>> map =
            new EnumMap<>(TransferMonitorState.class);
        map.put(QUEUED, Set.copyOf(EnumSet.of(CONNECTING, RUNNING, FAILED, CANCELLED)));
        map.put(CONNECTING, Set.copyOf(EnumSet.of(RUNNING, FAILED, CANCELLED)));
        map.put(RUNNING, Set.copyOf(EnumSet.of(COMPLETED, FAILED, CANCELLED)));
        map.put(COMPLETED, Set.of());
        map.put(FAILED, Set.of());
        map.put(CANCELLED, Set.of());
        return map;
    }

    public String displayName() {
        return displayName;
    }

    public SemanticTone tone() {
        return tone;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isTerminal() {
        return !active;
    }

    public Set<TransferMonitorState> allowedTransitions() {
        return VALID_TRANSITIONS.getOrDefault(this, Set.of());
    }

    public boolean canTransitionTo(TransferMonitorState target) {
        if (target == null) {
            return false;
        }
        return allowedTransitions().contains(target);
    }

    public TransferMonitorState requireValidTransition(TransferMonitorState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                "Invalid transfer state transition: " + this + " -> " + target
            );
        }
        return target;
    }
}
