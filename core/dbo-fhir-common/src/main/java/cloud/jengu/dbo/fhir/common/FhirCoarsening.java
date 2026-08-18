package cloud.jengu.dbo.fhir.common;

import cloud.jengu.dbo.core.face.Coarsening;

/**
 * The FHIR face's coarse forms for identifying elements (§14 §7).
 *
 * <p>Version-neutral: R4 and R5 agree about what a {@code date} is, so both
 * personalities use this one.
 */
public final class FhirCoarsening implements Coarsening {

    public static final Coarsening INSTANCE = new FhirCoarsening();

    private FhirCoarsening() {
    }

    @Override
    public Object coarsen(String typeName, String element, Object value) {
        return "birthDate".equals(element) ? year(String.valueOf(value)) : null;
    }

    /**
     * A birth date reduced to its year.
     *
     * <p>Year alone answers most of what a birth date is needed for — age for
     * dosing, screening intervals, growth expectations at any scale above the
     * neonatal — while being far too coarse to identify. Care that needs the
     * exact date asks for it and says why.
     *
     * <p>It remains a valid FHIR {@code date}: the type admits {@code YYYY},
     * {@code YYYY-MM} and {@code YYYY-MM-DD}, so the coarse form lives in the
     * standard's own type system rather than being a shape violation every
     * reader has to special-case.
     */
    private static String year(String date) {
        if (date == null || date.length() < 4) {
            return null;
        }
        String head = date.substring(0, 4);
        return head.chars().allMatch(Character::isDigit) ? head : null;
    }
}
