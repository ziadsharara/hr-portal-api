package com.hrportal.cv;

import com.hrportal.model.Employee;
import com.hrportal.model.Experience;
import org.apache.poi.xslf.usermodel.*;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Generates one employee's CV as a .pptx from the tokenized master template
 * (src/main/resources/templates/cv_template_master.pptx), preserving the
 * approved design exactly — every font/color/position in that file is
 * untouched; only the {{TOKEN}} text runs get replaced.
 *
 * ARCHITECTURE NOTE (rewritten 2026-08-21, replacing an earlier
 * separate-empty-presentation approach): the earlier version created a
 * brand-new, separate XMLSlideShow and copied shapes into it via
 * importContent(). That copies shape XML but NOT the slide layout/master/
 * theme chain those shapes depend on for background colors — confirmed by
 * inspecting the generated output's raw XML: text/borders were present
 * with correct values but rendered white-on-white (schemeClr "lt1" / srgbClr
 * FFFFFF) because the colored background the design sits on was never
 * carried over. It also broke the logo image's media relationship for the
 * same cross-package reason.
 *
 * Fix: never leave the template's package. Work directly on the loaded
 * template XMLSlideShow as the output document:
 *   - Page 1 is the template's own slide 0, edited in place — no copying.
 *   - Pages 2+ are created via ppt.createSlide(sameLayoutAsSlide1) +
 *     importContent(), which — because it stays within the SAME package —
 *     correctly inherits the layout/master/theme (and therefore the
 *     background colors and the logo image relationship).
 *   - The original "page N frame" stencil slide (template slide 1) is used
 *     only as a content/layout source and is removed from the final output
 *     once all real pages have been created from it.
 *
 * Template structure:
 *   slide 0 = "page 1 frame"  — header info + pagination label, no experience blocks
 *   slide 1 = "page N frame + block masters" — pagination label + the 3
 *             reusable experience-block table shapes (FULL, HALF_LEFT, HALF_RIGHT)
 */
@Service
public class CvGeneratorService {

    private static final String TEMPLATE_PATH = "templates/cv_template_master.pptx";
    private static final DateTimeFormatter YEAR_FMT = DateTimeFormatter.ofPattern("yyyy");

    private final CvLayoutEngine layoutEngine = new CvLayoutEngine();

    public byte[] generate(Employee employee, List<Experience> experiencesSortedDesc) {
        try (InputStream templateStream = new ClassPathResource(TEMPLATE_PATH).getInputStream();
             XMLSlideShow ppt = new XMLSlideShow(templateStream)) {

            XSLFSlide page1Frame = ppt.getSlides().get(0);
            XSLFSlide pageNStencil = ppt.getSlides().get(1);
            XSLFSlideLayout pageNLayout = pageNStencil.getSlideLayout();

            // Capture the 3 block masters as independent XML copies BEFORE
            // any slide removal, so cloning rows later never depends on the
            // stencil slide still being present in the presentation.
            XmlObject blockFullXml = null, blockLeftXml = null, blockRightXml = null;
            int fullRows = 0, fullCols = 0, leftRows = 0, leftCols = 0, rightRows = 0, rightCols = 0;
            for (XSLFShape shp : pageNStencil.getShapes()) {
                if (shp instanceof XSLFTable table) {
                    long left = Math.round(table.getAnchor().getX() * 12700.0);
                    if (Math.abs(left - CvGeometry.FULL_LEFT) < 5000 && table.getAnchor().getWidth() * 12700.0 > 10_000_000) {
                        blockFullXml = table.getXmlObject().copy();
                        fullRows = table.getNumberOfRows();
                        fullCols = table.getNumberOfColumns();
                    } else if (Math.abs(left - CvGeometry.HALF_LEFT_LEFT) < 5000) {
                        blockLeftXml = table.getXmlObject().copy();
                        leftRows = table.getNumberOfRows();
                        leftCols = table.getNumberOfColumns();
                    } else if (Math.abs(left - CvGeometry.HALF_RIGHT_LEFT) < 5000) {
                        blockRightXml = table.getXmlObject().copy();
                        rightRows = table.getNumberOfRows();
                        rightCols = table.getNumberOfColumns();
                    }
                }
            }
            if (blockFullXml == null || blockLeftXml == null || blockRightXml == null) {
                throw new IllegalStateException("Could not locate all 3 experience block masters in the template — " +
                        "check cv_template_master.pptx wasn't modified in a way that changed shape positions.");
            }

            List<CvLayoutEngine.ExperienceBlock> blocks = experiencesSortedDesc.stream()
                    .map(this::toBlock)
                    .toList();
            List<CvLayoutEngine.Page> pages = layoutEngine.layout(blocks);
            int totalPages = pages.size();

            // --- PAGE 1: edit the template's own slide in place, zero copying ---
            // Slide 1 now carries a visible HALF_LEFT+HALF_RIGHT placeholder
            // pair (added 2026-08-21 for template-design completeness, so
            // the .pptx looks right when opened directly in PowerPoint —
            // matches how slide 2 already shows its own block masters).
            // Those are template-only reference shapes and must be removed
            // before real per-employee rows are cloned in, exactly like
            // removeBlockMasterShapes() already does for pages 2+ below —
            // otherwise the placeholder tokens ({{ORG}} etc.) would survive
            // untouched (replaceTokensOnSlide only replaces HEADER tokens)
            // and real cloned rows would land on top of them.
            removeBlockMasterShapes(page1Frame);
            replaceTokensOnSlide(page1Frame, headerTokens(employee, 1, totalPages));
            addRowsToSlide(page1Frame, pages.get(0), true,
                    blockFullXml, fullRows, fullCols, blockLeftXml, leftRows, leftCols, blockRightXml, rightRows, rightCols);

            // --- PAGES 2..N: same-package duplicate of the stencil's layout+content ---
            for (int pageIndex = 1; pageIndex < pages.size(); pageIndex++) {
                XSLFSlide newSlide = ppt.createSlide(pageNLayout);
                newSlide.importContent(pageNStencil);
                removeBlockMasterShapes(newSlide); // strip the 3 tokenized masters importContent just copied in

                replaceTokensOnSlide(newSlide, headerTokens(employee, pageIndex + 1, totalPages));
                addRowsToSlide(newSlide, pages.get(pageIndex), false,
                        blockFullXml, fullRows, fullCols, blockLeftXml, leftRows, leftCols, blockRightXml, rightRows, rightCols);
            }

            // Remove the stencil now — it was only ever a content source for
            // pages 2+ and should not appear as an actual output page. Safe
            // to remove now since every block master was already captured
            // as an independent XML copy above, and every page-N slide
            // already has its own imported (not referenced) content.
            ppt.removeSlide(ppt.getSlides().indexOf(pageNStencil));

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ppt.write(baos);
            return baos.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException("Failed to generate CV for employee " + employee.getCompanyCode(), e);
        }
    }

    // ---------------------------------------------------------------

    private void addRowsToSlide(XSLFSlide slide, CvLayoutEngine.Page page, boolean isPage1,
                                 XmlObject blockFullXml, int fullRows, int fullCols,
                                 XmlObject blockLeftXml, int leftRows, int leftCols,
                                 XmlObject blockRightXml, int rightRows, int rightCols) {
        for (int rowIndex = 0; rowIndex < page.rows.size(); rowIndex++) {
            CvLayoutEngine.Row row = page.rows.get(rowIndex);
            long top = CvGeometry.rowTop(isPage1 ? 0 : 1, rowIndex);

            if (row.type == CvLayoutEngine.RowType.FULL) {
                addBlock(slide, blockFullXml.copy(), fullRows, fullCols, row.left,
                        CvGeometry.FULL_LEFT, top, CvGeometry.FULL_WIDTH, CvGeometry.FULL_HEIGHT);
            } else if (row.type == CvLayoutEngine.RowType.SOLO) {
                // Orphaned short-scope experience, no pairing partner —
                // confirmed against real ground truth (the trailing "KIMA"
                // entry in Abdel_Rahman_Ibrahim_CV_Prototype_v1.pptx):
                // renders alone in the HALF-LEFT slot, right half stays
                // empty. NOT promoted to full-width.
                addBlock(slide, blockLeftXml.copy(), leftRows, leftCols, row.left,
                        CvGeometry.HALF_LEFT_LEFT, top, CvGeometry.HALF_LEFT_WIDTH, CvGeometry.HALF_LEFT_HEIGHT);
            } else {
                addBlock(slide, blockLeftXml.copy(), leftRows, leftCols, row.left,
                        CvGeometry.HALF_LEFT_LEFT, top, CvGeometry.HALF_LEFT_WIDTH, CvGeometry.HALF_LEFT_HEIGHT);
                addBlock(slide, blockRightXml.copy(), rightRows, rightCols, row.right,
                        CvGeometry.HALF_RIGHT_LEFT, top, CvGeometry.HALF_RIGHT_WIDTH, CvGeometry.HALF_RIGHT_HEIGHT);
            }
        }
    }

    private void removeBlockMasterShapes(XSLFSlide slide) {
        List<XSLFShape> toRemove = slide.getShapes().stream()
                .filter(s -> s instanceof XSLFTable)
                // block master tables are identified by their row-0 label,
                // which is never tokenized (only the value column is)
                .filter(s -> "Organization".equals(((XSLFTable) s).getCell(0, 0).getText()))
                .toList();
        for (XSLFShape s : toRemove) {
            slide.removeShape(s);
        }
    }

    /** Clones one experience block from a captured master XML copy, positions it, fills its 7 tokens. */
    private void addBlock(XSLFSlide slide, XmlObject blockXmlCopy, int rows, int cols, CvLayoutEngine.ExperienceBlock data,
                           long left, long top, long width, long height) {
        XSLFTable clone = slide.createTable(rows, cols);
        // Copy the master's row heights / column widths / cell formatting by
        // importing its XML directly rather than rebuilding cell-by-cell —
        // simplest reliable way to keep styling identical.
        clone.getXmlObject().set(blockXmlCopy);
        clone.setAnchor(new java.awt.geom.Rectangle2D.Double(
                left / 12700.0, top / 12700.0, width / 12700.0, height / 12700.0));

        Map<String, String> values = Map.of(
                "{{ORG}}", nullToEmpty(data.organization),
                "{{COUNTRY}}", nullToEmpty(data.country),
                "{{INDUSTRY}}", nullToEmpty(data.industry),
                "{{SCOPE}}", nullToEmpty(data.scope),
                "{{YEAR}}", nullToEmpty(data.year),
                "{{ROLE}}", nullToEmpty(data.role),
                "{{DURATION}}", nullToEmpty(data.duration)
        );

        // After the .set() swap above, clone.getRows()/.getCell()/etc
        // return stale wrapper objects — edits through them never reach
        // the serialized XML (confirmed via testing). Fix: walk
        // clone.getXmlObject() — the actual live XML tree, post-.set() —
        // directly with an XmlCursor instead.
        replaceTokensInRawXml(clone.getXmlObject(), values);
    }

    private void replaceTokensInRawXml(XmlObject xml, Map<String, String> values) {
        try (XmlCursor cursor = xml.newCursor()) {
            XmlCursor.TokenType tt = cursor.toNextToken();
            while (tt != XmlCursor.TokenType.ENDDOC) {
                if (tt == XmlCursor.TokenType.TEXT) {
                    String text = cursor.getChars();
                    if (text != null && !text.isEmpty()) {
                        String replaced = text;
                        for (Map.Entry<String, String> e : values.entrySet()) {
                            replaced = replaced.replace(e.getKey(), e.getValue());
                        }
                        if (!replaced.equals(text)) {
                            cursor.removeChars(text.length());
                            cursor.insertChars(replaced);
                        }
                    }
                }
                tt = cursor.toNextToken();
            }
        }
    }

    private CvLayoutEngine.ExperienceBlock toBlock(Experience x) {
        String year = x.getStartDate() != null ? x.getStartDate().format(YEAR_FMT) : "";
        return new CvLayoutEngine.ExperienceBlock(
                x.getProject(), x.getCountry(), x.getIndustry(), x.getScope(),
                year, x.getRole(), x.getDuration());
    }

    private Map<String, String> headerTokens(Employee e, int page, int totalPages) {
        String age = "";
        if (e.getDateOfBirth() != null) {
            age = Period.between(e.getDateOfBirth(), LocalDate.now()).getYears() + " Years";
        }
        return Map.ofEntries(
                Map.entry("{{NAME}}", nullToDash(e.getName())),
                Map.entry("{{CV_TITLE}}", nullToDash(e.getCvTitle())),
                Map.entry("{{POSITION}}", nullToDash(e.getPosition())),
                Map.entry("{{NATIONALITY}}", nullToDash(e.getNationality())),
                Map.entry("{{LANGUAGES}}", nullToDash(e.getLanguages())),
                Map.entry("{{AGE}}", age),
                Map.entry("{{EDUCATION}}", nullToDash(e.getEducation())),
                Map.entry("{{EXPERIENCE_YEARS}}", nullToDash(e.getExperienceYears())),
                Map.entry("{{CERTIFICATION}}", nullToDash(e.getCertificates())),
                Map.entry("{{PAGE}}", String.valueOf(page)),
                Map.entry("{{TOTAL_PAGES}}", String.valueOf(totalPages))
        );
    }

    private void replaceTokensOnSlide(XSLFSlide slide, Map<String, String> values) {
        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTextShape textShape) {
                replaceTokensInTextShape(textShape, values);
            } else if (shape instanceof XSLFTable table) {
                replaceTokensInTable(table, values);
            }
        }
    }

    private void replaceTokensInTable(XSLFTable table, Map<String, String> values) {
        for (XSLFTableRow row : table.getRows()) {
            for (XSLFTableCell cell : row.getCells()) {
                replaceTokensInTextShape(cell, values);
            }
        }
    }

    private void replaceTokensInTextShape(XSLFTextShape shape, Map<String, String> values) {
        for (XSLFTextParagraph para : shape.getTextParagraphs()) {
            for (XSLFTextRun run : para.getTextRuns()) {
                String text = run.getRawText();
                if (text == null) continue;
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    if (text.contains(entry.getKey())) {
                        text = text.replace(entry.getKey(), entry.getValue());
                    }
                }
                run.setText(text);
            }
        }
    }

    private String nullToDash(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }

    /**
     * Confirmed against real ground truth (Abdel_Rahman_Ibrahim_CV_Prototype_v1.pptx):
     * the "TMG AP" experience has genuinely blank Country/Industry/Duration
     * cells, not a dash placeholder — so experience-block fields render
     * empty when missing. Header fields still use nullToDash (no evidence
     * either way there, kept as the safer default).
     */
    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
