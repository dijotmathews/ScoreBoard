package com.dijo.events;

/**
 * A player's place in one event's draw. opponent is null while the other slot of
 * their match is empty or holds a BYE (opponentBye).
 */
public record PlayerDraw(String eventId, String eventName, int slot, String opponent,
                         boolean opponentBye, boolean drawLocked) {
    public int match() { return (slot + 1) / 2; }
}
