-- What an element must equal, and what it must contain.
--
-- `fixed` is equality: the element is that value and no other. `pattern` is
-- containment, which is what a pattern means — the element must carry what the
-- pattern states and may carry more — and jsonb containment is exactly that
-- relation, for an object, an array of codings, or a bare value alike.
CREATE OR REPLACE FUNCTION dbo.value_issues(doc jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT 'error', e.path, 'fixed',
         format('%s is fixed to %s and holds %s', e.path, e.fixed, i.instance)
    FROM dbo.instances(doc, profile) i
    JOIN state.definition_element e
      ON e.canonical = profile AND e.element_id = i.element_id AND e.unenforceable IS NULL
   WHERE e.fixed IS NOT NULL AND i.instance IS DISTINCT FROM e.fixed
  UNION ALL
  SELECT 'error', e.path, 'pattern',
         format('%s must contain %s and holds %s', e.path, e.pattern, i.instance)
    FROM dbo.instances(doc, profile) i
    JOIN state.definition_element e
      ON e.canonical = profile AND e.element_id = i.element_id AND e.unenforceable IS NULL
   WHERE e.pattern IS NOT NULL AND NOT (i.instance @> e.pattern)
$$;
