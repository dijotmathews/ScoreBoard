package com.dijo.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.dijo.benefits.PlayerBenefitsService;
import com.dijo.benefits.PlayerCsv;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

@WithMockUser(username = "admin", roles = {"ADMIN", "USER"})
class PlayerImportDialogTest extends UiTest {
    static final String CSV = "Timestamp,Email Address,Players Name(AITA/AADHAR),"
            + "Date of Birth (DD/MM/YYYY),Birth Certificate (PDF/JPEG),Events,Payment Amount,"
            + "Transaction Reference\n"
            + "t,e,Ada Li,d,b,ev,500,UPI1\n"
            + "t,e,,d,b,ev,500,UPI2\n";

    @Autowired
    PlayerBenefitsService benefits;

    @BeforeEach
    void setUp() {
        data.account("admin", "ADMIN", "USER");
        asAdmin(() -> benefits.updateSettings(50000, 0, "None"));
    }

    @Test
    void previewsThenImportsTheUploadedFile() {
        navigate(AdminPlayersView.class);
        click("Import CSV");
        PlayerImportDialog dialog = find(PlayerImportDialog.class).single();
        dialog.showFile(CSV.getBytes(StandardCharsets.UTF_8));

        assertThat(find(Span.class).withText(
                "1 of 2 rows ready to import; rows with problems will be skipped.").exists()).isTrue();
        click("Import 1 player");

        assertThat(find(Span.class).withText(
                "1 players created (1 signed up, 0 without payment), 0 skipped, 1 failed.").exists()).isTrue();
        click("Close");
        assertThat(test(find(Grid.class).single()).size()).isEqualTo(1); // Ada; staff are not listed
    }

    @Test
    void showsTheExpectedFormatUntilAFileIsUploaded() {
        navigate(AdminPlayersView.class);
        click("Import CSV");
        Details format = find(Details.class).single();
        assertThat(format.isOpened()).isTrue();
        assertThat(format.getElement().getTextRecursively())
                .contains("Players Name(AITA/AADHAR)", "Payment Amount", "Transaction Reference",
                        "Contact Number", "Download sample CSV");
        assertThat(find(Pre.class).single().getText()).isEqualTo(PlayerCsv.SAMPLE.stripTrailing());

        find(PlayerImportDialog.class).single().showFile(PlayerCsv.SAMPLE.getBytes(StandardCharsets.UTF_8));
        assertThat(format.isOpened()).isFalse();
        assertThat(find(Span.class).withText("2 of 2 rows ready to import.").exists()).isTrue();
    }
}
