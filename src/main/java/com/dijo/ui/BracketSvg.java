package com.dijo.ui;

import com.dijo.events.Match;
import java.util.List;
import java.util.Locale;

/**
 * Draws a knockout bracket as SVG, in the style of a printed tournament draw:
 * round 1 lists every slot on a line, each match is joined by a bracket line,
 * and the confirmed winner moves to a line in the next column with the score
 * beneath it. The final column shows the champion. Colours come from the theme's
 * CSS properties (with currentColor as fallback), so it follows light and dark mode.
 */
final class BracketSvg {
    private static final int ROW = 40;           // vertical space per round-1 slot
    private static final int HEADER = 30;
    private static final int FIRST_COLUMN = 200;
    private static final int COLUMN = 170;
    private static final int MAX_NAME = 24;
    private static final String LINE = "stroke: var(--vaadin-text-color-secondary, currentColor)";
    private static final String SECONDARY = "fill: var(--vaadin-text-color-secondary, currentColor)";
    private static final String ACCENT = "fill: var(--aura-accent-color, currentColor)";

    private BracketSvg() {}

    /** matches: every match of one event, ordered by round and match number. */
    static String render(List<Match> matches, String highlight) {
        int rounds = matches.getFirst().totalRounds();
        int slots = 1 << rounds;
        int width = FIRST_COLUMN + COLUMN * rounds + 10;
        int height = HEADER + slots * ROW + 10;
        Match[][] byRound = new Match[rounds + 1][];
        for (int r = 1; r <= rounds; r++) byRound[r] = new Match[slots >> r];
        for (Match m : matches) byRound[m.round()][m.matchNo() - 1] = m;

        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns='http://www.w3.org/2000/svg' role='img' aria-label='Tournament draw'")
                .append(" width='").append(width).append("' height='").append(height)
                .append("' viewBox='0 0 ").append(width).append(' ').append(height)
                .append("' style='font: inherit; font-size: 13px; color: inherit; display: block'>");

        // Column 0 holds the round-1 slots; column c (1..rounds) holds round c's winners.
        double[] y = new double[slots];
        for (int i = 0; i < slots; i++) y[i] = HEADER + (i + 0.5) * ROW;
        int x = 0;
        for (int column = 0; column <= rounds; column++) {
            int w = column == 0 ? FIRST_COLUMN : COLUMN;
            header(svg, x, column == rounds ? "Champion" : byRound[column + 1][0].roundName());
            int entries = slots >> column;
            for (int k = 0; k < entries; k++) {
                String name;
                boolean bye = false;
                String score = null;
                if (column == 0) {
                    Match m = byRound[1][k / 2];
                    bye = k % 2 == 0 ? m.player1Bye() : m.player2Bye();
                    name = bye ? "BYE" : k % 2 == 0 ? m.player1() : m.player2();
                } else {
                    Match produced = byRound[column][k];
                    name = produced.confirmed() ? produced.winner() : null;
                    if (produced.confirmed()) score = produced.byeMatch() ? "bye" : produced.score();
                }
                line(svg, x, y[k], w);
                if (name != null) text(svg, x + 4, y[k] - 7, name, bye, name.equals(highlight));
                if (score != null) small(svg, x + 4, y[k] + 15, score);
                if (column == rounds && name != null) {
                    champion(svg, x + 4, y[k] + (score == null ? 18 : 34));
                }
            }
            if (column < rounds) {
                // Bracket line joining each pair, at the right edge of the column.
                double[] next = new double[entries / 2];
                for (int k = 0; k < entries; k += 2) {
                    svg.append(String.format(Locale.ROOT,
                            "<line x1='%d' y1='%.1f' x2='%d' y2='%.1f' style='%s' stroke-width='1'/>",
                            x + w, y[k], x + w, y[k + 1], LINE));
                    next[k / 2] = (y[k] + y[k + 1]) / 2;
                }
                y = next;
            }
            x += w;
        }
        return svg.append("</svg>").toString();
    }

    private static void header(StringBuilder svg, int x, String label) {
        svg.append("<text x='").append(x + 4).append("' y='18' font-size='11' font-weight='600'")
                .append(" letter-spacing='0.06em' style='").append(SECONDARY).append("'>")
                .append(escape(label.toUpperCase(Locale.ROOT))).append("</text>");
    }

    private static void line(StringBuilder svg, int x, double y, int w) {
        svg.append(String.format(Locale.ROOT,
                "<line x1='%d' y1='%.1f' x2='%d' y2='%.1f' style='%s' stroke-width='1'/>",
                x, y, x + w, y, LINE));
    }

    private static void text(StringBuilder svg, int x, double y, String value, boolean muted, boolean bold) {
        String style = muted ? " font-style='italic' style='" + SECONDARY + "'"
                : bold ? " font-weight='700' style='" + ACCENT + "'" : " fill='currentColor'";
        svg.append(String.format(Locale.ROOT, "<text x='%d' y='%.1f'%s>", x, y, style))
                .append(escape(shorten(value))).append("</text>");
    }

    private static void small(StringBuilder svg, int x, double y, String value) {
        svg.append(String.format(Locale.ROOT,
                        "<text x='%d' y='%.1f' font-size='12' style='%s'>", x, y, SECONDARY))
                .append(escape(shorten(value))).append("</text>");
    }

    private static void champion(StringBuilder svg, int x, double y) {
        svg.append(String.format(Locale.ROOT,
                "<text x='%d' y='%.1f' font-size='11' font-weight='700' letter-spacing='0.06em' style='%s'>CHAMPION</text>",
                x, y, ACCENT));
    }

    private static String shorten(String value) {
        return value.length() <= MAX_NAME ? value : value.substring(0, MAX_NAME - 1) + "…";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("'", "&apos;").replace("\"", "&quot;");
    }
}
