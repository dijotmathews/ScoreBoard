package com.dijo.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

@Route("official")
@PageTitle("Official Home")
@RolesAllowed("OFFICIAL")
public class OfficialHomeView extends MobileHomeLayout {

    public OfficialHomeView() {
        super("Official Home");

        Button matches = new Button("Manage Matches");
        matches.setWidthFull();

        Button results = new Button("Enter Results");
        results.setWidthFull();

        add(matches, results);
    }
}
