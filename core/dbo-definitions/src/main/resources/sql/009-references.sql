-- Whether a reference points at something this store actually holds.
--
-- The other half of the design's claim about references: a reference is not
-- checked as it WAS when a context was loaded, but as it is, by reading the
-- records beside the document. That it is a join rather than a lookup is the
-- whole point — the records are here.
--
-- Found through the catalogue rather than through a registry. A record lives
-- in the table its domain owns, and which domains a tenant has is a fact about
-- this database that the database can answer; the alternative is shipped SQL
-- that has to be told what a face registered, which is the coupling this
-- arrangement exists to avoid.
--
-- Three answers again, and the third is what keeps a correct document safe.
-- An id that is not one of this store's is not a missing record: an absolute
-- url names another server, a fragment names something contained in the
-- document itself, and neither is this store's to judge.
CREATE OR REPLACE FUNCTION dbo.record_exists(p_type text, p_id text)
RETURNS boolean LANGUAGE plpgsql STABLE AS $$
DECLARE
  v_schema text;
  v_table text;
  v_found boolean;
BEGIN
  IF p_type IS NULL
     OR p_id !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
  THEN
    RETURN NULL;
  END IF;
  -- Every schema a domain's records can be in, not only the shared one. A
  -- tenant's definitions live in a schema of their own, and a reference to
  -- one — a profile's base, a value set a binding names — would otherwise
  -- read as pointing at a record this store does not hold.
  FOR v_schema, v_table IN
      SELECT n.nspname, c.relname
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
       WHERE n.nspname IN ('state', 'definitions')
         AND c.relkind = 'r' AND c.relname LIKE '%\_data'
  LOOP
    EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.%I'
                   || ' WHERE type = $1 AND id = $2::uuid AND NOT deleted)',
                   v_schema, v_table)
      INTO v_found USING p_type, p_id;
    IF v_found THEN
      RETURN true;
    END IF;
  END LOOP;
  RETURN false;
END;
$$;

-- Two entry points, one body. The name taking a document walks it and hands
-- the walk on; the name taking a walk is what `dbo.validate` calls, so a whole
-- validation walks once instead of once per check.
CREATE OR REPLACE FUNCTION dbo.reference_in(walked jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT 'error', e.path, 'reference',
         format('%s points at %s, which is not a record this store holds',
                e.path, pointed.at)
    FROM jsonb_array_elements(walked) AS at
    JOIN definitions.definition_element e
      ON e.canonical = profile AND e.element_id = at.value ->> 'e' AND e.unenforceable IS NULL
     AND e.types @> '[{"code":"Reference"}]'::jsonb
   CROSS JOIN LATERAL (SELECT (at.value -> 'i') ->> 'reference') AS pointed(at)
   WHERE pointed.at IS NOT NULL
     AND dbo.record_exists(split_part(pointed.at, '/', 1), split_part(pointed.at, '/', 2)) IS FALSE
$$;

CREATE OR REPLACE FUNCTION dbo.reference_issues(doc jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT * FROM dbo.reference_in(dbo.walked(doc, profile), profile)
$$;
