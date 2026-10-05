package com.dijo.ui;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.RolesAllowed;

@Route("admin")
@PageTitle("Admin Home")
@RolesAllowed("ADMIN")
public class AdminHomeView extends VerticalLayout {

    public AdminHomeView(AuthenticationContext authentication) {
        HorizontalLayout links = new HorizontalLayout(
                new RouterLink("Players and Benefits", AdminPlayersView.class),
                new RouterLink("Manage events", AdminEventsView.class),
                new LogoutButton(authentication));
        links.setAlignItems(Alignment.BASELINE);
        add(new H1("Admin Home"), links);
    }
}
