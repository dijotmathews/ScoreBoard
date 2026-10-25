package com.dijo.events;

/** One position in an event's draw: a player, a BYE, or empty. playerName is how the player is shown. */
public record DrawSlot(String eventId, int slot, String playerUsername, String playerName, boolean bye) {
    public boolean empty() { return playerUsername == null && !bye; }
}
