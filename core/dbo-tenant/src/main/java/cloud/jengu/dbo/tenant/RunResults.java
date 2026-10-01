package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.runner.Outcome;
import cloud.jengu.dbo.work.Run;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a step's result becomes the tenant's records.
 *
 * <p>The step said what should be written; this writes it, as the tenant,
 * through the tenant's own face — the same transaction a bundle posted to the
 * records surface is, so what validates there validates here, what is refused
 * there is refused here, and what identifies a person is sealed as it is for
 * any write. There is no second write path for work, which is the point: a
 * step's result is not a back door, it is a write the tenant made for a run.
 *
 * <p><b>Two refusals before the face is asked.</b> The step must have declared
 * that it writes the type, and the record must be the type its write names.
 * Both are about the declaration rather than the record, so they are decided
 * here, by name, and the face never sees a write the step was not entitled to
 * ask for.
 */
final class RunResults implements Lane.Results {

    private final String tenant;
    private final Map<String, Set<String>> writesByStep;
    private final FhirStoreFacade face;

    RunResults(String tenant, List<TenantSpec.Step> steps, FhirStoreFacade face) {
        this.tenant = tenant;
        Map<String, Set<String>> writes = new java.util.HashMap<>();
        steps.forEach(step -> writes.put(step.code(), step.writes()));
        this.writesByStep = Map.copyOf(writes);
        this.face = face;
    }

    @Override
    public List<String> commit(Run run, List<Outcome.Write> result) {
        String step = run.process() + "." + run.step();
        Set<String> declared = writesByStep.getOrDefault(step, Set.of());
        StringBuilder entries = new StringBuilder();
        for (int at = 0; at < result.size(); at++) {
            Outcome.Write write = result.get(at);
            if (!declared.contains(write.type())) {
                throw new Lane.Results.Refused(tenant + ": step '" + step + "' does not "
                        + "write '" + write.type() + "'" + (declared.isEmpty()
                        ? ", and declares no type it writes"
                        : "; it writes " + new java.util.TreeSet<>(declared)));
            }
            Object record;
            try {
                record = Json.parse(write.resource());
            } catch (RuntimeException notJson) {
                throw new Lane.Results.Refused(tenant + ": result[" + at + "] is not a "
                        + "record: " + notJson.getMessage(), notJson);
            }
            // The record's own type, not the one its write names: the face
            // stores what the record says it is, so a write naming one
            // declared type over a record of another would be the step
            // writing what it never declared.
            String type = record instanceof Map<?, ?> map
                    ? String.valueOf(map.get("resourceType")) : null;
            if (!write.type().equals(type)) {
                throw new Lane.Results.Refused(tenant + ": result[" + at + "] names '"
                        + write.type() + "' and carries " + (type == null ? "no record"
                        : "a '" + type + "'"));
            }
            entries.append(at == 0 ? "" : ",").append('{');
            if (write.fullUrl() != null) {
                entries.append("\"fullUrl\":").append(quoted(write.fullUrl())).append(',');
            }
            entries.append("\"resource\":").append(write.resource())
                    .append(",\"request\":{\"method\":").append(quoted(write.method()))
                    .append(",\"url\":").append(quoted(write.url()));
            if (write.ifMatch() != null) {
                entries.append(",\"ifMatch\":").append(quoted("W/\"" + write.ifMatch() + "\""));
            }
            entries.append("}}");
        }
        String answered;
        try {
            answered = face.bundle("{\"resourceType\":\"Bundle\",\"type\":\"transaction\","
                    + "\"entry\":[" + entries + "]}");
        } catch (cloud.jengu.dbo.fhir.common.ValidationFailedException invalid) {
            throw new Lane.Results.Refused(tenant + ": " + invalid.getMessage(), invalid);
        } catch (cloud.jengu.dbo.fhir.common.ValidationUnavailableException notNow) {
            // Not a verdict: validation could not be reached, and the same
            // result may well be accepted in a moment. Thrown on as a failure
            // to write, so the run is released and tried again.
            throw notNow;
        } catch (cloud.jengu.dbo.core.api.VersionConflictException
                | cloud.jengu.dbo.core.api.IdentityConflictException
                | cloud.jengu.dbo.core.api.HandlingRefusedException
                | cloud.jengu.dbo.core.api.PolicyViolationException
                | cloud.jengu.dbo.core.api.ShapeTooNewException
                | cloud.jengu.dbo.fhir.common.UnknownSearchParameterException
                | UnsupportedOperationException
                | IllegalArgumentException refused) {
            // The refusals the records surface answers with a 4xx: each is
            // about what the result says, so the same result would meet it
            // again, and the run ends with it rather than looping on it.
            throw new Lane.Results.Refused(tenant + ": " + refused.getMessage(), refused);
        }
        return versions(answered);
    }

    /** {@code Type/id/version} for each entry the face answered, in order. */
    private static List<String> versions(String transactionResponse) {
        List<String> versions = new ArrayList<>();
        Object response = Json.parse(transactionResponse);
        for (Object entry : Json.array(response, "entry")) {
            Object located = Json.objOpt(Json.objOpt(entry, "response"), "location");
            String location = located == null ? null : String.valueOf(located);
            String[] parts = location == null ? new String[0] : location.split("/");
            if (parts.length != 4 || !"_history".equals(parts[2])) {
                throw new IllegalStateException("a committed entry answered no versioned "
                        + "location: " + location);
            }
            versions.add(parts[0] + "/" + parts[1] + "/" + parts[3]);
        }
        return List.copyOf(versions);
    }

    private static String quoted(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
