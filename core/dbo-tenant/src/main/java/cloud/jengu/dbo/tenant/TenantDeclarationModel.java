package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.work.WorkModel;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * What this deployment has been told to serve, as records.
 *
 * <p>A tenant's declaration was a file in a directory and nothing else, so the
 * only way to ask what a deployment is declared to serve was to read that
 * directory — which nobody outside the node can do, and which says nothing
 * about when a declaration arrived or what it said before. A zone's
 * declarations have been records for as long as there have been zones; this is
 * the same sentence about tenants.
 *
 * <p><b>The payload is the spec, verbatim.</b> Not a projection of it: the
 * declaration is what somebody wrote, and a second representation would be a
 * second thing to be wrong. It is parsed on the way out, by the same parser
 * that reads the file.
 *
 * <p>Registered for every tenant, like the lane's own bookkeeping, because
 * which tenant is the managing one is a deployment's decision made after its
 * tenants are built — and a type registered for the wrong one is a surface
 * that answers nothing.
 */
public final class TenantDeclarationModel {

    public static final String TYPE = "TenantDeclaration";

    /** A declaration is identified by the tenant it declares. */
    public static final String CODE_SYSTEM = "urn:dbo:tenant";

    private TenantDeclarationModel() {
    }

    public static List<TypeRegistration> registrations() {
        return List.of(new TypeRegistration(TYPE, WorkModel.DOMAIN, IdentityClass.IDENTIFIER,
                Set.of(CODE_SYSTEM),
                // Projected from wherever the declarations are kept, and
                // owned by the lane that applies them: nobody's tenant users
                // may author what this deployment serves.
                Handling.projectedConfig(), extractor(), List.of()));
    }

    /**
     * Read with the spec parser rather than a second reader of the same bytes.
     * A declaration that will not parse is refused here, which is where the
     * pass turns it into a card naming the file.
     */
    private static EnvelopeExtractor extractor() {
        return (type, payload) -> {
            TenantSpec spec = TenantSpec.parse(new String(payload, StandardCharsets.UTF_8));
            Envelope envelope = new Envelope();
            envelope.identifier(CODE_SYSTEM, spec.code());
            envelope.value("code", EnvelopeValue.of(spec.code()));
            envelope.value("face", EnvelopeValue.of(spec.face()));
            if (spec.zone() != null) {
                envelope.value("zone", EnvelopeValue.of(spec.zone()));
            }
            return envelope;
        };
    }

    public static Identifier of(String tenantCode) {
        return new Identifier(CODE_SYSTEM, tenantCode);
    }
}
