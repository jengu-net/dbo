package bench;

import org.hl7.fhir.convertors.factory.VersionConvertorFactory_40_50;
import org.hl7.fhir.r4.formats.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Builds the comparison cohort: a fixed, banded draw of patients from the
 * rowling population, converted R4 to R5 once, offline, and written out as
 * files every server is fed byte-identically.
 *
 * <p>Offline and once, because a converter in the driver measures the
 * converter. Banded, because a bundle spans 29 to 117,444 resources and a
 * benchmark whose unit varies four-thousandfold is measuring its own draw.
 * Ordered by id rather than randomly, because a cohort that is re-drawn per
 * run cannot show drift.
 */
public final class CohortBuilder {

    public static void main(String[] args) throws Exception {
        String url = arg(args, "--url", "jdbc:postgresql://192.168.1.16:5432/rowling");
        String user = arg(args, "--user", "postgres");
        String password = arg(args, "--password", System.getenv("PGPASSWORD"));
        int count = Integer.parseInt(arg(args, "--count", "50"));
        int min = Integer.parseInt(arg(args, "--min", "900"));
        int max = Integer.parseInt(arg(args, "--max", "1100"));
        Path out = Path.of(arg(args, "--out", "cohort"));

        Files.createDirectories(out);
        StringBuilder manifest = new StringBuilder(
                "# rowling cohort: band " + min + "-" + max + " resources, "
                        + count + " patients, ordered by id\n"
                        + "# id\tresources\tr5Bytes\tsha256\n");
        List<String> failures = new ArrayList<>();
        long totalResources = 0;
        long totalBytes = 0;

        try (Connection c = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, resource_count, bundle_gz FROM patient "
                             + "WHERE resource_count BETWEEN ? AND ? ORDER BY id LIMIT ?")) {
            ps.setInt(1, min);
            ps.setInt(2, max);
            ps.setInt(3, count);
            try (ResultSet rs = ps.executeQuery()) {
                int n = 0;
                while (rs.next()) {
                    String id = rs.getString("id");
                    int resources = rs.getInt("resource_count");
                    byte[] gz = rs.getBytes("bundle_gz");
                    try {
                        byte[] r4;
                        try (GZIPInputStream in = new GZIPInputStream(
                                new java.io.ByteArrayInputStream(gz))) {
                            r4 = in.readAllBytes();
                        }
                        org.hl7.fhir.r4.model.Resource parsed =
                                (org.hl7.fhir.r4.model.Resource) new JsonParser().parse(r4);
                        org.hl7.fhir.r5.model.Resource converted =
                                VersionConvertorFactory_40_50.convertResource(parsed);
                        byte[] r5 = new org.hl7.fhir.r5.formats.JsonParser()
                                .composeBytes(converted);
                        Files.write(out.resolve(id + ".json"), r5);
                        manifest.append(id).append('\t').append(resources).append('\t')
                                .append(r5.length).append('\t').append(sha256(r5)).append('\n');
                        totalResources += resources;
                        totalBytes += r5.length;
                        n++;
                        if (n % 10 == 0) {
                            System.out.println("  " + n + "/" + count);
                        }
                    } catch (Exception e) {
                        // Recorded, never skipped silently: a cohort that
                        // shrinks without saying so is how a comparison starts
                        // flattering somebody.
                        failures.add(id + ": " + e);
                    }
                }
            }
        }

        Files.writeString(out.resolve("MANIFEST.tsv"), manifest.toString(),
                StandardCharsets.UTF_8);
        if (!failures.isEmpty()) {
            Files.writeString(out.resolve("FAILURES.txt"), String.join("\n", failures));
        }
        System.out.println("cohort: " + (count - failures.size()) + " bundles, "
                + totalResources + " resources, "
                + (totalBytes / 1_048_576) + " MB of R5 JSON");
        System.out.println("conversion failures: " + failures.size());
        failures.stream().limit(5).forEach(f -> System.out.println("  " + f));
    }

    private static String sha256(byte[] b) throws Exception {
        StringBuilder sb = new StringBuilder(64);
        for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
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
