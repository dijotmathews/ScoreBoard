package com.dijo.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.dijo.events.Match;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A four-player bracket: ada beat bob (confirmed), cy has a BYE, the final is open. */
class BracketSvgTest {
    private static final List<Match> MATCHES = List.of(
            new Match("e", "Singles", 2, 1, 1, "ada", "ada", false, "bob", "bob", false, null, "ada", "ada", "21-15", "ref", "admin", null, null),
            new Match("e", "Singles", 2, 1, 2, "cy", "cy", false, null, null, true, null, "cy", "cy", null, null, null, null, null),
            new Match("e", "Singles", 2, 2, 1, "ada", "ada", false, null, null, false, null, null, null, null, null, null, null, null));

    @Test
    void drawsEveryPlayerTheByeAndRoundHeaders() {
        String svg = BracketSvg.render(MATCHES, null);

        assertThat(svg).startsWith("<svg").endsWith("</svg>");
        assertThat(svg).contains(">ada<", ">bob<", ">cy<", ">BYE<", "SEMI-FINAL", "FINAL", "CHAMPION");
    }

    @Test
    void showsConfirmedWinnersWithTheirScore() {
        String svg = BracketSvg.render(MATCHES, null);
        assertThat(svg).contains(">21-15<");
    }

    @Test
    void highlightsTheGivenPlayer() {
        String svg = BracketSvg.render(MATCHES, "ada");
        assertThat(svg).contains("font-weight='700' style='fill: var(--aura-accent-color, currentColor)'>ada<");
    }

    @Test
    void showsFullNamesAndHighlightsByUsername() {
        List<Match> named = List.of(new Match("e", "E", 1, 1, 1, "dijo", "Dijo T Mathews", false,
                "bob", "bob", false, null, null, null, null, null, null, null, null));
        String svg = BracketSvg.render(named, "dijo");
        assertThat(svg).contains("font-weight='700' style='fill: var(--aura-accent-color, currentColor)'>Dijo T Mathews<")
                .doesNotContain(">dijo<");
    }

    @Test
    void followsTheThemeOnThePage() {
        String svg = BracketSvg.render(MATCHES, null);
        assertThat(svg).contains("var(--vaadin-text-color-secondary").doesNotContain("#ffffff");
    }

    @Test
    void shareableImageHasATitleFixedColoursAndNotes() {
        String svg = BracketSvg.image(MATCHES, "Singles", m -> m.round() == 2 ? "Court 3 · 4:00 PM" : null);

        assertThat(svg).contains("fill='#ffffff'", ">Singles<", ">Court 3 · 4:00 PM<");
        assertThat(svg).doesNotContain("var(--");
    }

    @Test
    void notesAreNotShownForConfirmedMatches() {
        String svg = BracketSvg.image(MATCHES, "Singles", m -> "Court 9");
        // Only the open final and the unconfirmed BYE match carry the note.
        assertThat(svg.split(">Court 9<", -1)).hasSize(3);
    }

    @Test
    void aMatchBetweenTwoByesSendsAByeIntoTheNextColumn() {
        List<Match> byes = List.of(
                new Match("e", "E", 2, 1, 1, "ada", "ada", false, "bob", "bob", false, null, null, null, null, null, null, null, null),
                new Match("e", "E", 2, 1, 2, null, null, true, null, null, true, null, null, null, null, null, null, null, null),
                new Match("e", "E", 2, 2, 1, null, null, false, null, null, true, null, null, null, null, null, null, null, null));

        // Two BYEs in round 1, and the one they send into the final.
        assertThat(BracketSvg.render(byes, null).split(">BYE<", -1)).hasSize(4);
    }

    @Test
    void escapesNames() {
        List<Match> tricky = List.of(
                new Match("e", "A&B", 1, 1, 1, "<o'neil>", "<o'neil>", false, "bob", "bob", false, null, null, null, null, null, null, null, null));
        String svg = BracketSvg.image(tricky, "Tom & Jerry", m -> null);

        assertThat(svg).contains("&lt;o&apos;neil&gt;", "Tom &amp; Jerry").doesNotContain("<o'neil>");
    }

    @Test
    void shortensLongNames() {
        List<Match> longName = List.of(
                new Match("e", "E", 1, 1, 1, "abcdefghijklmnopqrstuvwxyz", "abcdefghijklmnopqrstuvwxyz", false, "bob", "bob", false,
                        null, null, null, null, null, null, null, null));
        assertThat(BracketSvg.render(longName, null)).contains(">abcdefghijklmnopqrstuvw…<");
    }
}
