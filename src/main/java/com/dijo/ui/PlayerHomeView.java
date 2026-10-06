package com.dijo.ui;

import com.dijo.events.Match;
import com.dijo.events.MatchService;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.RolesAllowed;
import java.util.List;

@Route("player")
@PageTitle("Player Home")
@RolesAllowed("USER")
public class PlayerHomeView extends MobileHomeLayout {

    public PlayerHomeView(MatchService matches, AuthenticationContext authentication) {
        super("Player Home");
        String me = authentication.getPrincipalName().orElse("");
        add(new Span("Welcome, " + me + ". Use the menu for your matches, event draws and profile."),
                new H3("Your next matches"));

        // Matches with both sides known and no confirmed result yet.
        List<Match> upcoming = matches.myPlayerMatches().stream()
                .filter(m -> !m.confirmed() && !m.byeMatch() && m.ready())
                .toList();
        if (upcoming.isEmpty()) add(new Span("No matches to play right now."));
        upcoming.forEach(m -> add(new Span(m.eventName() + " • " + m.roundName() + ": vs "
                + (me.equals(m.player1()) ? m.side2() : m.side1())
                + (m.winner() == null ? "" : " — result awaiting confirmation"))));
    }
}
