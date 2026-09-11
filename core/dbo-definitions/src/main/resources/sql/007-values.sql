-- What an element must equal, and what it must contain.
--
-- `fixed` is equality: the element is that value and no other. `pattern` is
-- containment, which is what a pattern means — the element must carry what the
-- pattern states and may carry more — and jsonb containment is exactly that
-- relation, for an object, an array of codings, or a bare value alike.

-- Two entry points, one body. The name taking a document walks it and hands
-- the walk on; the name taking a walk is what `dbo.validate` calls, so a whole
-- validation walks once instead of once per check.
CREATE OR REPLACE FUNCTION dbo.value_in(walked jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT 'error', e.path, 'fixed',
         format('%s is fixed to %s and holds %s', e.path, e.fixed, at.value -> 'i')
    FROM jsonb_array_elements(walked) AS at
    JOIN state.definition_element e
      ON e.canonical = profile AND e.element_id = at.value ->> 'e' AND e.unenforceable IS NULL
   WHERE e.fixed IS NOT NULL AND (at.value -> 'i') IS DISTINCT FROM e.fixed
  UNION ALL
  SELECT 'error', e.path, 'pattern',
         format('%s must contain %s and holds %s', e.path, e.pattern, at.value -> 'i')
    FROM jsonb_array_elements(walked) AS at
    JOIN state.definition_element e
      ON e.canonical = profile AND e.element_id = at.value ->> 'e' AND e.unenforceable IS NULL
   WHERE e.pattern IS NOT NULL AND NOT ((at.value -> 'i') @> e.pattern)
$$;

CREATE OR REPLACE FUNCTION dbo.value_issues(doc jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT * FROM dbo.value_in(dbo.walked(doc, profile), profile)
$$;
