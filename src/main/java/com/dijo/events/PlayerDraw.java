package com.dijo.events;

/**
 * A player's place in one event's draw. opponent is null while the other slot of
 * their match is empty or holds a BYE (opponentBye); opponentName is how they are shown
 * (a team, "Ada & Bob", in doubles). slot is the team's slot when the player is a partner.
 */
public record PlayerDraw(String eventId, String eventName, int slot, String opponent,
                         String opponentName, boolean opponentBye, boolean drawLocked) {
    public int match() { return (slot + 1) / 2; }
}
