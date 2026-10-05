package com.dijo.events;

/** One position in an event's draw: a player, a BYE, or empty. */
public record DrawSlot(String eventId, int slot, String playerUsername, boolean bye) {
    public boolean empty() { return playerUsername == null && !bye; }
}
