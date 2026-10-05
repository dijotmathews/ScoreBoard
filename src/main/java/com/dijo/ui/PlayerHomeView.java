package com.dijo.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.RolesAllowed;

@Route("player")
@PageTitle("Player Home")
@RolesAllowed("USER")
public class PlayerHomeView extends MobileHomeLayout {

    public PlayerHomeView(AuthenticationContext authentication) {
        super("Player Home");

        Button matches = new Button("My Matches",
                e -> getUI().ifPresent(ui -> ui.navigate(PlayerMatchesView.class)));
        matches.setWidthFull();

        Button profile = new Button("My Profile",
                e -> getUI().ifPresent(ui -> ui.navigate(PlayerProfileView.class)));
        profile.setWidthFull();

        LogoutButton logout = new LogoutButton(authentication);
        logout.setWidthFull();

        add(matches, profile, logout);
    }
}
