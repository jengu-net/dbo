package io.dbobjects.storage;

import lombok.Data;

public interface SearchCriteria {

    /**
     * Prepares filter by object id. Searching object by id is the fastest way to find object. The limitation is that
     * object id is designed to be internal.
     * Every next call of the method replaces previously set object id.
     * For (globally) shared identifiers please see withIdentifier.
     *
     * @param objectId required objectId
     * @return searchCriteria with applied arguments.
     */
    SearchCriteria withObjectId(String objectId);

    /**
     * Every next call of the method adds a new option to the list. The match exists if any of entered identifiers
     * matches.
     *
     * @param identifier an identifier
     * @return current object
     */
    SearchCriteria withIdentifier(StorageObjectIdentifier identifier);

    SearchCriteria withPaginationInfo(Integer offset, Integer limit);

    SearchCriteria orderBy(String envField, Direction direction);

    SearchCriteria withValueIfExists(String dottedKey, String value);

    SearchCriteria withValueIfExists(String dottedKey, Number value);

    SearchCriteria withValueIfExists(String dottedKey, Boolean value);

    SearchCriteria withReferences();

    int getIdentifierCount();

    void prepare();

    @Data
    final class OrderBy {
        private String field;
        private Direction direction;
    }

    enum Direction {
        asc, desc;
    }

}
