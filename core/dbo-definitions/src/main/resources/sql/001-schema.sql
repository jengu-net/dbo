-- The schema this store's own functions live in, beside the tenant's state.
--
-- Its own schema rather than state: what is in state is DATA, which arrives
-- through a chain and is replicated, restored and exported. What is here is
-- CODE, which ships with the release and arrives no other way — a function
-- that could be replicated would be a way to run something on a tenant by
-- writing to a feed.
CREATE SCHEMA IF NOT EXISTS dbo;
