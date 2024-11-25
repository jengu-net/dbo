package io.dbobjects.fhir.types;

public interface WithUniqueId<T> {
    String getId();

    T setId(String id);
}
