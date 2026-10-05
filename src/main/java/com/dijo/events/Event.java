package com.dijo.events;

/** A competition within the tournament; contestantCount is its draw size (a power of 2). */
public record Event(String id, String name, int contestantCount, boolean drawLocked) {}
