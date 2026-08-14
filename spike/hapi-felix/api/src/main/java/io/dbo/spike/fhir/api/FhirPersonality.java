package io.dbo.spike.fhir.api;

import java.util.List;

/**
 * The DBO-owned personality surface. JSON in, JSON/strings out — HAPI types
 * never cross this boundary (the §7.3 rule under test).
 */
public interface FhirPersonality {
    /** e.g. "R4", "R5" */
    String fhirVersion();

    /** Parse and re-serialize a resource — proves the model round-trips. */
    String reserialize(String resourceJson);

    /** Evaluate a FHIRPath expression, return primitive results as strings. */
    List<String> evalPath(String resourceJson, String fhirPath);

    /** Validate against base profiles; returns human-readable issue lines. */
    List<String> validate(String resourceJson);

    /** Can this bundle's classloader load the given class? (isolation probe) */
    boolean canLoad(String fqcn);

    /** Loader identity of the class as seen from this bundle, or "absent" — proves separate private copies. */
    String classOrigin(String fqcn);

    /** Milliseconds spent creating the FhirContext (lazily, first use). */
    long contextInitMillis();
}
