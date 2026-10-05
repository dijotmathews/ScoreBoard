package com.dijo.events;

/**
 * One knockout match. A side is a player, a BYE (round 1 only), or not known yet
 * (null player, not a BYE). The loser, or the BYE, is eliminated once the admin
 * confirms the result.
 */
public record Match(String eventId, String eventName, int totalRounds, int round, int matchNo,
                    String player1, boolean player1Bye, String player2, boolean player2Bye,
                    String official, String winner, String score,
                    String resultBy, String confirmedBy) {

    public enum Status { WAITING_FOR_PLAYERS, SCHEDULED, AWAITING_CONFIRMATION, CONFIRMED }

    public boolean byeMatch() { return player1Bye || player2Bye; }

    /** Both sides decided: two players, or a player and a BYE. */
    public boolean ready() {
        return (player1 != null || player1Bye) && (player2 != null || player2Bye);
    }

    public boolean confirmed() { return confirmedBy != null; }

    public Status status() {
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

    public String side1() { return side(player1, player1Bye); }
    public String side2() { return side(player2, player2Bye); }

    private static String side(String player, boolean bye) {
        return bye ? "BYE" : player == null ? "TBD" : player;
    }
}
