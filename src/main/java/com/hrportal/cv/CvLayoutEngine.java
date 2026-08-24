package com.hrportal.cv;

import java.util.ArrayList;
import java.util.List;

/**
 * Packs a sorted list of experiences into pages of at most 2 rows each,
 * where each row is either:
 *   - one FULL-width block (long scope), or
 *   - two HALF-width blocks side by side (short scope)
 *
 * Confirmed against the real approved_cv_template.pptx sample: every
 * half-width scope observed was <= 163 chars, the one full-width scope
 * was 211 chars. SCOPE_LENGTH_THRESHOLD is a starting point from that
 * single data point — recalibrate once more real CVs go through this.
 */
public class CvLayoutEngine {

    public static final int SCOPE_LENGTH_THRESHOLD = 180;
    private static final int ROWS_PER_PAGE = 2;

    public enum RowType { FULL, PAIR, SOLO }

    public static class Row {
        public final RowType type;
        public final ExperienceBlock left;   // used for FULL/SOLO (the only block) and PAIR (left half)
        public final ExperienceBlock right;  // used for PAIR only, null for FULL/SOLO

        Row(RowType type, ExperienceBlock left, ExperienceBlock right) {
            this.type = type;
            this.left = left;
            this.right = right;
        }
    }

    public static class Page {
        public final List<Row> rows = new ArrayList<>();
    }

    /** Simple carrier for the 7 tokenized fields of one experience. */
    public static class ExperienceBlock {
        public final String organization, country, industry, scope, year, role, duration;

        public ExperienceBlock(String organization, String country, String industry, String scope,
                                String year, String role, String duration) {
            this.organization = organization;
            this.country = country;
            this.industry = industry;
            this.scope = scope == null ? "" : scope;
            this.year = year;
            this.role = role;
            this.duration = duration;
        }

        boolean isLong() {
            return scope.length() > SCOPE_LENGTH_THRESHOLD;
        }
    }

    /**
     * Greedy row-building: a long-scope block always takes a full row alone.
     * Two consecutive short-scope blocks pair into one row. A short-scope
     * block with no short partner available (next is long, or it's the
     * last item) becomes a SOLO row — rendered alone in the HALF-LEFT slot
     * with the right half left empty, NOT promoted to a full-width block.
     * Confirmed against a real 10-experience ground-truth CV
     * (Abdel_Rahman_Ibrahim_CV_Prototype_v1.pptx) where the trailing
     * orphaned "KIMA" experience renders exactly this way on its own page.
     *
     * Page-packing: verified against two real samples now — the original
     * 9-experience file (3 pages) and this 10-experience one (4 pages,
     * adding "TMG AP"/2023 into the mix). A FULL row is NEVER placed as a
     * page's 2nd row — if one would land there, it forces a page break and
     * becomes row 1 of a fresh page instead. SOLO rows are NOT subject to
     * that restriction (no evidence either way, but they behave like a
     * PAIR for packing purposes since nothing suggested otherwise). This
     * rule reproduces both real samples' exact page splits.
     */
    public List<Page> layout(List<ExperienceBlock> experiences) {
        List<Row> rows = buildRows(experiences);

        List<Page> pages = new ArrayList<>();
        Page current = new Page();
        for (Row row : rows) {
            boolean wouldBeSecondRow = current.rows.size() == 1;
            if (row.type == RowType.FULL && wouldBeSecondRow) {
                pages.add(current);
                current = new Page();
            }
            current.rows.add(row);
            int limit = pages.isEmpty() ? 1 : ROWS_PER_PAGE;
            if (current.rows.size() == limit) {
                pages.add(current);
                current = new Page();
            }
        }
        if (!current.rows.isEmpty()) {
            pages.add(current);
        }
        if (pages.isEmpty()) {
            pages.add(new Page()); // employee with zero experiences still gets a page-1 header slide
        }
        return pages;
    }

    private List<Row> buildRows(List<ExperienceBlock> experiences) {
        List<Row> rows = new ArrayList<>();
        int i = 0;
        while (i < experiences.size()) {
            ExperienceBlock current = experiences.get(i);
            if (current.isLong()) {
                rows.add(new Row(RowType.FULL, current, null));
                i++;
                continue;
            }
            if (i + 1 < experiences.size() && !experiences.get(i + 1).isLong()) {
                rows.add(new Row(RowType.PAIR, current, experiences.get(i + 1)));
                i += 2;
            } else {
                rows.add(new Row(RowType.SOLO, current, null));
                i++;
            }
        }
        return rows;
    }
}
