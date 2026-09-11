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
CREATE OR REPLACE FUNCTION dbo.invariant_holds(instance jsonb, path text)
RETURNS boolean LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
  RETURN jsonb_path_match(instance, path::jsonpath, '{}'::jsonb, true);
EXCEPTION WHEN others THEN
  RETURN NULL;
END;
$$;

-- Every rule that is broken, where it is broken.
--
-- `jsonb_path_match` rather than `jsonb_path_exists`: a path whose whole body
-- is a predicate YIELDS a boolean either way, so `exists` answers true for a
-- rule that evaluates false. Built on that, every invariant would pass and
-- nothing would ever say otherwise.
--
-- Severity is the rule's own. A warning is advice — it names what a reader
-- should look at and refuses nothing — exactly as a binding weaker than
-- required already is.
CREATE OR REPLACE FUNCTION dbo.invariant_issues(doc jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT coalesce(rule.severity, 'error'), element.path, rule.key,
         format('%s: %s', rule.key, coalesce(rule.expression, rule.key))
    FROM dbo.instances(doc, profile) i
    JOIN state.definition_invariant rule
      ON rule.canonical = profile AND rule.element_id = i.element_id
     AND rule.path IS NOT NULL
    JOIN state.definition_element element
      ON element.canonical = profile AND element.element_id = i.element_id
   WHERE dbo.invariant_holds(i.instance, rule.path) IS FALSE
$$;
