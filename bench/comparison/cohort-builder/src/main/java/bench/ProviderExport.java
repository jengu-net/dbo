package bench;

import org.hl7.fhir.convertors.factory.VersionConvertorFactory_40_50;
import org.hl7.fhir.r4.formats.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * The provider directory, converted to R5 once and written as one transaction
 * bundle per chunk.
 *
 * <p>It has to exist in a server BEFORE the patient cohort lands, because
 * Synthea references organisations and practitioners with conditional
 * references — {@code Organization?identifier=…} — which are resolved at write
 * time against what is already stored. Loading it is setup, not measurement:
 * a real integration loads its directory before it loads patients, and every
 * server here gets the same directory from the same bytes.
 */
public final class ProviderExport {

    public static void main(String[] args) throws Exception {
        String url = arg(args, "--url", "jdbc:postgresql://localhost:5432/rowling");
        String user = arg(args, "--user", "postgres");
        String password = arg(args, "--password", System.getenv("PGPASSWORD"));
        Path out = Path.of(arg(args, "--out", "providers"));
        int perBundle = Integer.parseInt(arg(args, "--per-bundle", "500"));
        Files.createDirectories(out);

        StringBuilder bundle = new StringBuilder();
        int inBundle = 0;
        int file = 0;
        int total = 0;
        int failed = 0;

        try (Connection c = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, type, resource::text FROM provider ORDER BY type, id")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString("type");
                    String json = rs.getString(3);
                    String r5;
                    try {
                        org.hl7.fhir.r4.model.Resource parsed = (org.hl7.fhir.r4.model.Resource)
                                new JsonParser().parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        r5 = new String(new org.hl7.fhir.r5.formats.JsonParser()
                                .composeBytes(VersionConvertorFactory_40_50.convertResource(parsed)),
                                java.nio.charset.StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        failed++;
                        continue;
                    }
                    if (inBundle > 0) {
                        bundle.append(',');
                    }
                    // PUT under the resource's own id, so the directory is the
                    // same resources at the same addresses in all three
                    // servers -- and so loading it twice is not two copies.
                    bundle.append("{\"fullUrl\":\"urn:uuid:").append(rs.getString("id"))
                            .append("\",\"resource\":").append(r5)
                            .append(",\"request\":{\"method\":\"PUT\",\"url\":\"")
                            .append(type).append('/').append(rs.getString("id")).append("\"}}");
                    inBundle++;
                    total++;
                    if (inBundle == perBundle) {
                        write(out, file++, bundle);
                        bundle.setLength(0);
                        inBundle = 0;
                    }
                }
            }
        }
        if (inBundle > 0) {
            write(out, file++, bundle);
        }
        System.out.println("providers: " + total + " resources in " + file
                + " bundles, " + failed + " conversion failures");
    }

    private static void write(Path out, int index, StringBuilder entries) throws Exception {
        Files.writeString(out.resolve(String.format("providers-%03d.json", index)),
                "{\"resourceType\":\"Bundle\",\"type\":\"transaction\",\"entry\":["
                        + entries + "]}");
    }

    private static String arg(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
