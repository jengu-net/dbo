-- What a coded element holds, and whether the tenant's terminology says it
-- belongs.
--
-- The join the design is for: a binding names a value set, the value set's
-- rules and the concepts are rows in this same database, and membership is
-- answered by reading them rather than by holding a terminology server in
-- memory.
--
-- Parameters carry a `p_` prefix here because `system` and `code` are column
-- names in the tables these read, and a parameter that shadows a column reads
-- correctly and means something else.

-- The (system, code) pairs an instance carries: a bare code carries none, a
-- Coding carries its own, a CodeableConcept carries one per coding.
CREATE OR REPLACE FUNCTION dbo.coded_values(p_instance jsonb)
RETURNS TABLE (system text, code text) LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT NULL::text, p_instance #>> '{}' WHERE jsonb_typeof(p_instance) = 'string'
  UNION ALL
  SELECT coding ->> 'system', coding ->> 'code'
    FROM jsonb_array_elements(
           CASE WHEN jsonb_typeof(p_instance) = 'object'
                THEN coalesce(p_instance -> 'coding', '[]'::jsonb) ELSE '[]'::jsonb END) AS coding
  UNION ALL
  SELECT p_instance ->> 'system', p_instance ->> 'code'
   WHERE jsonb_typeof(p_instance) = 'object' AND p_instance ? 'code'
$$;

-- Whether one concept is the other or sits under it, for an `is-a` include.
CREATE OR REPLACE FUNCTION dbo.descends_from(p_system text, p_code text, p_ancestor text)
RETURNS boolean LANGUAGE sql STABLE AS $$
  WITH RECURSIVE up AS (
      SELECT c.code, c.parent_code FROM state.term_concept c
       WHERE c.system = p_system AND c.code = p_code
    UNION ALL
      SELECT parent.code, parent.parent_code FROM up
        JOIN state.term_concept parent
          ON parent.system = p_system AND parent.code = up.parent_code
  )
  SELECT coalesce(bool_or(up.code = p_ancestor), false) FROM up
$$;

-- Whether a code is in a value set: true, false, or NULL for cannot say.
--
-- Three answers rather than two, deliberately. "This store does not hold that
-- value set" and "this store does not hold any of the systems it is built
-- from" are both different from "that code is not in it", and answering them
-- alike would refuse a tenant's data for being unknown here
-- (REQ-DBO-VAL-UNRESOLVABLE-IS-NOT-INVALID).
--
-- A bare `code` arrives with no system at all — a primitive says only its
-- value — so what judges it is each system the value set is built from that
-- this tenant actually holds. Where it holds none, nobody here can say.
CREATE OR REPLACE FUNCTION dbo.in_value_set(p_valueset text, p_system text, p_code text)
RETURNS boolean LANGUAGE plpgsql STABLE AS $$
DECLARE
  v_compose jsonb;
  v_set     jsonb;
  v_named   boolean := false;  -- the value set is built from this system
  v_judged  boolean := false;  -- and this tenant holds it, so it could say
BEGIN
  SELECT compose INTO v_compose FROM state.term_valueset
   WHERE url = split_part(p_valueset, '|', 1);
  IF v_compose IS NULL THEN
    RETURN NULL;
  END IF;

  -- What is excluded is out, whatever an include says about it.
  FOR v_set IN SELECT * FROM jsonb_array_elements(coalesce(v_compose -> 'excludes', '[]'::jsonb))
  LOOP
    IF (p_system IS NULL OR p_system = v_set ->> 'system')
       AND EXISTS (SELECT 1 FROM state.term_system WHERE url = v_set ->> 'system')
       AND (NOT (v_set ? 'codes')
            OR p_code IN (SELECT jsonb_array_elements_text(v_set -> 'codes')))
    THEN
      RETURN false;
    END IF;
  END LOOP;

  FOR v_set IN SELECT * FROM jsonb_array_elements(coalesce(v_compose -> 'includes', '[]'::jsonb))
  LOOP
    IF p_system IS NOT NULL AND p_system <> v_set ->> 'system' THEN
      CONTINUE;
    END IF;
    v_named := true;
    IF NOT EXISTS (SELECT 1 FROM state.term_system WHERE url = v_set ->> 'system') THEN
      CONTINUE;
    END IF;
    v_judged := true;
    IF v_set ? 'codes' THEN
      IF p_code IN (SELECT jsonb_array_elements_text(v_set -> 'codes')) THEN
        RETURN true;
      END IF;
    ELSIF v_set ? 'isA' THEN
      IF dbo.descends_from(v_set ->> 'system', p_code, v_set ->> 'isA') THEN
        RETURN true;
      END IF;
    ELSIF EXISTS (SELECT 1 FROM state.term_concept
                   WHERE system = v_set ->> 'system' AND code = p_code) THEN
      RETURN true;
    END IF;
  END LOOP;

  -- A code from a system the value set is not built from is not in it, and
  -- that is knowable without holding anything.
  IF p_system IS NOT NULL AND NOT v_named THEN
    RETURN false;
  END IF;
  IF v_judged THEN
    RETURN false;
  END IF;
  RETURN NULL;
END;
$$;
