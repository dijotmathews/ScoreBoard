package com.dijo.events;

import java.time.LocalTime;

/**
 * One knockout match. A side is a player, a BYE, or not known yet (null player,
 * not a BYE). A BYE in a later round comes from a match between two BYEs, which is
 * never played (noMatch). The loser, or the BYE, is eliminated once the admin
 * confirms the result. Players are identified by username; player1Name, player2Name
 * and winnerName are how they are shown (their full name, else the username).
 * court and startTime say where and when it is to be played; either may be null.
 */
public record Match(String eventId, String eventName, int totalRounds, int round, int matchNo,
                    String player1, String player1Name, boolean player1Bye,
                    String player2, String player2Name, boolean player2Bye,
                    String official, String winner, String winnerName, String score,
                    String resultBy, String confirmedBy, String court, LocalTime startTime) {

    public enum Status { WAITING_FOR_PLAYERS, SCHEDULED, AWAITING_CONFIRMATION, CONFIRMED, NO_MATCH }

    public boolean byeMatch() { return player1Bye || player2Bye; }

    /** Both sides are BYEs: nothing is played, and a BYE goes through to the next round. */
    public boolean noMatch() { return player1Bye && player2Bye; }

    /** Both sides decided: two players, or a player and a BYE. */
    public boolean ready() {
        return (player1 != null || player1Bye) && (player2 != null || player2Bye);
    }

    public boolean confirmed() { return confirmedBy != null; }

    /** Still to be played: no confirmed result, and not decided by a BYE. */
    public boolean remaining() { return !confirmed() && !byeMatch(); }

    /** A court or a start time has been given. */
    public boolean scheduled() { return court != null || startTime != null; }

    public Status status() {
        if (noMatch()) return Status.NO_MATCH;
        if (confirmed()) return Status.CONFIRMED;
        if (winner != null) return Status.AWAITING_CONFIRMATION;
        return ready() ? Status.SCHEDULED : Status.WAITING_FOR_PLAYERS;
    }

    /** "Final", "Semi-final", "Quarter-final", or "Round n". */
    public String roundName() {
        return switch (totalRounds - round) {
            case 0 -> "Final";
            case 1 -> "Semi-final";
            case 2 -> "Quarter-final";
            default -> "Round " + round;
        };
    }

    /** The shown name of a side: the player's name, "BYE" or "TBD". */
    public String side1() { return side(player1Name, player1Bye); }
    public String side2() { return side(player2Name, player2Bye); }

    /** The shown name of a player in this match (player1, player2 or winner). */
    public String nameOf(String player) {
        if (player == null) return null;
        if (player.equals(player1)) return player1Name;
        if (player.equals(player2)) return player2Name;
        return player.equals(winner) ? winnerName : player;
    }

    private static String side(String name, boolean bye) {
        return bye ? "BYE" : name == null ? "TBD" : name;
    }
}
