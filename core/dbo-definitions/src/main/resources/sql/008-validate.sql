-- Tier one, under one name.
--
-- The same name on every face, so a caller asks one question however the
-- tenant's version spells its definitions. What stands behind it is
-- face-agnostic by construction: every check above reads the expanded rows,
-- and nothing in them names a FHIR version.
CREATE OR REPLACE FUNCTION dbo.validate(doc jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE sql STABLE AS $$
  SELECT * FROM dbo.cardinality_issues(doc, profile)
  UNION ALL
  SELECT * FROM dbo.value_issues(doc, profile)
  UNION ALL
  SELECT * FROM dbo.binding_issues(doc, profile)
$$;
