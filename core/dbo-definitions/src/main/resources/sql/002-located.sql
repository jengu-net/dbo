-- Where an element is, inside one instance of its parent.
--
-- An element's steps are jsonpaths relative to the parent instance: one
-- normally, several for a choice, one with a predicate for a slice. What comes
-- back is every member they find, as one array, so the caller counts it or
-- walks into it without caring which of those an element was.
--
-- `[*]` is already on each step, so an array yields its members rather than
-- itself, and a key that is not there yields nothing rather than a null.
CREATE OR REPLACE FUNCTION dbo.located(instance jsonb, steps text[])
RETURNS jsonb LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT coalesce(jsonb_agg(found), '[]'::jsonb)
    FROM unnest(steps) AS step,
         LATERAL jsonb_array_elements(jsonb_path_query_array(instance, step::jsonpath)) AS found
$$;
