package com.dijo.ui;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

@Route("admin")
@PageTitle("Admin Home")
@RolesAllowed("ADMIN")
public class AdminHomeView extends VerticalLayout {

    public AdminHomeView() {
        add(new H1("Admin Home"),
                new Span("Use the menu to manage players, benefits and events."));
    }
}
