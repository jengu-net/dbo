package cloud.jengu.dbo.core.api;

/**
 * Upgrades a payload one schema-version hop (§2, REQ-DBO-CORE-UPGRADE-ON-READ).
 * The engine walks a chain of converters from the stored version to the
 * registration's current version on READ; stored bytes are never rewritten
 * (payload-is-truth). Conversion must preserve identity fields bit-exact
 * (REQ-DBO-CORE-IDENTITY-SURVIVES-CONVERSION).
 */
public interface PayloadConverter {

    String fromVersion();

    String toVersion();

    byte[] convert(String typeName, byte[] payload);
}
