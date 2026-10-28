package com.dijo.events;

/**
 * A competition within the tournament; contestantCount is its draw size (a power of 2).
 * teamSize is the number of players per side: 1 for singles, 2 for doubles.
 */
public record Event(String id, String name, int contestantCount, boolean drawLocked, int teamSize) {
    public boolean doubles() { return teamSize == 2; }
}
