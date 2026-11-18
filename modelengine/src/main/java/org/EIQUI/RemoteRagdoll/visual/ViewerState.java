package org.EIQUI.RemoteRagdoll.visual;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** UUID-only membership: no disconnected Player can be retained by a visual. */
final class ViewerState {
    enum Transition { NONE, SPAWN, DESTROY }
    private final Set<UUID> viewers = new HashSet<>();

    Transition update(UUID player, boolean visible) {
        if (visible) return viewers.add(player) ? Transition.SPAWN : Transition.NONE;
        return viewers.remove(player) ? Transition.DESTROY : Transition.NONE;
    }

    Set<UUID> snapshot() { return Set.copyOf(viewers); }
    void forget(UUID player) { viewers.remove(player); }
    void clear() { viewers.clear(); }
}
