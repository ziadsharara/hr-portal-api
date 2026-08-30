package com.hrportal.cv;

import com.hrportal.model.Employee;
import com.hrportal.model.Experience;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for a real PowerPoint-corruption bug: every clone made via
 * XmlObject.set() (CvGeneratorService.addBlock()) and every shape pulled in
 * via importContent() (page-2+ frames) carried its source shape's original
 * <p:cNvPr id="..."> forward unchanged, so a multi-page, multi-block CV
 * ended up with duplicate shape ids — including on the same slide.
 * LibreOffice and python-pptx both open such files without complaint;
 * PowerPoint's stricter validator rejects the whole file as corrupt. Since
 * this environment has no PowerPoint or Open XML SDK strict validator
 * available, this test is the cheap, CI-runnable substitute: it doesn't
 * prove PowerPoint will accept the file, only that the specific defect
 * (duplicate <p:cNvPr id>) that caused the real-world failure is absent.
 * Real PowerPoint verification still needs to happen out of band.
 *
 * Uses the exact 10-experience data set that CvLayoutEngine's page-packing
 * rules were calibrated against (Abdelrahman Ibrahim /
 * Abdel_Rahman_Ibrahim_CV_Prototype_v1.pptx, recovered from
 * ~/.local/share/Trash/files since it isn't checked into the repo).
 *
 * Output path is controlled by the CV_OUTPUT_PATH env var (defaults to
 * /tmp/generated_cv.pptx) so it can be pointed at a mounted volume when run
 * inside a container, for manual inspection alongside the assertion.
 */
class CvGeneratorManualIT {

    @Test
    void generateRealMultiPageCv_hasNoDuplicateShapeIds() throws IOException {
        Employee employee = Employee.builder()
                .companyCode(1001)
                .name("Abdelrahman Ibrahim")
                .email("abdelrahman.ibrahim@example.com")
                .status("ACTIVE")
                .company("CIC")
                .cvTitle("FICO - SAC Financial Planning")
                .position("SAP FICO Senior Consultant")
                .nationality("Egyptian")
                .languages("English / Arabic")
                .dateOfBirth(LocalDate.now().minusYears(29))
                .education("Bachelor of Business Administration, Arab Academy for Science Technology & Maritime Transport, 2019 .")
                .experienceYears("6 Years")
                .certificates("C_TS4FI_1909-SAP Certified Application Associate- SAP S/4HANA for Financial Accounting Associates")
                .build();

        List<Experience> experiences = List.of(
                exp("Saudi Emar", "KSA", "Construction",
                        "FICO-MM-SD-PS-CPM-CIC's SAP/Primavira integration Solution-SESMI-SD-PP-E-invoice-Flett Management-Integration withGL System one way(SAP-Lagancy DataOcean)-WRECF",
                        2025, "FICO - Consultant", "6 months"),
                exp("ELSEWEDY", "Egypt", "Electrical Equipment , Infrastructure & Industrial Manufacturing  Sectors",
                        " FICO,MM,SD,PS,DMS,PM,PP,QM,Group Reporting ,Sales Cloud,Basis,Project Management  ,SESMI , Estimation Solution, Primavera Integration Solution",
                        2026, "SAP FICO Consultant", "8 months"),
                exp("TMG AP", "", "",
                        "AP",
                        2023, "FICO Consultant", ""),
                exp("BMW", "Egypt", "Automotive Group",
                        "Group reporting",
                        2023, "SAP FICO Senior Consultant", "4 months"),
                exp("EDSCO", "KSA", "Construction",
                        "FICO-Commercial Project Management -PS -SAC Reporting-TRM (LC & LG)-FM -MM- SD- HCM- DMS- EPC: Integration between SAP S/4HANA & Primavera-Integration between SAP S/4HANA & Time Machines -SESMI-E-Invoicing-PM",
                        2024, "SAP FICO Consultant", "8 months"),
                exp("MCI", "Egypt", "Chemicals",
                        "FICO, HCM, MM, SD, PM, PP, QM, OPENTEXT and BOBJ",
                        2021, "SAP FICO Consultant", "5 months"),
                exp("New Giza", "Egypt", "Real Estate",
                        "FICO, PS, MM, RE, OPENTEXT, HCM, EPC, ECC and PPM",
                        2020, "SAP FICO Consultant", "10 months"),
                exp("TMG SAC", "Egypt", "Real Estate",
                        "SAC",
                        2022, "SAC Financial Planning", "12 months"),
                exp("SEMADCO", "Egypt", "Chemicals",
                        "FICO, HCM, MM, SD, PM, PP, QM, OPENTEXT and BOBJ",
                        2021, "SAP FICO Consultant", "5 months"),
                exp("KIMA", "Egypt", "Manufacturing",
                        "FICO, CM, MM, SD, PM, OPENTEXT, HCM, QM, BI and PP",
                        2020, "SAP FICO  Consultant", "9 months")
        );

        CvGeneratorService service = new CvGeneratorService();
        byte[] pptx = service.generate(employee, experiences);

        String outPath = System.getenv().getOrDefault("CV_OUTPUT_PATH", "/tmp/generated_cv.pptx");
        Files.write(Path.of(outPath), pptx);
        System.out.println("WROTE_CV_BYTES=" + pptx.length + " TO=" + outPath);

        assertNoDuplicateShapeIds(pptx);
    }

    /**
     * Parses every ppt/slides/slideN.xml entry in the .pptx and fails if any
     * <p:cNvPr id="..."> value repeats on the SAME slide — the concrete
     * violation PowerPoint's strict validator rejected. Also reports (via
     * the failure message) any id that repeats across DIFFERENT slides,
     * since this generator's fix assigns ids from one global counter and a
     * cross-slide repeat would mean the counter regressed, even though
     * same-slide-only uniqueness is the strictly necessary condition.
     */
    private static void assertNoDuplicateShapeIds(byte[] pptx) throws IOException {
        Pattern idPattern = Pattern.compile("<p:cNvPr id=\"(\\d+)\"");
        Map<String, List<String>> idToSlides = new HashMap<>();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(pptx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().matches("ppt/slides/slide\\d+\\.xml")) continue;
                String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                Matcher m = idPattern.matcher(xml);
                while (m.find()) {
                    String id = m.group(1);
                    if ("1".equals(id)) continue; // root spTree group shape id — expected on every slide
                    idToSlides.computeIfAbsent(id, k -> new ArrayList<>()).add(entry.getName());
                }
            }
        }

        List<String> sameSlideDuplicates = new ArrayList<>();
        List<String> crossSlideDuplicates = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : idToSlides.entrySet()) {
            List<String> slides = e.getValue();
            if (slides.size() < 2) continue;
            boolean sameSlide = slides.size() != slides.stream().distinct().count();
            (sameSlide ? sameSlideDuplicates : crossSlideDuplicates)
                    .add("id=" + e.getKey() + " -> " + slides);
        }

        assertTrue(sameSlideDuplicates.isEmpty(),
                "Found shape id(s) duplicated on the SAME slide — this is exactly what made real " +
                        "PowerPoint reject the file as corrupt: " + sameSlideDuplicates);
        assertTrue(crossSlideDuplicates.isEmpty(),
                "Found shape id(s) duplicated across different slides — not the strict failure " +
                        "condition, but indicates the global id counter regressed: " + crossSlideDuplicates);
    }

    private static Experience exp(String org, String country, String industry, String scope,
                                   int year, String role, String duration) {
        return Experience.builder()
                .project(org)
                .country(country)
                .industry(industry)
                .scope(scope)
                .startDate(LocalDate.of(year, 6, 15))
                .role(role)
                .duration(duration)
                .build();
    }
}
