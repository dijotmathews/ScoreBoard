package com.dijo.ui;

import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

public abstract class MobileHomeLayout extends VerticalLayout {

    protected MobileHomeLayout(String title) {
        addClassName("mobile-home");

        setWidthFull();
        setPadding(false);
        setSpacing(false);

        H2 heading = new H2(title);
        heading.addClassName("mobile-home-title");

        add(heading);
    }
}