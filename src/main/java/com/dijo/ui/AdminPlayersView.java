package com.dijo.ui;

import com.dijo.benefits.PlayerAccountService;
import com.dijo.benefits.PlayerBenefitsService;
import com.dijo.benefits.PlayerBenefitsService.PlayerBenefitRow;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
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
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.RolesAllowed;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

@Route("admin/players")
@PageTitle("Players and Benefits")
@RolesAllowed("ADMIN")
public class AdminPlayersView extends VerticalLayout {
    private final PlayerBenefitsService service;
    private final PlayerAccountService accounts;
    private final TextField search = new TextField("Search player");
    private final Grid<PlayerBenefitRow> grid = new Grid<>(PlayerBenefitRow.class, false);
    private final Span summary = new Span();
    private List<PlayerBenefitRow> rows = List.of();
    private String feeText = "";

    public AdminPlayersView(PlayerBenefitsService service, PlayerAccountService accounts,
            AuthenticationContext authentication) {
        this.service = service;
        this.accounts = accounts;
        setSizeFull();
        getStyle().set("box-sizing", "border-box");

        search.setPlaceholder("Username");
        search.setClearButtonVisible(true);
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.addValueChangeListener(e -> applyFilter());

        Button refresh = new Button("Refresh", e -> reloadRows());
        Button settings = new Button("Benefit settings",
                e -> new BenefitSettingsDialog(service, this::reloadRows).open());
        Button newPlayer = new Button("New player", e -> openEditor(null));
        newPlayer.addThemeVariants(ButtonVariant.PRIMARY);
        HorizontalLayout toolbar = new HorizontalLayout(search, refresh, settings, newPlayer);
        toolbar.setAlignItems(Alignment.END);
        toolbar.setWidthFull();
        toolbar.getStyle().set("flex-wrap", "wrap");

        grid.addColumn(PlayerBenefitRow::username).setHeader("Player").setFrozen(true).setSortable(true);
        grid.addColumn(r -> r.enabled() ? "Enabled" : "Disabled").setHeader("Account");
        grid.addColumn(r -> r.paid() ? "Signed up" : "Not signed up").setHeader("Sign-up");
        grid.addColumn(r -> "₹" + BigDecimal.valueOf(r.amountPaise(), 2).toPlainString())
                .setHeader("Amount paid");
        grid.addColumn(r -> r.paymentReference() == null ? "—" : r.paymentReference())
                .setHeader("Transaction ID");
        grid.addColumn(r -> !r.paid() ? "Not eligible"
                : r.kitHandedOverAt() == null ? "Pending handover" : "Collected")
                .setHeader("Welcome kit");
        grid.addColumn(PlayerBenefitRow::couponsIssued).setHeader("Coupons issued");
        grid.addColumn(PlayerBenefitRow::couponsRedeemed).setHeader("Redeemed");
        grid.addColumn(PlayerBenefitRow::couponsRemaining).setHeader("Remaining");
        grid.getColumns().forEach(c -> c.setAutoWidth(true));
        grid.setSizeFull();
        grid.asSingleSelect().addValueChangeListener(e -> {
            if (e.getValue() != null) openEditor(e.getValue());
        });

        HorizontalLayout links = new HorizontalLayout(
                new RouterLink("Admin home", AdminHomeView.class),
                new RouterLink("Manage events", AdminEventsView.class),
                new LogoutButton(authentication));
        links.setAlignItems(Alignment.BASELINE);
        add(new H1("Players and Benefits"), links, toolbar, summary, grid);
        setFlexGrow(1, grid);
        reloadRows();
    }

    private void openEditor(PlayerBenefitRow player) {
        PlayerEditorDialog dialog = new PlayerEditorDialog(accounts, service,
                player, this::reloadRows);
        dialog.addOpenedChangeListener(e -> {
            if (!e.isOpened()) grid.deselectAll();
        });
        dialog.open();
    }

    private void reloadRows() {
        rows = service.listPlayerBenefits();
        feeText = service.settings()
                .map(b -> " • Entry fee: " + PlayerEditorDialog.rupees(b.feePaise()))
                .orElse(" • Entry fee not set yet: open Benefit settings");
        applyFilter();
    }

    private void applyFilter() {
        String term = search.getValue().strip().toLowerCase(Locale.ROOT);
        List<PlayerBenefitRow> visible = rows.stream()
                .filter(r -> r.username().toLowerCase(Locale.ROOT).contains(term)).toList();
        grid.setItems(visible);
        long paid = visible.stream().filter(PlayerBenefitRow::paid).count();
        long collected = visible.stream().filter(r -> r.kitHandedOverAt() != null).count();
        long remaining = visible.stream().mapToLong(PlayerBenefitRow::couponsRemaining).sum();
        summary.setText("Showing " + visible.size() + " of " + rows.size()
                + " players • Signed up: " + paid + " • Kits collected: " + collected
                + " • Coupons remaining: " + remaining + feeText);
    }
}
