package com.hrportal.cv;

/**
 * Exact EMU (English Metric Unit) positions/sizes pulled directly from
 * approved_cv_template.pptx's real experience-block tables. 914400 EMU = 1 inch.
 *
 * Row Y-position is independent of block type (a FULL block or a HALF pair
 * can each land in either row slot) — X/width/height come from the master
 * shape's own geometry, but TOP is always overridden to whichever row
 * (1 or 2) the layout engine assigned it to.
 */
public final class CvGeometry {
    private CvGeometry() {}

    public static final long ROW_1_TOP = 1_464_302; // from slide 2's full-width block (row 1 in the sample)
    public static final long ROW_2_TOP = 4_182_412; // from slide 2's half-width blocks (row 2 in the sample)

    public static final long FULL_LEFT = 331_949, FULL_WIDTH = 11_297_783, FULL_HEIGHT = 2_422_571;
    public static final long HALF_LEFT_LEFT = 331_949, HALF_LEFT_WIDTH = 5_400_000, HALF_LEFT_HEIGHT = 2_452_431;
    public static final long HALF_RIGHT_LEFT = 6_270_235, HALF_RIGHT_WIDTH = 5_472_000, HALF_RIGHT_HEIGHT = 2_447_999;

    public static long rowTop(int pageIndex, int rowIndexOnPage) {
        if (pageIndex == 0) {
            return ROW_2_TOP; // Page 1 has a large header, only space for 1 row at the bottom
        }
        return rowIndexOnPage == 0 ? ROW_1_TOP : ROW_2_TOP;
    }
}
