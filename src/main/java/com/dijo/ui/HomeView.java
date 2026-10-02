package com.dijo.ui;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

@Route("")
@PageTitle("Home")
@RolesAllowed({"ADMIN", "USER"})
public class HomeView extends VerticalLayout {

    public HomeView() {
        add(new H1("Welcome Home"));
    }
}