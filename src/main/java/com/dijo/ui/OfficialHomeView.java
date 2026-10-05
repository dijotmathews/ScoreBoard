package com.dijo.ui;

import com.dijo.events.Match;
import com.dijo.events.MatchService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.RolesAllowed;
import java.util.List;

@Route("official")
@PageTitle("Official Home")
@RolesAllowed("OFFICIAL")
public class OfficialHomeView extends MobileHomeLayout {
    private final MatchService matches;
    private final VerticalLayout list = new VerticalLayout();

    public OfficialHomeView(MatchService matches, AuthenticationContext authentication) {
        super("Official Home");
        this.matches = matches;
        list.setPadding(false);
        LogoutButton logout = new LogoutButton(authentication);
        logout.setWidthFull();
        add(logout, new H3("Matches I officiate"), list);
        reload();
    }

    private void reload() {
        list.removeAll();
        List<Match> mine = matches.myMatches();
        if (mine.isEmpty()) list.add(new Span("No matches assigned to you yet."));
        mine.forEach(match -> list.add(matchCard(match)));
    }

    private Card matchCard(Match match) {
        Card card = new Card();
        card.setWidthFull();
        card.setTitle(match.side1() + " vs " + match.side2());
        card.setSubtitle(match.eventName() + " • " + match.roundName() + ", match " + match.matchNo());
        card.add(new Span(AdminMatchesView.statusText(match.status())
                + (match.winner() == null ? ""
                        : " • Winner: " + match.winner()
                                + (match.score() == null ? "" : " (" + match.score() + ")"))));
        boolean canEnter = match.ready() && !match.byeMatch() && !match.confirmed();
        if (canEnter) {
            Button enter = new Button(match.winner() == null ? "Enter result" : "Update result",
                    e -> new MatchResultDialog(matches, match, false, this::reload).open());
            enter.addThemeVariants(ButtonVariant.PRIMARY);
            card.addToFooter(enter);
        }
        return card;
    }
}
