package com.dijo.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

@Route("player")
@PageTitle("Player Home")
@RolesAllowed("USER")
public class PlayerHomeView extends MobileHomeLayout {

    public PlayerHomeView() {
        super("Player Home");

        Button matches = new Button("My Matches");
        matches.setWidthFull();

        Button profile = new Button("My Profile");
        profile.setWidthFull();

        add(matches, profile);
    }
}
