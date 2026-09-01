package cloud.jengu.dbo.fhir.element;

import org.hl7.fhir.r5.model.SearchParameter;

import java.util.List;

/**
 * What a type may actually be searched by here — this version's own
 * parameters together with whatever the tenant has authored.
 *
 * <p>It exists because those two sets have different owners. The version's
 * are the same for every tenant on the deployment and are known before any
 * store is opened; a tenant's are records in that tenant's database and can
 * change while it is serving. A face may not hold a store, so it cannot
 * answer this on its own, and the search compiler and the capability
 * statement must not be given only half the answer — one that refuses what
 * the other advertises is the dishonesty
 * REQ-DBO-SRCH-HONEST-CAPABILITY exists to forbid.
 *
 * <p>So both are handed the same view, and the tenant-scoped facade is what
 * supplies it.
 */
@FunctionalInterface
interface ParametersInForce {

    List<SearchParameter> forType(String typeName);

    /** A version's own set and nothing else — every caller with no tenant behind it. */
    static ParametersInForce of(ElementVersion version) {
        return version::parametersFor;
    }
}
