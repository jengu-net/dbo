CREATE EXTENSION IF NOT EXISTS btree_gin;

CREATE TABLE IF NOT EXISTS DBO_%1$s_%2$s_%3$s_EVENTS (
    event_id BIGSERIAL PRIMARY KEY,
    event_type varchar NOT NULL,
    object_id varchar NOT NULL,
    object_type varchar NOT NULL,
    object_version integer,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    app_code varchar NOT NULL,
    context JSONB,
    payload BYTEA);

CREATE TABLE IF NOT EXISTS DBO_%1$s_%2$s_%3$s_PROCESSED (
    event_id bigint NOT NULL,
    processed_at TIMESTAMPTZ DEFAULT NOW(),
    constraint DBO_%1$s_%2$s_%3$s_id_unq unique (event_id));

CREATE TABLE IF NOT EXISTS DBO_%1$s_%2$s_%3$s_DATA (
    id varchar NOT NULL,
    app_code varchar NOT NULL,
    object_type varchar NOT NULL,
    object_version integer,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    modified_at TIMESTAMPTZ,
    context JSONB,
    payload BYTEA,
    last_event_id bigint,
    constraint DBO_%1$s_%2$s_%3$s_data_unq unique (id, app_code, object_type));

CREATE TABLE IF NOT EXISTS DBO_%1$s_%2$s_%3$s_IDENTIFIER (
    app_code varchar NOT NULL,
    object_type varchar NOT NULL,
    system varchar not null,
    value varchar not null,
    object_id varchar not null,
    CONSTRAINT DBO_%1$s_%2$s_%3$s_identifier_unq unique (app_code, object_type, system, value, object_id));

CREATE TABLE IF NOT EXISTS DBO_%1$s_%2$s_%3$s_REFERENCE (
    app_code varchar NOT NULL,
    owner_object_type varchar NOT NULL,
    owner_object_id varchar not null,
    ref_type varchar not null,
    ref_object_type varchar not null,
    ref_object_id varchar not null);
