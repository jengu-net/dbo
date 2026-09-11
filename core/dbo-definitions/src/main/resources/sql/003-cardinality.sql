-- How often an element may occur, checked where it occurs.
--
-- INSIDE ITS PARENT, which is the whole reason the rows are shaped as they
-- are. `Patient.contact.name` may occur once, and once PER CONTACT: a patient
-- with three contacts holds three names and is correct, while one contact
-- holding two names is not. A path from the document root cannot tell those
-- apart, so this walks parents to instances and counts each element within the
-- instance it belongs to.
--
-- An element nothing can locate is skipped rather than counted. A row with no
-- steps would count zero for everything and refuse every document that has
-- what it asks for (REQ-DBO-VAL-UNRESOLVABLE-IS-NOT-INVALID, in its shape for
-- structures).
CREATE OR REPLACE FUNCTION dbo.cardinality_issues(doc jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  WITH RECURSIVE walk AS (
      -- the resource itself: one instance, and it is the document
      SELECT e.element_id, doc AS instance
        FROM state.definition_element e
       WHERE e.canonical = profile AND e.parent_id IS NULL
    UNION ALL
      SELECT c.element_id, found.value
        FROM walk w
        JOIN state.definition_element c
          ON c.canonical = profile AND c.parent_id = w.element_id
       CROSS JOIN LATERAL jsonb_array_elements(dbo.located(w.instance, c.steps)) AS found(value)
  )
  SELECT 'error', c.path, 'cardinality',
         format('%s occurs %s times here; the profile allows %s..%s',
                c.path, present.n, c.min_occurs, coalesce(c.max_occurs::text, '*'))
    FROM walk w
    JOIN state.definition_element c
      ON c.canonical = profile AND c.parent_id = w.element_id AND c.unenforceable IS NULL
   CROSS JOIN LATERAL (SELECT jsonb_array_length(dbo.located(w.instance, c.steps))) AS present(n)
   WHERE present.n < c.min_occurs
      OR (c.max_occurs IS NOT NULL AND present.n > c.max_occurs)
$$;
