package com.dijo.ui;

import com.dijo.benefits.PlayerBenefitsService;
import com.dijo.benefits.PlayerBenefitsService.Event;
import com.dijo.benefits.PlayerBenefitsService.PlayerBenefitRow;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import jakarta.annotation.security.RolesAllowed;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

@Route("admin/players")
@PageTitle("Players and Benefits")
@RolesAllowed("ADMIN")
public class AdminPlayersView extends VerticalLayout {
    private final PlayerBenefitsService service;
    private final ComboBox<Event> events = new ComboBox<>("Event");
    private final TextField search = new TextField("Search player");
    private final Grid<PlayerBenefitRow> grid = new Grid<>(PlayerBenefitRow.class, false);
    private final Span summary = new Span();
    private List<PlayerBenefitRow> rows = List.of();

    public AdminPlayersView(PlayerBenefitsService service) {
        this.service = service;
        setSizeFull();
        getStyle().set("box-sizing", "border-box");

        events.setItemLabelGenerator(Event::name);
        events.setWidth("280px");
        events.addValueChangeListener(e -> reloadRows());
        search.setPlaceholder("Username");
        search.setClearButtonVisible(true);
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.addValueChangeListener(e -> applyFilter());

        Button refresh = new Button("Refresh", e -> refreshEvents());
        HorizontalLayout toolbar = new HorizontalLayout(events, search, refresh);
        toolbar.setAlignItems(Alignment.END);
        toolbar.setWidthFull();
        toolbar.getStyle().set("flex-wrap", "wrap");

        grid.addColumn(PlayerBenefitRow::username).setHeader("Player").setFrozen(true).setSortable(true);
        grid.addColumn(r -> r.enabled() ? "Enabled" : "Disabled").setHeader("Account");
        grid.addColumn(r -> r.paid() ? "Paid" : "Unpaid").setHeader("Entry fee");
        grid.addColumn(r -> "₹" + BigDecimal.valueOf(r.amountPaise(), 2).toPlainString())
                .setHeader("Amount paid");
        grid.addColumn(r -> r.paymentReference() == null ? "—" : r.paymentReference())
                .setHeader("Payment reference");
        grid.addColumn(r -> !r.paid() ? "Not eligible"
                : r.kitHandedOverAt() == null ? "Pending handover" : "Collected")
                .setHeader("Welcome kit");
        grid.addColumn(PlayerBenefitRow::couponsIssued).setHeader("Coupons issued");
        grid.addColumn(PlayerBenefitRow::couponsRedeemed).setHeader("Redeemed");
        grid.addColumn(PlayerBenefitRow::couponsRemaining).setHeader("Remaining");
        grid.getColumns().forEach(c -> c.setAutoWidth(true).setFlexGrow(0));
        grid.setSizeFull();

        add(new H1("Players and Benefits"),
                new RouterLink("Admin home", AdminHomeView.class), toolbar, summary, grid);
        setFlexGrow(1, grid);
        refreshEvents();
    }

    private void refreshEvents() {
        String selectedId = events.getValue() == null ? null : events.getValue().id();
        List<Event> available = service.events();
        events.setItems(available);
        Event selected = available.stream().filter(e -> e.id().equals(selectedId))
                .findFirst().orElse(available.isEmpty() ? null : available.getFirst());
        events.setValue(selected);
        reloadRows();
    }

    private void reloadRows() {
        rows = events.getValue() == null ? List.of()
                : service.listPlayerBenefits(events.getValue().id());
        applyFilter();
    }

    private void applyFilter() {
        String term = search.getValue().strip().toLowerCase(Locale.ROOT);
        List<PlayerBenefitRow> visible = rows.stream()
                .filter(r -> r.username().toLowerCase(Locale.ROOT).contains(term)).toList();
        grid.setItems(visible);
        if (events.getValue() == null) {
            summary.setText("No events available. Create an event first.");
            return;
        }
        long paid = visible.stream().filter(PlayerBenefitRow::paid).count();
        long collected = visible.stream().filter(r -> r.kitHandedOverAt() != null).count();
        long remaining = visible.stream().mapToLong(PlayerBenefitRow::couponsRemaining).sum();
        summary.setText("Showing " + visible.size() + " of " + rows.size()
                + " players • Paid: " + paid + " • Kits collected: " + collected
                + " • Coupons remaining: " + remaining);
    }
}
