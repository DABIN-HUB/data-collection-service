CREATE TABLE dc_schema_version (
    version INTEGER PRIMARY KEY CHECK (version > 0),
    script_name TEXT NOT NULL,
    checksum TEXT NOT NULL,
    app_identity TEXT NOT NULL,
    installed_at TEXT NOT NULL
);
CREATE TABLE dc_config_revision (
    revision INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    operation TEXT NOT NULL CHECK (operation IN ('CREATE', 'UPDATE', 'DELETE')),
    created_at TEXT NOT NULL
);
CREATE TABLE dc_device (
    device_id TEXT PRIMARY KEY NOT NULL CHECK (length(trim(device_id)) > 0),
    device_json TEXT NOT NULL CHECK (coalesce(json_valid(device_json) AND json_type(device_json) = 'object'
        AND json_type(device_json, '$.deviceId') = 'text' AND json_extract(device_json, '$.deviceId') = device_id
        AND json_type(device_json, '$.configSource') = 'text' AND json_extract(device_json, '$.configSource') = 'local'
        AND json_type(device_json, '$.temporaryConfig') = 'true', 0)),
    revision INTEGER NOT NULL REFERENCES dc_config_revision(revision)
);
CREATE TABLE dc_device_connection (
    device_id TEXT PRIMARY KEY NOT NULL REFERENCES dc_device(device_id) ON DELETE CASCADE,
    connection_json TEXT NOT NULL CHECK (coalesce(json_valid(connection_json) AND json_type(connection_json) = 'object'
        AND json_type(connection_json, '$.deviceId') = 'text' AND json_extract(connection_json, '$.deviceId') = device_id, 0))
);
CREATE TABLE dc_device_point (
    point_id TEXT PRIMARY KEY NOT NULL CHECK (length(trim(point_id)) > 0),
    device_id TEXT NOT NULL REFERENCES dc_device(device_id) ON DELETE CASCADE,
    sort_order INTEGER NOT NULL CHECK (sort_order >= 0),
    status INTEGER,
    point_code TEXT,
    point_json TEXT NOT NULL CHECK (coalesce(json_valid(point_json) AND json_type(point_json) = 'object'
        AND json_type(point_json, '$.deviceId') = 'text' AND json_extract(point_json, '$.deviceId') = device_id
        AND json_type(point_json, '$.pointId') = 'text' AND json_extract(point_json, '$.pointId') = point_id
        AND ((status IS NULL AND (json_type(point_json, '$.status') IS NULL OR json_type(point_json, '$.status') = 'null'))
          OR (status IS NOT NULL AND json_type(point_json, '$.status') = 'integer'
              AND json_extract(point_json, '$.status') = status)), 0)),
    UNIQUE (device_id, sort_order)
);
CREATE INDEX idx_dc_point_device_sort ON dc_device_point(device_id, sort_order);
CREATE INDEX idx_dc_point_status ON dc_device_point(status);
CREATE INDEX idx_dc_point_code ON dc_device_point(point_code);
CREATE TABLE dc_protocol_profile (
    profile_id TEXT PRIMARY KEY NOT NULL CHECK (length(trim(profile_id)) > 0),
    protocol_type TEXT NOT NULL CHECK (length(trim(protocol_type)) > 0),
    connection_json TEXT NOT NULL CHECK (json_valid(connection_json) AND json_type(connection_json) = 'object'),
    revision INTEGER NOT NULL REFERENCES dc_config_revision(revision)
);
CREATE INDEX idx_dc_profile_protocol ON dc_protocol_profile(protocol_type);
CREATE TABLE dc_gateway_setting (
    setting_key TEXT PRIMARY KEY NOT NULL CHECK (length(trim(setting_key)) > 0),
    config_json TEXT NOT NULL CHECK (json_valid(config_json)),
    revision INTEGER NOT NULL REFERENCES dc_config_revision(revision)
);
CREATE TABLE dc_remote_snapshot (
    source TEXT NOT NULL CHECK (length(trim(source)) > 0 AND source <> 'local'),
    snapshot_id TEXT NOT NULL CHECK (length(trim(snapshot_id)) > 0),
    source_version TEXT NOT NULL CHECK (length(trim(source_version)) > 0),
    config_json TEXT NOT NULL CHECK (json_valid(config_json)),
    revision INTEGER NOT NULL REFERENCES dc_config_revision(revision),
    PRIMARY KEY (source, snapshot_id, source_version)
);
CREATE TABLE dc_json_migration (
    migration_id TEXT PRIMARY KEY NOT NULL,
    source_path TEXT NOT NULL,
    source_sha256 TEXT NOT NULL,
    device_count INTEGER NOT NULL CHECK (device_count >= 0),
    connection_count INTEGER NOT NULL CHECK (connection_count >= 0 AND connection_count <= device_count),
    point_count INTEGER NOT NULL CHECK (point_count >= 0),
    completed_at TEXT NOT NULL
);
