package io.dbobjects.testdomain;

import io.dbobjects.fhir.types.WithUniqueId;
import io.dbobjects.jsonstorage.JsonStorage;
import io.dbobjects.storage.PayloadInfo;
import io.dbobjects.storage.Reference;
import io.dbobjects.storage.ReferencedStorageObject;
import io.dbobjects.storage.StorageObject;
import io.dbobjects.storage.StorageObjectContext;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Optional;
import java.util.function.BiFunction;


@Slf4j
public class TestDataStorage<D extends WithUniqueId<D>> extends JsonStorage<D> {

    private BiFunction<String, D, Collection<Reference>> referenceBuilder;

    private TestDataStorage(@NonNull Class<D> typeClass, int typeVersion, String domain) {
        super(typeClass, typeVersion, domain);
    }

    public static <C extends WithUniqueId<C>> TestDataStorage<C> of(Class<C> clazz, String domain) {
        return new TestDataStorage<C>(clazz, 1, domain);
    }

    public TestDataStorage<D> withReferenceBuilder(BiFunction<String, D, Collection<Reference>> referenceBuilder) {
        this.referenceBuilder = referenceBuilder;
        return this;
    }

    @Override
    public StorageObjectContext buildContextFor(PayloadInfo payloadInfo) {
        var result = super.buildContextFor(payloadInfo);
        if (referenceBuilder != null) {
            result.withReferences(referenceBuilder.apply(payloadInfo.getId(), getObjectMapper().deserialize(getTypeClass(), payloadInfo.getPayload())));
        }
        return result;
    }

    public String put(String id, D entity) {
        return super.put(id, entity);
    }

    public Optional<D> getById(String id) {
        return super.getAsObject(id);
    }

    public Optional<StorageObject> getById(String id, boolean includeReferences) {
        return super.doSelect(super.createCriteria().withObjectId(id).withReferences()).stream().findFirst();
    }

    public String storageObjectToString(StorageObject o) {
        var ref = "";
        if (o instanceof ReferencedStorageObject) {
            var ro = (ReferencedStorageObject)o;
            ref = ro.getReference().getReferenceType() + ": ";
        }
        return ref + o.getPayloadInfo().getId() + " (" + o.getPayloadInfo().getType() + "): " + super.toMap(o.getPayloadInfo().getPayload()).toString();
    }


}
