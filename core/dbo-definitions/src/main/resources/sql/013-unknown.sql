-- An element the profile does not declare, found where it sits.
--
-- The other checks all ask "is what is here allowed"; this asks the question
-- from the other side — "is what is here anything the profile knows about" —
-- and it is the one the walk cannot answer by itself. `dbo.instances` descends
-- from the root by each element's steps, so a key nothing names is simply
-- never reached: no pair exists for it, every check sees a clean document, and
-- a typo like `sistem` inside an identifier is stored as though somebody meant
-- it.
--
-- The toolchain refuses these, from the PARSER rather than the validator, and
-- until this existed that refusal was the one thing a tenant lost by declaring
-- `verdict: database`.
--
-- WHERE IT IS SILENT, and this is the load-bearing part. A datatype's insides
-- are only in the rows where a profile constrains them, so an element with no
-- children rows has nothing to compare against — and comparing anyway would
-- refuse every key of every unconstrained Identifier, Coding and HumanName in
-- the corpus. So an instance whose element has no children is passed over
-- entirely. The check speaks where the expansion went and nowhere else, which
-- is the same reach every check here has
-- (REQ-DBO-VAL-UNRESOLVABLE-IS-NOT-INVALID, in its shape for keys).
CREATE OR REPLACE FUNCTION dbo.unknown_in(walked jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  WITH pairs AS (
      SELECT parent.value ->> 'e' AS element_id, parent.value -> 'i' AS instance
        FROM jsonb_array_elements(walked) AS parent
       WHERE jsonb_typeof(parent.value -> 'i') = 'object'
  ),
  -- What this element's children lay claim to: the first key of each step.
  -- A choice contributes one per type, a slice's predicate rides on the same
  -- leading key, and an element with no steps claims nothing.
  claimed AS (
      SELECT p.element_id,
             substring(step from '^\$\."([^"]+)"') AS named
        FROM pairs p
        JOIN definitions.definition_element c
          ON c.canonical = profile AND c.parent_id = p.element_id
       CROSS JOIN LATERAL unnest(c.steps) AS step
  ),
  -- Only elements the expansion actually went inside. An element with no
  -- children rows is not a document with no children; it is a place the rows
  -- do not describe.
  described AS (
      SELECT DISTINCT element_id FROM claimed WHERE named IS NOT NULL
  )
  SELECT 'error',
         p.element_id || '.' || present.key,
         'unknown',
         format('the element %L is not declared by %s, and nothing here can search, '
                || 'validate or convert it — FHIR carries what a resource does not '
                || 'define in %L',
                present.key, p.element_id, 'extension')
    FROM pairs p
    JOIN described d ON d.element_id = p.element_id
   CROSS JOIN LATERAL jsonb_object_keys(p.instance) AS present(key)
   WHERE NOT EXISTS (
           SELECT 1 FROM claimed c
            WHERE c.element_id = p.element_id
              AND (c.named = present.key
                   -- A primitive's extensions ride beside it under an
                   -- underscore, and are the value's own business rather than
                   -- an element of the parent.
                   OR c.named = ltrim(present.key, '_'))
         )
     -- What every resource carries and no element declares.
     AND present.key NOT IN ('resourceType', 'fhir_comments')
$$;

-- The same, for a caller holding a document rather than a walk.
CREATE OR REPLACE FUNCTION dbo.unknown_issues(doc jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT * FROM dbo.unknown_in(dbo.walked(doc, profile), profile)
$$;
