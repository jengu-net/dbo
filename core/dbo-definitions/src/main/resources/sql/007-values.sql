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
    JOIN definitions.definition_element e
      ON e.canonical = profile AND e.element_id = at.value ->> 'e' AND e.unenforceable IS NULL
   WHERE e.fixed IS NOT NULL AND (at.value -> 'i') IS DISTINCT FROM e.fixed
  UNION ALL
  SELECT 'error', e.path, 'pattern',
         format('%s must contain %s and holds %s', e.path, e.pattern, at.value -> 'i')
    FROM jsonb_array_elements(walked) AS at
    JOIN definitions.definition_element e
      ON e.canonical = profile AND e.element_id = at.value ->> 'e' AND e.unenforceable IS NULL
   WHERE e.pattern IS NOT NULL AND NOT ((at.value -> 'i') @> e.pattern)
$$;

CREATE OR REPLACE FUNCTION dbo.value_issues(doc jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT * FROM dbo.value_in(dbo.walked(doc, profile), profile)
$$;

-- What a primitive value must LOOK like.
--
-- The checks above answer what an element must equal or contain, which is a
-- question about a value that is already the right kind of thing. Nothing
-- asked whether it was: a date that is not a date and a string standing where
-- a boolean belongs both passed, and were refused only by the toolchain — two
-- of the three clinical divergences measured against a face.
--
-- Conservative by construction: an element is flagged only when NO type it
-- declares can admit what is there. A choice element keeps its freedom that
-- way — `deceased[x]` admits a boolean or a dateTime, so a string is judged
-- against dateTime alone, because that is the only one of the two a string
-- could be. And a type this does not recognise admits everything, so a
-- complex type, a Reference or a profile's own datatype is never refused here
-- by a check that does not understand it.
CREATE OR REPLACE FUNCTION dbo.admits(code text, kind text, v jsonb)
RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE code
    WHEN 'boolean' THEN kind = 'boolean'
    WHEN 'integer' THEN kind = 'number' AND (v #>> '{}') ~ '^-?[0-9]+$'
    WHEN 'positiveInt' THEN kind = 'number' AND (v #>> '{}') ~ '^[1-9][0-9]*$'
    WHEN 'unsignedInt' THEN kind = 'number' AND (v #>> '{}') ~ '^[0-9]+$'
    WHEN 'integer64' THEN kind = 'number' AND (v #>> '{}') ~ '^-?[0-9]+$'
    WHEN 'decimal' THEN kind = 'number'
    -- The specification's own lexical forms, which is why they are this
    -- shape: a year may not be 0000, and a month or a day is present with
    -- everything above it or not at all.
    WHEN 'date' THEN kind = 'string' AND (v #>> '{}') ~
      '^([0-9]([0-9]([0-9][1-9]|[1-9]0)|[1-9]00)|[1-9]000)(-(0[1-9]|1[0-2])(-(0[1-9]|[1-2][0-9]|3[0-1]))?)?$'
    WHEN 'dateTime' THEN kind = 'string' AND (v #>> '{}') ~
      '^([0-9]([0-9]([0-9][1-9]|[1-9]0)|[1-9]00)|[1-9]000)(-(0[1-9]|1[0-2])(-(0[1-9]|[1-2][0-9]|3[0-1])(T([01][0-9]|2[0-3]):[0-5][0-9]:([0-5][0-9]|60)(\.[0-9]+)?(Z|[+-]((0[0-9]|1[0-3]):[0-5][0-9]|14:00)))?)?)?$'
    WHEN 'instant' THEN kind = 'string' AND (v #>> '{}') ~
      '^([0-9]([0-9]([0-9][1-9]|[1-9]0)|[1-9]00)|[1-9]000)-(0[1-9]|1[0-2])-(0[1-9]|[1-2][0-9]|3[0-1])T([01][0-9]|2[0-3]):[0-5][0-9]:([0-5][0-9]|60)(\.[0-9]+)?(Z|[+-]((0[0-9]|1[0-3]):[0-5][0-9]|14:00))$'
    WHEN 'time' THEN kind = 'string' AND (v #>> '{}') ~
      '^([01][0-9]|2[0-3]):[0-5][0-9]:([0-5][0-9]|60)(\.[0-9]+)?$'
    WHEN 'id' THEN kind = 'string' AND (v #>> '{}') ~ '^[A-Za-z0-9\-\.]{1,64}$'
    WHEN 'code' THEN kind = 'string' AND (v #>> '{}') ~ '^[^\s]+( [^\s]+)*$'
    WHEN 'oid' THEN kind = 'string' AND (v #>> '{}') ~ '^urn:oid:[0-2](\.(0|[1-9][0-9]*))+$'
    -- Lowercase, which the specification's own regex does not say and the
    -- toolchain enforces anyway. It was one of three rules a validator carries
    -- in its own code and no definition states, measured as the gap between
    -- what the database answers and what the toolchain does.
    WHEN 'uuid' THEN kind = 'string' AND (v #>> '{}') ~
      '^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    WHEN 'string' THEN kind = 'string'
    WHEN 'markdown' THEN kind = 'string'
    WHEN 'uri' THEN kind = 'string'
    WHEN 'url' THEN kind = 'string'
    -- Absolute, for the same reason: a canonical names a resource by the url
    -- it is published under, and one without a scheme names it nowhere. The
    -- second of the three, and the one that accounted for eighteen findings by
    -- itself. A version suffix and a fragment ride after the scheme and are
    -- none of this check's business.
    WHEN 'canonical' THEN kind = 'string'
      AND (v #>> '{}') ~ '^[A-Za-z][A-Za-z0-9+.\-]*:'
    WHEN 'base64Binary' THEN kind = 'string'
    WHEN 'xhtml' THEN kind = 'string'
    -- Not a primitive this knows: it admits whatever is there, so nothing is
    -- refused by a rule that was never written for it.
    ELSE true
  END
$$;

CREATE OR REPLACE FUNCTION dbo.primitive_in(walked jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  WITH held AS (
    SELECT e.path, e.types, at.value -> 'i' AS v
      FROM jsonb_array_elements(walked) AS at
      JOIN definitions.definition_element e
        ON e.canonical = profile AND e.element_id = at.value ->> 'e'
       AND e.unenforceable IS NULL
     WHERE e.types IS NOT NULL AND jsonb_array_length(e.types) > 0
  )
  SELECT 'error', h.path, 'primitive',
         format('%s holds %s, which is not a %s', h.path, h.v,
                (SELECT string_agg(t ->> 'code', ' or ')
                   FROM jsonb_array_elements(h.types) AS t))
    FROM held h
   WHERE jsonb_typeof(h.v) NOT IN ('null', 'object', 'array')
     AND NOT EXISTS (
           SELECT 1 FROM jsonb_array_elements(h.types) AS t
            WHERE dbo.admits(t ->> 'code', jsonb_typeof(h.v), h.v))
$$;

-- The third of the rules a validator carries in its own code: an identifier
-- whose system says "this value is a uri" must hold one.
--
-- urn:ietf:rfc:3986 is the RFC that defines a URI, and naming it as an
-- identifier's system is how FHIR says the value IS a uri rather than a number
-- somebody assigns. No StructureDefinition states it, so it is written here
-- beside the two in `dbo.admits` rather than expanded from anything.
--
-- Keyed on the system alone. Other elements carry a `system` — a contact
-- point's is phone or email — and none of them can carry this one, so nothing
-- needs to know it is looking at an Identifier.
CREATE OR REPLACE FUNCTION dbo.identifier_in(walked jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT 'error', e.path, 'identifier',
         format('%s names urn:ietf:rfc:3986 as its system, so its value is a uri and %s is not',
                e.path, at.value -> 'i' ->> 'value')
    FROM jsonb_array_elements(walked) AS at
    JOIN definitions.definition_element e
      ON e.canonical = profile AND e.element_id = at.value ->> 'e' AND e.unenforceable IS NULL
   WHERE jsonb_typeof(at.value -> 'i') = 'object'
     AND at.value -> 'i' ->> 'system' = 'urn:ietf:rfc:3986'
     AND at.value -> 'i' ->> 'value' IS NOT NULL
     AND (at.value -> 'i' ->> 'value') !~ '^[A-Za-z][A-Za-z0-9+.\-]*:'
$$;

CREATE OR REPLACE FUNCTION dbo.identifier_issues(doc jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT * FROM dbo.identifier_in(dbo.walked(doc, profile), profile)
$$;

CREATE OR REPLACE FUNCTION dbo.primitive_issues(doc jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT * FROM dbo.primitive_in(dbo.walked(doc, profile), profile)
$$;
