-- The walk, once, as a value the checks can share.
--
-- Every check needs the same thing: each element of the profile paired with
-- each instance of it the document holds. Each of them used to work that out
-- for itself, so a validation walked the document once per check — measured at
-- more than any single check cost.
--
-- Carried as an array rather than a table because a table cannot be handed to
-- five functions without being computed five times, which is the thing being
-- fixed.
CREATE OR REPLACE FUNCTION dbo.walked(doc jsonb, profile text)
RETURNS jsonb LANGUAGE sql STABLE AS $$
  SELECT coalesce(jsonb_agg(jsonb_build_object('e', element_id, 'i', instance)), '[]'::jsonb)
    FROM dbo.instances(doc, profile)
$$;
