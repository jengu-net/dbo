-- How often an element may occur, checked where it occurs.
--
-- INSIDE ITS PARENT, which is the whole reason the rows are shaped as they
-- are. `Patient.contact.name` may occur once, and once PER CONTACT: a patient
-- with three contacts holds three names and is correct, while one contact
-- holding two names is not. A path from the document root cannot tell those
-- apart, so this counts each element within the instance it belongs to.
--
-- An element nothing can locate is skipped rather than counted. A row with no
-- steps would count zero for everything and refuse every document that has
-- what it asks for (REQ-DBO-VAL-UNRESOLVABLE-IS-NOT-INVALID, in its shape for
-- structures).

-- Two entry points, one body. The name taking a document walks it and hands
-- the walk on; the name taking a walk is what `dbo.validate` calls, so a whole
-- validation walks once instead of once per check.
CREATE OR REPLACE FUNCTION dbo.cardinality_in(walked jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT 'error', c.path, 'cardinality',
         format('%s occurs %s times here; the profile allows %s..%s',
                c.path, present.n, c.min_occurs, coalesce(c.max_occurs::text, '*'))
    FROM jsonb_array_elements(walked) AS parent
    JOIN definitions.definition_element c
      ON c.canonical = profile AND c.parent_id = parent.value ->> 'e'
     AND c.unenforceable IS NULL
   CROSS JOIN LATERAL (SELECT jsonb_array_length(dbo.located(parent.value -> 'i', c.steps)))
     AS present(n)
   WHERE present.n < c.min_occurs
      OR (c.max_occurs IS NOT NULL AND present.n > c.max_occurs)
$$;

CREATE OR REPLACE FUNCTION dbo.cardinality_issues(doc jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT * FROM dbo.cardinality_in(dbo.walked(doc, profile), profile)
$$;
