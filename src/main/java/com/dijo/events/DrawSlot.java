package com.dijo.events;

/**
 * One position in an event's draw: a player, a BYE, or empty. In doubles the player
 * leads a team with a partner (null until placed). The names are how players are shown.
 */
public record DrawSlot(String eventId, int slot, String playerUsername, String playerName,
                       String partnerUsername, String partnerName, boolean bye) {
    /** "Ada & Bob", or the player's name without a partner; null when there is no player. */
    public String teamName() {
        return playerName == null || partnerName == null ? playerName : playerName + " & " + partnerName;
    }

    public boolean empty() { return playerUsername == null && !bye; }
}
