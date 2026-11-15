package io.github.lumiagg.gestureevo;

// Queried immediately before injecting DOWN; revocation never requires a UI-thread round trip.
interface ITouchInterruptionGuard {
    boolean isCurrent() = 0;
}
