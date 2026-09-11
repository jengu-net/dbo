-- The rules an element carries, run against the instances of it.
--
-- Compiled when the definition arrived, so what happens here is execution and
-- nothing else: no expression is parsed, no rule is interpreted, and the path
-- was settled once for every document that will ever be checked against it.

-- Whether one rule holds of one instance: true, false, or NULL for cannot say.
--
-- Three answers, as everywhere else in this checker. A path can fail to answer
-- — comparing a string to a number, reaching into something that is not there
-- in the shape it expected — and the honest report of that is silence rather
-- than a refusal. A document is not wrong because a rule could not be run
-- against it.
--
-- Wrapped so that nothing here can fail a write. A stored path that will not
-- parse is this store's own defect, and the way to find it is a rule that
-- stops holding rather than a tenant that stops writing.

-- Two entry points, one body. The name taking a document walks it and hands
-- the walk on; the name taking a walk is what `dbo.validate` calls, so a whole
-- validation walks once instead of once per check.
-- Whether one rule holds of one instance: true, false, or NULL for cannot say.
--
-- Three answers, as everywhere else in this checker. A path can fail to answer
-- — comparing a string to a number, reaching into something that is not there
-- in the shape it expected — and the honest report of that is silence rather
-- than a refusal. A document is not wrong because a rule could not be run
-- against it.
--
-- Wrapped so that nothing here can fail a write. A stored path that will not
-- parse is this store's own defect, and the way to find it is a rule that
-- stops holding rather than a tenant that stops writing.
CREATE OR REPLACE FUNCTION dbo.invariant_holds(instance jsonb, path text)
RETURNS boolean LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
  RETURN jsonb_path_match(instance, path::jsonpath, '{}'::jsonb, true);
EXCEPTION WHEN others THEN
  RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION dbo.invariant_in(walked jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT coalesce(rule.severity, 'error'), element.path, rule.key,
         format('%s: %s', rule.key, coalesce(rule.expression, rule.key))
    FROM jsonb_array_elements(walked) AS at
    JOIN state.definition_invariant rule
      ON rule.canonical = profile AND rule.element_id = at.value ->> 'e'
     AND rule.path IS NOT NULL
    JOIN state.definition_element element
      ON element.canonical = profile AND element.element_id = at.value ->> 'e'
   WHERE dbo.invariant_holds(at.value -> 'i', rule.path) IS FALSE
$$;

CREATE OR REPLACE FUNCTION dbo.invariant_issues(doc jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT * FROM dbo.invariant_in(dbo.walked(doc, profile), profile)
$$;
