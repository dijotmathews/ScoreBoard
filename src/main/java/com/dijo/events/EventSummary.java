package com.dijo.events;

/** filled = draw slots holding a player or a BYE. */
public record EventSummary(Event event, int filled) {}
