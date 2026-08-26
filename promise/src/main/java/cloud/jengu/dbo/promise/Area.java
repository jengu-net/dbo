package cloud.jengu.dbo.promise;

import java.util.List;

/**
 * The grouping above the four classification views: an area collects the
 * stories, features, qualities and constraints of one higher-level concern —
 * a regulation covered paragraph-by-paragraph as constraints, a product
 * theme spanning stories and qualities.
 *
 * <p>An area is not owned by any one catalogue: composition merges same-code
 * areas across products (REQ-DBO-PRM-AREAS-MERGE-BY-CODE), so one area may
 * span several catalogues' classifications. Two same-code areas with
 * conflicting titles are refused rather than one being picked.
 */
public interface Area extends Coded {

    /** The concern, as one business-readable title. */
    String title();

    /** The classifications this area groups. */
    List<Classified> covers();
}
