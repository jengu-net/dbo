-- Every element of a profile, paired with each instance of it this document
-- holds.
--
-- The walk every check shares, done once: from the resource down, a parent's
-- instances locate its children's, so each check reads (element, instance)
-- pairs and never re-derives where anything is.
--
-- The parameter names are part of what is shipped. Postgres refuses to rename
-- the parameters of a function it already has, so a release that renamed one
-- would fail to install on every tenant brought up by the release before it.
CREATE OR REPLACE FUNCTION dbo.instances(doc jsonb, profile text)
RETURNS TABLE (element_id text, instance jsonb)
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
  SELECT walk.element_id, walk.instance FROM walk
$$;
