package com.dijo.ui;

import static com.dijo.ui.PlayerUi.badge;

import com.dijo.benefits.PlayerAccountService;
import com.dijo.benefits.PlayerBenefitsService;
import com.dijo.benefits.PlayerBenefitsService.PlayerBenefitRow;
import com.vaadin.flow.component.avatar.Avatar;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

@Route("admin/players")
@PageTitle("Players and Benefits")
@RolesAllowed("ADMIN")
public class AdminPlayersView extends VerticalLayout {
    /** Quick filters above the grid. */
    private enum Show {
        ALL("All players", r -> true),
        SIGNED_UP("Signed up", PlayerBenefitRow::paid),
        NOT_SIGNED_UP("Not signed up", r -> !r.paid()),
        KIT_TO_COLLECT("Kit to collect", r -> r.paid() && r.kitHandedOverAt() == null);

        final String label;
        final Predicate<PlayerBenefitRow> test;

        Show(String label, Predicate<PlayerBenefitRow> test) {
            this.label = label;
            this.test = test;
        }
    }

    private final PlayerBenefitsService service;
    private final PlayerAccountService accounts;
    private final TextField search = new TextField();
    private final Select<Show> show = new Select<>();
    private final Div stats = new Div();
    private final Span showing = new Span();
    private final Grid<PlayerBenefitRow> grid = new Grid<>(PlayerBenefitRow.class, false);
    private List<PlayerBenefitRow> rows = List.of();

    public AdminPlayersView(PlayerBenefitsService service, PlayerAccountService accounts) {
        this.service = service;
        this.accounts = accounts;
        setSizeFull();
        getStyle().set("box-sizing", "border-box");

        // Header: title and description, page actions on the right.
        Button settings = new Button("Benefit settings", VaadinIcon.COG.create(),
                e -> new BenefitSettingsDialog(service, this::reloadRows).open());
        Button newPlayer = new Button("New player", VaadinIcon.PLUS.create(), e -> openEditor(null));
        newPlayer.addThemeVariants(ButtonVariant.PRIMARY);
        Div header = PlayerUi.pageHeader("Players and Benefits",
                "Player accounts, entry fee sign-ups, welcome kits and coupons.", settings, newPlayer);

        stats.addClassName("stat-tiles");

        search.setPlaceholder("Search players");
        search.setPrefixComponent(VaadinIcon.SEARCH.create());
        search.setClearButtonVisible(true);
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.setAriaLabel("Search players");
        search.addValueChangeListener(e -> applyFilter());
        show.setItems(Show.values());
        show.setItemLabelGenerator(s -> s.label);
        show.setValue(Show.ALL);
        show.setAriaLabel("Show");
        show.addValueChangeListener(e -> applyFilter());
        Button refresh = new Button(VaadinIcon.REFRESH.create(), e -> reloadRows());
        refresh.addThemeVariants(ButtonVariant.TERTIARY);
        refresh.setAriaLabel("Refresh");
        refresh.setTooltipText("Refresh");
        showing.addClassName("muted");
        Div toolbar = new Div(search, show, refresh, showing);
        toolbar.addClassName("grid-toolbar");

        grid.addComponentColumn(this::playerCell).setHeader("Player").setFrozen(true)
                .setComparator(PlayerBenefitRow::username).setSortable(true).setFlexGrow(2);
        grid.addComponentColumn(r -> r.paid() ? badge("Signed up", BadgeVariant.SUCCESS)
                : badge("Not signed up", BadgeVariant.WARNING)).setHeader("Sign-up");
        grid.addColumn(r -> r.paid() ? PlayerEditorDialog.rupees(r.amountPaise()) : "—")
                .setHeader("Amount").setTextAlign(ColumnTextAlign.END);
        grid.addColumn(r -> r.paymentReference() == null ? "—" : r.paymentReference())
                .setHeader("Transaction ID").setFlexGrow(2);
        grid.addComponentColumn(r -> !r.paid() ? PlayerUi.muted("—")
                : r.kitHandedOverAt() == null ? badge("To collect", BadgeVariant.WARNING)
                : badge("Collected", BadgeVariant.SUCCESS)).setHeader("Welcome kit");
        grid.addColumn(r -> r.paid() ? r.couponsRemaining() + " of " + r.couponsIssued() + " left" : "—")
                .setHeader("Coupons");
        grid.getColumns().forEach(c -> c.setAutoWidth(true));
        grid.addThemeVariants(GridVariant.ROW_STRIPES);
        grid.setSizeFull();
        grid.asSingleSelect().addValueChangeListener(e -> {
            if (e.getValue() != null) openEditor(e.getValue());
        });

        add(header, stats, toolbar, grid);
        setFlexGrow(1, grid);
        reloadRows();
    }

    private Div playerCell(PlayerBenefitRow row) {
        Avatar avatar = new Avatar(row.username());
        avatar.addThemeName("xsmall");
        Div cell = new Div(avatar, new Span(row.username()));
        if (!row.enabled()) cell.add(badge("Disabled", BadgeVariant.ERROR));
        cell.addClassName("player-cell");
        return cell;
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
        long signedUp = rows.stream().filter(PlayerBenefitRow::paid).count();
        long collected = rows.stream().filter(r -> r.kitHandedOverAt() != null).count();
        long couponsLeft = rows.stream().mapToLong(PlayerBenefitRow::couponsRemaining).sum();
        long couponsIssued = rows.stream().mapToLong(PlayerBenefitRow::couponsIssued).sum();
        stats.removeAll();
        stats.add(PlayerUi.statTile("Players", String.valueOf(rows.size()), null),
                PlayerUi.statTile("Signed up", String.valueOf(signedUp), "of " + rows.size() + " players"),
                PlayerUi.statTile("Kits collected", String.valueOf(collected), "of " + signedUp + " kits"),
                PlayerUi.statTile("Coupons left", String.valueOf(couponsLeft), "of " + couponsIssued + " issued"),
                PlayerUi.statTile("Entry fee", service.settings().map(b -> PlayerEditorDialog.rupees(b.feePaise()))
                        .orElse("Not set"), "per player"));
        applyFilter();
    }

    private void applyFilter() {
        String term = search.getValue().strip().toLowerCase(Locale.ROOT);
        Show filter = show.getValue() == null ? Show.ALL : show.getValue();
        List<PlayerBenefitRow> visible = rows.stream()
                .filter(r -> r.username().toLowerCase(Locale.ROOT).contains(term))
                .filter(filter.test).toList();
        grid.setItems(visible);
        showing.setText(visible.size() == rows.size() ? rows.size() + " players"
                : "Showing " + visible.size() + " of " + rows.size() + " players");
    }
}
