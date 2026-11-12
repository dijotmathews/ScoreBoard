package com.dijo.benefits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dijo.DatabaseTest;
import com.dijo.benefits.PlayerImportService.Outcome;
import com.dijo.benefits.PlayerImportService.Result;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

class PlayerImportServiceTest extends DatabaseTest {
    @Autowired
    PlayerImportService importer;

    @Autowired
    PlayerBenefitsService benefits;

    @BeforeEach
    void setUp() {
        account("admin", "ADMIN", "USER");
        signIn("admin", "ADMIN", "USER");
        benefits.updateSettings(50000, 2, "Refreshment");
    }

    private List<Result> importCsv(String body) {
        return importer.importPlayers(PlayerCsv.parse(PlayerCsvTest.HEADER + body));
    }

    @Test
    void createsAndSignsUpPlayersWhosePaymentMatchesTheFee() {
        List<Result> results = importCsv("t,e,Ada Li,d,b,ev,500,UPI1\nt,e,Bo Chen,d,b,ev,,\n");

        assertThat(results).extracting(Result::outcome, Result::username)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(Outcome.SIGNED_UP, "adali"),
                        org.assertj.core.groups.Tuple.tuple(Outcome.CREATED, "bochen"));
        assertThat(benefits.playerBenefits("adali").paymentReference()).isEqualTo("UPI1");
        assertThat(benefits.playerBenefits("adali").coupons()).hasSize(2);
        assertThat(count("SELECT count(*) FROM entry_payments WHERE player_username = 'bochen'"))
                .isZero();
    }

    @Test
    void recordsTheAmountFromTheFileEvenWhenItDiffersFromTheFee() {
        Result result = importCsv("t,e,Ada Li,d,b,ev,700,UPI1\n").getFirst();
        assertThat(result.outcome()).isEqualTo(Outcome.SIGNED_UP);
        assertThat(benefits.playerBenefits("adali").amountPaise()).isEqualTo(70000);
        assertThat(benefits.playerBenefits("adali").coupons()).hasSize(2);
    }

    @Test
    void recordingAPaymentByHandStillRequiresTheFee() {
        importCsv("t,e,Ada Li,d,b,ev,,\n");
        assertThatThrownBy(() -> benefits.confirmPayment("adali", "UPI1", 70000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsWithoutPaymentWhenNoEntryFeeIsSet() {
        jdbc.update("DELETE FROM benefit_settings");
        Result result = importCsv("t,e,Ada Li,d,b,ev,700,UPI1\n").getFirst();
        assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(count("SELECT count(*) FROM entry_payments")).isZero();
    }

    @Test
    void importingTheSameFileAgainSkipsEveryRow() {
        String csv = "t,e,Ada Li,d,b,ev,500,UPI1\nt,e,Bo Chen,d,b,ev,,\n";
        importCsv(csv);
        assertThat(importCsv(csv)).extracting(Result::outcome)
                .containsOnly(Outcome.SKIPPED);
        assertThat(count("SELECT count(*) FROM users")).isEqualTo(3);
    }

    @Test
    void reportsInvalidRowsAndCarriesOn() {
        List<Result> results = importCsv("t,e,,d,b,ev,500,UPI1\nt,e,R2-D2,d,b,ev,,\nt,e,Ada,d,b,ev,500,UPI3\n");
        assertThat(results).extracting(Result::outcome)
                .containsExactly(Outcome.FAILED, Outcome.FAILED, Outcome.SIGNED_UP);
    }

    @Test
    void onlyAdminsCanImport() {
        signIn("ref", "OFFICIAL");
        assertThatThrownBy(() -> importer.importPlayers(List.of())).isInstanceOf(AccessDeniedException.class);
    }
}
