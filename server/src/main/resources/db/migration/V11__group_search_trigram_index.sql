-- The admin group list filtered names with POSITION(lower(?) IN lower(c.name)) > 0, which no index
-- can serve: a term that matched no group made the planner read every conversation row. That is the
-- same defect V9 fixed for the account list, on a different table.
--
-- pg_trgm understands substring LIKE, so the filter now uses LIKE with escaped wildcards against
-- this index. It is partial on type = 'group' because the admin query always restricts to groups,
-- and direct chats have no name to match.
--
-- fastupdate is off for the same reason as V9: the pending list hides rows written since the last
-- vacuum, which is exactly the workload of a freshly populated group table.
--
-- The extension lives in public and the opclass is schema-qualified because Flyway runs with the
-- application schema on its search_path.

CREATE INDEX conversation_group_name_trgm_idx
    ON conversation USING gin (LOWER(name) public.gin_trgm_ops)
    WITH (fastupdate = off)
    WHERE type = 'group';
