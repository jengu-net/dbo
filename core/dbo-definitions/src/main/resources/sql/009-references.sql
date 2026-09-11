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
  v_table text;
  v_found boolean;
BEGIN
  IF p_type IS NULL
     OR p_id !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
  THEN
    RETURN NULL;
  END IF;
  FOR v_table IN
      SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
       WHERE n.nspname = 'state' AND c.relkind = 'r' AND c.relname LIKE '%\_data'
  LOOP
    EXECUTE format('SELECT EXISTS (SELECT 1 FROM state.%I'
                   || ' WHERE type = $1 AND id = $2::uuid AND NOT deleted)', v_table)
      INTO v_found USING p_type, p_id;
    IF v_found THEN
      RETURN true;
    END IF;
  END LOOP;
  RETURN false;
END;
$$;

-- A reference at an element that may hold one, resolved against the records.
--
-- Only where the definition says a reference may stand, so a string that looks
-- like one somewhere else is not chased. What is read is the literal reference
-- a document carries: a conditional reference is a question, and by the time a
-- write reaches here the face has already answered it.
--
-- ON ITS OWN CONNECTION, which is what "advisory" costs: a bundle that creates
-- a record and points at it in the same transaction has not committed when
-- this reads, so the reference reads as missing. Checking a reference as it is
-- WHEN THE WRITE HAPPENS means validating inside the write's own transaction,
-- which is the step after this one.
CREATE OR REPLACE FUNCTION dbo.reference_issues(doc jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT 'error', e.path, 'reference',
         format('%s points at %s, which is not a record this store holds',
                e.path, pointed.at)
    FROM dbo.instances(doc, profile) i
    JOIN state.definition_element e
      ON e.canonical = profile AND e.element_id = i.element_id AND e.unenforceable IS NULL
     AND e.types @> '[{"code":"Reference"}]'::jsonb
   CROSS JOIN LATERAL (SELECT i.instance ->> 'reference') AS pointed(at)
   WHERE pointed.at IS NOT NULL
     AND dbo.record_exists(split_part(pointed.at, '/', 1), split_part(pointed.at, '/', 2)) IS FALSE
$$;
