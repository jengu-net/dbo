-- A required binding, answered from the terminology this tenant holds.
--
-- Per element instance rather than per coding: a CodeableConcept satisfies a
-- binding when ANY of its codings is in the value set, so the codings are
-- judged together and the element is refused only when every code that could
-- be judged was, and none of them belonged.
--
-- Where nothing could judge — the value set is not held, or none of the
-- systems it is built from are — there is no finding at all. Unresolvable is
-- not invalid, and a store that refused what it merely does not hold would
-- refuse a tenant's own vocabulary.
--
-- Required only. A weaker binding is advice, and advice belongs where a person
-- reads it rather than in a refusal
-- (REQ-DBO-VAL-BINDING-STRENGTH-IS-THE-ANSWER).

-- Two entry points, one body. The name taking a document walks it and hands
-- the walk on; the name taking a walk is what `dbo.validate` calls, so a whole
-- validation walks once instead of once per check.
CREATE OR REPLACE FUNCTION dbo.binding_in(walked jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT 'error', e.path, 'binding',
         format('%s is bound to %s, and %s is not in it', e.path,
                split_part(e.binding_valueset, '|', 1), verdict.shown)
    FROM jsonb_array_elements(walked) AS at
    JOIN definitions.definition_element e
      ON e.canonical = profile AND e.element_id = at.value ->> 'e' AND e.unenforceable IS NULL
     AND e.binding_strength = 'required' AND e.binding_valueset IS NOT NULL
   CROSS JOIN LATERAL (
       SELECT bool_or(judged.member) AS any_member,
              count(judged.member)   AS judged,
              string_agg(coalesce(judged.system || '#', '') || judged.code, ', ') AS shown
         FROM (SELECT cv.system, cv.code,
                      dbo.in_value_set(e.binding_valueset, cv.system, cv.code) AS member
                 FROM dbo.coded_values(at.value -> 'i') cv) judged
   ) verdict
   WHERE verdict.judged > 0 AND coalesce(verdict.any_member, false) = false
$$;

CREATE OR REPLACE FUNCTION dbo.binding_issues(doc jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT * FROM dbo.binding_in(dbo.walked(doc, profile), profile)
$$;
