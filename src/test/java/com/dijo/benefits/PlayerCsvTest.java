package com.dijo.benefits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dijo.benefits.PlayerCsv.Row;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PlayerCsvTest {
    static final String HEADER = "Timestamp,Email Address,Players Name(AITA/AADHAR),"
            + "Date of Birth (DD/MM/YYYY),Birth Certificate (PDF/JPEG),Events,Payment Amount,"
            + "Transaction Reference\n";

    @Test
    void readsOnlyNameAmountAndReference() {
        List<Row> rows = PlayerCsv.parse(HEADER
                + "10/11/2026 9:00:00,ada@example.com,Ada Li,01/02/2012,https://drive/x,"
                + "\"U12 Singles, U12 Doubles\",500,UPI123\n");
        assertThat(rows).containsExactly(new Row(2, "Ada Li", 50000L, "UPI123", null));
    }

    @Test
    void acceptsTabSeparatedFilesWithQuotesAndBlankLines() {
        String tsv = HEADER.replace(',', '\t')
                + "t\te\t\"Dijo \"\"DJ\"\" Mathews\"\td\tb\tev\t₹1,000\tREF-9\r\n\r\n"
                + "t\te\tBo\td\tb\tev\t\t\n";
        assertThat(PlayerCsv.parse(tsv)).containsExactly(
                new Row(2, "Dijo \"DJ\" Mathews", 100000L, "REF-9", null),
                new Row(4, "Bo", null, null, null));
    }

    @Test
    void flagsRowsWithoutANameOrWithABadAmount() {
        List<Row> rows = PlayerCsv.parse(HEADER + "t,e,,d,b,ev,500,R1\nt,e,Ada,d,b,ev,five,R2\n");
        assertThat(rows).extracting(Row::problem).containsExactly(
                "Player name is missing", "Payment amount \"five\" is not a number");
    }

    @Test
    void thePlayerIsThePlayersNameColumnEvenWithOtherNameColumns() {
        List<Row> rows = PlayerCsv.parse("Parent Name,Players Name(AITA/AADHAR),Payment Amount,"
                + "Transaction Reference\nRavi Li,Ada Li,500,UPI1\n");
        assertThat(rows).extracting(Row::name).containsExactly("Ada Li");
    }

    @Test
    void requiresTheRelevantColumns() {
        assertThatThrownBy(() -> PlayerCsv.parse("Name,Payment Amount,Transaction Reference\n"))
                .hasMessageContaining("Players Name(AITA/AADHAR)");
        assertThatThrownBy(() -> PlayerCsv.parse("Timestamp,Players Name,Payment Amount\n"))
                .hasMessageContaining("Transaction Reference");
        assertThatThrownBy(() -> PlayerCsv.parse("")).hasMessage("The file is empty");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"500|50000", "Rs. 500.50|50050", "INR 1,200|120000", "₹ 75|7500"})
    void parsesAmounts(String amount, long paise) {
        assertThat(PlayerCsv.paise(amount)).isEqualTo(paise);
    }
}
