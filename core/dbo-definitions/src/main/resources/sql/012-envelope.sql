-- The envelope, built where the bytes are.
--
-- A version publishes about thirty ways of asking after each type, and every
-- one has been an expression evaluated over an object tree on every write.
-- The parameters are compiled to jsonpath when they arrive, so what is left is
-- to run them here — over the document already in hand, in the statement that
-- stores it.
--
-- What the envelope holds is a typed rule per kind rather than the expression
-- that found the value. A token is three questions and not one; a string is
-- asked case-insensitively and, with :exact, case-sensitively; a date is a
-- span written at whatever precision its author had and is indexed by the
-- moment that span opens. Those rules are the contract this has to keep, and
-- they are kept here exactly as they were kept in Java — byte for byte, which
-- a test over everything the version publishes holds them to.

-- One FHIR date, dateTime or instant, at the moment its span opens.
--
-- A date is written at the precision the author had: 2020, 2020-01, a full
-- day, a full instant. Each names a span, and reading one as a moment is how
-- 2020 becomes the first of January — so the lower bound is taken on purpose
-- and the key is fixed-width UTC, which makes lexicographic order
-- chronological.
CREATE OR REPLACE FUNCTION dbo.date_key(p_value text)
RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE
    WHEN p_value IS NULL THEN NULL
    WHEN p_value ~ '^[0-9]{4}$'
      THEN p_value || '-01-01T00:00:00.000Z'
    WHEN p_value ~ '^[0-9]{4}-[0-9]{2}$'
      THEN p_value || '-01T00:00:00.000Z'
    WHEN p_value ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
      THEN p_value || 'T00:00:00.000Z'
    WHEN p_value ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T'
      THEN to_char((p_value::timestamptz) AT TIME ZONE 'UTC',
                   'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
    ELSE NULL
  END
$$;

-- A token in the three shapes a search can ask for it.
--
-- sys|code is this code in this system, code is this code in any system, and
-- sys| is anything at all in this system. The index answers by containment, so
-- a form it does not carry is a search that silently finds nothing — worse
-- than an error, because a count of zero looks like an answer.
CREATE OR REPLACE FUNCTION dbo.token_forms(p_system text, p_code text)
RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE
    WHEN p_code IS NULL THEN '[]'::jsonb
    WHEN p_system IS NULL THEN
      jsonb_build_array(jsonb_build_object('t', 'tokc', 'v', p_code))
    ELSE jsonb_build_array(
      jsonb_build_object('t', 'tok', 's', p_system, 'v', p_code),
      jsonb_build_object('t', 'toks', 'v', p_system),
      jsonb_build_object('t', 'tokc', 'v', p_code))
  END
$$;

-- What one hit contributes, as key/value pairs.
--
-- Pairs rather than values because a string contributes to two keys: the base
-- path carries it folded for the ordinary case-insensitive search, and a
-- second key carries it as written for :exact.
--
-- The token shapes are told apart by what the document looks like, since that
-- is what is here: a concept carries codings, a coding carries system and
-- code, an identifier and a contact point both carry system and value, and
-- anything else that is a plain value is a bare code. The two that do not
-- differ in what they contribute are not worth telling apart.
CREATE OR REPLACE FUNCTION dbo.envelope_pairs(p_key text, p_kind text, p_hit jsonb)
RETURNS TABLE (key text, value jsonb) LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
  v_text text;
BEGIN
  IF p_hit IS NULL OR jsonb_typeof(p_hit) = 'null' THEN
    RETURN;
  END IF;
  v_text := CASE jsonb_typeof(p_hit)
              WHEN 'string' THEN p_hit #>> '{}'
              WHEN 'number' THEN p_hit #>> '{}'
              WHEN 'boolean' THEN p_hit #>> '{}'
              ELSE NULL
            END;

  IF p_kind = 'token' THEN
    IF v_text IS NOT NULL THEN
      RETURN QUERY SELECT p_key, jsonb_build_object('t', 'tokc', 'v', v_text);
    ELSIF p_hit ? 'coding' THEN
      RETURN QUERY
        SELECT p_key, form
          FROM jsonb_array_elements(p_hit -> 'coding') one,
               LATERAL jsonb_array_elements(
                   dbo.token_forms(one ->> 'system', one ->> 'code')) form;
    ELSIF p_hit ? 'code' THEN
      RETURN QUERY
        SELECT p_key, form
          FROM jsonb_array_elements(
                   dbo.token_forms(p_hit ->> 'system', p_hit ->> 'code')) form;
    ELSIF p_hit ? 'value' THEN
      RETURN QUERY
        SELECT p_key, form
          FROM jsonb_array_elements(
                   dbo.token_forms(p_hit ->> 'system', p_hit ->> 'value')) form;
    END IF;

  ELSIF p_kind = 'string' THEN
    IF v_text IS NOT NULL THEN
      RETURN QUERY SELECT p_key, jsonb_build_object('t', 'str', 'v', lower(v_text));
      RETURN QUERY SELECT p_key || '_xct', jsonb_build_object('t', 'str', 'v', v_text);
    END IF;

  ELSIF p_kind = 'uri' THEN
    -- as written: a uri is case-sensitive
    IF v_text IS NOT NULL THEN
      RETURN QUERY SELECT p_key, jsonb_build_object('t', 'str', 'v', v_text);
    END IF;

  ELSIF p_kind = 'number' THEN
    IF v_text IS NOT NULL AND v_text ~ '^-?[0-9]+(\.[0-9]+)?$' THEN
      RETURN QUERY SELECT p_key, jsonb_build_object('t', 'num', 'v', v_text::numeric);
    END IF;

  ELSIF p_kind = 'date' THEN
    -- a period or a timing is indexed by its start: the lower bound is what
    -- an ordering asks for
    v_text := COALESCE(v_text, p_hit ->> 'start');
    IF dbo.date_key(v_text) IS NOT NULL THEN
      RETURN QUERY SELECT p_key,
                          jsonb_build_object('t', 'date', 'v', dbo.date_key(v_text));
    END IF;

  ELSIF p_kind = 'reference' THEN
    v_text := COALESCE(p_hit ->> 'reference', v_text);
    IF v_text IS NOT NULL AND position('/' in v_text) > 0 THEN
      RETURN QUERY
        SELECT p_key, jsonb_build_object(
                 't', 'ref',
                 'tt', regexp_replace(
                           substring(v_text from '^(.*)/[^/]*$'), '^.*/', ''),
                 'ti', substring(v_text from '[^/]*$'));
    END IF;
  END IF;
  RETURN;
END;
$$;

-- Every value this document carries, by the key a search asks under.
--
-- Read from the compiled parameters this tenant holds, so what a document is
-- indexed by is what that tenant can be asked — the version's own parameters
-- and whatever it has authored, together, which is what the rows already say.
-- A parameter that did not compile selects nothing here, which is why it is
-- held saying so rather than dropped.
CREATE OR REPLACE FUNCTION dbo.envelope(p_doc jsonb, p_type text)
RETURNS jsonb LANGUAGE sql STABLE AS $$
  SELECT COALESCE(jsonb_object_agg(key, values), '{}'::jsonb)
    FROM (
      SELECT pair.key, jsonb_agg(pair.value) AS values
        FROM definitions.definition_parameter p,
             LATERAL jsonb_array_elements_text(p.paths) AS path,
             LATERAL jsonb_path_query(p_doc, path::jsonpath) AS hit,
             LATERAL dbo.envelope_pairs(p.code, p.kind, hit) AS pair
       WHERE p.base = p_type
         AND p.unenforceable IS NULL
         AND (p.predicate IS NULL
              OR jsonb_path_match(hit, p.predicate::jsonpath, '{}'::jsonb, true))
       GROUP BY pair.key) keyed
$$;

-- The three parts a write needs, from one walk.
--
-- The envelope is what a search asks by; the identifiers are the exclusive
-- claims a record makes; the reference edges are what it points at. All three
-- come off the same hits, which is the whole reason to compute them here: the
-- document is walked once, in the statement that stores it, instead of three
-- times over an object tree in another process.
--
-- ONE PART IS NOT HERE, and deliberately. A canonical type is identified by
-- its own url, and which types those are is something the registration knows
-- and the document does not — a url in a document that is not of such a type
-- is an ordinary value. So that claim stays with the engine, which is where
-- the knowledge is, and this returns what can be read from the document
-- against the parameters the tenant holds.
CREATE OR REPLACE FUNCTION dbo.envelope_parts(p_doc jsonb, p_type text)
RETURNS jsonb LANGUAGE sql STABLE AS $$
  WITH selected AS (
    SELECT p.code, p.kind, h AS hit
      FROM definitions.definition_parameter p,
           LATERAL jsonb_array_elements_text(p.paths) AS path,
           LATERAL jsonb_path_query(p_doc, path::jsonpath) AS h
     WHERE p.base = p_type
       AND p.unenforceable IS NULL
       AND (p.predicate IS NULL
            OR jsonb_path_match(h, p.predicate::jsonpath, '{}'::jsonb, true))
  ),
  keyed AS (
    SELECT pair.key, jsonb_agg(pair.value) AS values
      FROM selected,
           LATERAL dbo.envelope_pairs(selected.code, selected.kind, selected.hit) AS pair
     GROUP BY pair.key
  ),
  -- An Identifier with a system is an exclusive claim; without one it is
  -- still searchable as a token and claims nothing, because the same digits
  -- in two namespaces are two different things.
  claimed AS (
    SELECT selected.hit ->> 'system' AS system, selected.hit ->> 'value' AS value
      FROM selected
     WHERE selected.kind = 'token'
       AND selected.hit ? 'value'
       AND selected.hit ->> 'system' IS NOT NULL
  ),
  -- `Patient/123`, and `http://host/fhir/Patient/123`, are both an edge to
  -- Patient 123: the type is the segment before the id, wherever the string
  -- began. A reference with nothing before its last slash names no type and
  -- is no edge.
  pointed AS (
    SELECT code,
           substring(v from '^(.*)/[^/]*$') AS before,
           substring(v from '[^/]*$') AS target_id
      FROM (SELECT selected.code,
                   COALESCE(selected.hit ->> 'reference',
                            CASE WHEN jsonb_typeof(selected.hit) = 'string'
                                 THEN selected.hit #>> '{}' END) AS v
              FROM selected
             WHERE selected.kind = 'reference') referenced
     WHERE v IS NOT NULL
  )
  SELECT jsonb_build_object(
           'envelope',
           (SELECT COALESCE(jsonb_object_agg(key, values), '{}'::jsonb) FROM keyed),
           'identifiers',
           (SELECT COALESCE(jsonb_agg(jsonb_build_object(
                                'system', system, 'value', value)), '[]'::jsonb)
              FROM claimed),
           'references',
           (SELECT COALESCE(jsonb_agg(jsonb_build_object(
                                'refType', code,
                                'targetType', regexp_replace(before, '^.*/', ''),
                                'targetId', target_id)), '[]'::jsonb)
              FROM pointed
             WHERE before IS NOT NULL AND before <> ''))
$$;
