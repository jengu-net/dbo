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
    -- A reference is an edge, and edges are their own table: what a search
    -- by reference reads is a join, not a key in this document's envelope.
    -- What DOES belong here is the logical reference — a pointer by business
    -- identifier rather than by id, which the :identifier modifier asks
    -- about and which has nowhere else to live.
    IF p_hit ? 'identifier' AND p_hit #>> '{identifier,system}' IS NOT NULL THEN
      RETURN QUERY
        SELECT p_key || '_identifier', form
          FROM jsonb_array_elements(
                   dbo.token_forms(p_hit #>> '{identifier,system}',
                                   p_hit #>> '{identifier,value}')) form;
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
  SELECT COALESCE(jsonb_object_agg(key, vals), '{}'::jsonb)
    FROM (
      SELECT key, jsonb_agg(value ORDER BY code, ord, ci) AS vals
        FROM (
          SELECT pair.key, pair.value, p.code, found.ord, pair.ci
            FROM definitions.definition_parameter p,
                 -- What the expression finds, each thing once and in the
                 -- order the document says it. A parameter shared between
                 -- types is a union of branches, and a union says each of
                 -- its members once — two names with the same given name are
                 -- one value to search by, while two codings that happen to
                 -- share a system are two codings and contribute twice.
                 LATERAL (
                   SELECT walked.hit, MIN(walked.ord) AS ord
                     FROM (SELECT q.hit,
                                  row_number() OVER (ORDER BY path.pi, q.hi) AS ord
                             FROM jsonb_array_elements_text(p.paths)
                                      WITH ORDINALITY AS path(path, pi),
                                  LATERAL jsonb_path_query(p_doc, path::jsonpath)
                                      WITH ORDINALITY AS q(hit, hi)) walked
                    GROUP BY walked.hit) found,
                 -- The key a search asks under is the code with its hyphens
                 -- folded, which is what the envelope has always been keyed
                 -- by: an envelope path is a json key and a search names it.
                 LATERAL dbo.envelope_pairs(replace(p.code, '-', '_'), p.kind, found.hit)
                     WITH ORDINALITY AS pair(key, value, ci)
           WHERE p.base = p_type
             AND p.unenforceable IS NULL

          UNION ALL

          -- What the engine indexes on its own. No parameter a version
          -- publishes expresses a profile or a tag — they are asked after by
          -- name at the search surface — and nothing wrote them for a long
          -- time, so a search by either answered empty, which looks like
          -- nobody matching and is not.
          SELECT '_profile', jsonb_build_object('t', 'str', 'v', named.url #>> '{}'),
                 '', named.n, 1
            FROM jsonb_path_query(p_doc, '$."meta"."profile"[*]')
                     WITH ORDINALITY AS named(url, n)
           WHERE jsonb_typeof(named.url) = 'string'
             AND length(named.url #>> '{}') > 0

          UNION ALL

          SELECT '_tag', form.value, '', tag.n, form.k
            FROM jsonb_path_query(p_doc, '$."meta"."tag"[*]')
                     WITH ORDINALITY AS tag(one, n),
                 LATERAL jsonb_array_elements(
                     dbo.token_forms(tag.one ->> 'system', tag.one ->> 'code'))
                     WITH ORDINALITY AS form(value, k)

          UNION ALL

          -- A parameter that asks rather than selects, whose answer IS the
          -- value. `Patient.deceased.exists() and Patient.deceased != false`
          -- is a token parameter over a question, and a document that says
          -- nothing about it is findable as false rather than not findable.
          SELECT replace(p.code, '-', '_'),
                 jsonb_build_object('t', 'tokc', 'v',
                     CASE WHEN COALESCE(jsonb_path_match(p_doc, p.predicate::jsonpath,
                                                         '{}'::jsonb, true), false)
                          THEN 'true' ELSE 'false' END),
                 p.code, 1, 1
            FROM definitions.definition_parameter p
           WHERE p.base = p_type
             AND p.unenforceable IS NULL
             AND p.predicate IS NOT NULL
             AND COALESCE(jsonb_array_length(p.paths), 0) = 0
             AND p.kind = 'token'
        ) either
       GROUP BY key) keyed
$$;

-- Where this document points, as the rows an edge is stored as.
--
-- Separate from the envelope because that is where a reference lives: a
-- search by reference is answered by a join against the edges of a document
-- rather than by a key in it, and the two are written by the same statement
-- from the same bytes.
--
-- The type is the last step before the id, so a reference written absolutely
-- points at the same thing as one written relatively: `Patient/1` and
-- `https://elsewhere.test/fhir/Patient/1` are one edge, which is the reading
-- a chained search already depends on.
CREATE OR REPLACE FUNCTION dbo.reference_edges(p_doc jsonb, p_type text)
RETURNS TABLE (ref_type text, target_type text, target_id text)
LANGUAGE sql STABLE AS $$
  SELECT DISTINCT replace(p.code, '-', '_'),
         regexp_replace(substring(pointed from '^(.*)/[^/]*$'), '^.*/', ''),
         substring(pointed from '[^/]*$')
    FROM definitions.definition_parameter p,
         LATERAL jsonb_array_elements_text(p.paths) AS path,
         LATERAL jsonb_path_query(p_doc, path::jsonpath) AS hit,
         LATERAL (SELECT COALESCE(hit ->> 'reference',
                                  CASE jsonb_typeof(hit)
                                    WHEN 'string' THEN hit #>> '{}'
                                    ELSE NULL
                                  END) AS pointed) one
   WHERE p.base = p_type
     AND p.kind = 'reference'
     AND p.unenforceable IS NULL
     AND (p.predicate IS NULL
          OR jsonb_path_match(hit, p.predicate::jsonpath, '{}'::jsonb, true))
     AND pointed IS NOT NULL
     AND position('/' in pointed) > 0
$$;

-- What this document claims to be known as, as the rows a claim is stored as.
--
-- An identifier is a claim on a name somebody else may also make, so it is
-- adjudicated rather than merely indexed: the rows it becomes are what a
-- conditional write is decided on, and they live in their own table beside
-- the envelope for that reason. Which of them are identity-bearing is the
-- engine's question, asked of the type's registration, not this one.
--
-- A claim needs a system. Identifier.system is 0..1, so a legal document can
-- carry a value without one — still searchable as a token, which the envelope
-- holds, and not an exclusive claim, because the same digits in two
-- namespaces are two different things.
--
-- Told apart by what the parameter says its values ARE, not by their shape:
-- an Identifier and a ContactPoint are the same two fields in JSON, and a
-- telephone number read as a claim would be a patient found by somebody
-- else's phone.
CREATE OR REPLACE FUNCTION dbo.identifiers(p_doc jsonb, p_type text)
RETURNS TABLE (system text, value text)
LANGUAGE sql STABLE AS $$
  SELECT DISTINCT hit ->> 'system', hit ->> 'value'
    FROM definitions.definition_parameter p,
         LATERAL jsonb_array_elements_text(p.paths) AS path,
         LATERAL jsonb_path_query(p_doc, path::jsonpath) AS hit
   WHERE p.base = p_type
     AND p.ends_at = 'Identifier'
     AND p.unenforceable IS NULL
     AND jsonb_typeof(hit) = 'object'
     AND hit ->> 'system' IS NOT NULL
     AND hit ->> 'value' IS NOT NULL
$$;
