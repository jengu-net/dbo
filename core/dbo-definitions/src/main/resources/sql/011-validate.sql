-- Tier one, under one name.
--
-- The same name on every face, so a caller asks one question however the
-- tenant's version spells its definitions. What stands behind it is
-- face-agnostic by construction: every check reads the expanded rows, and
-- nothing in them names a FHIR version.
--
-- The walk is done here, once, and handed to each check. Written as a block
-- rather than as a query with a shared expression because "once" should be a
-- thing the code says rather than a thing the planner can be relied upon to
-- decide.
CREATE OR REPLACE FUNCTION dbo.validate(doc jsonb, profile text)
RETURNS TABLE (severity text, path text, key text, detail text)
LANGUAGE plpgsql STABLE AS $$
DECLARE
  once jsonb := dbo.walked(doc, profile);
BEGIN
  RETURN QUERY
    SELECT * FROM dbo.cardinality_in(once, profile)
    UNION ALL SELECT * FROM dbo.value_in(once, profile)
    UNION ALL SELECT * FROM dbo.binding_in(once, profile)
    UNION ALL SELECT * FROM dbo.reference_in(once, profile)
    UNION ALL SELECT * FROM dbo.invariant_in(once, profile);
END;
$$;
