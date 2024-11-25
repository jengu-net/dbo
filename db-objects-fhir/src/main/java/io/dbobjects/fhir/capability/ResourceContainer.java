package io.dbobjects.fhir.capability;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.dbobjects.fhir.types.Reference;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;

@Slf4j
@Getter
@RequiredArgsConstructor
@JsonSerialize(using = ResourceContainerSerializer.class, typing = JsonSerialize.Typing.DYNAMIC)
public class ResourceContainer extends HashSet<Resource> implements Collection<Resource> {

    private final Resource owningResource;

    void normalizeContainedResources() {
        var resourceCollection = new ArrayList<Resource>();
        slurp(resourceCollection, this);
        this.addAll(resourceCollection);
    }

    private void slurp(Collection<Resource> resourceCollection, ResourceContainer r) {
        resourceCollection.addAll(r);
        r.forEach(sr -> slurp(resourceCollection, sr.getContained()));
        r.clear();
    }

    public Optional<Resource> getAsLocalReference(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return super.stream().filter(r -> id.equals(r.getId())).findFirst();
    }

    public <T> Optional<T> getAsLocalReference(String id, Class<T> clazz) {
        return getAsLocalReference(id).map(o -> castTo(o, clazz));
    }

    public Optional<Resource> getAsLocalReference(Reference ref) {
        if (ref == null || ref.getReference() == null || !ref.getReference().startsWith("#")) {
            return Optional.empty();
        }
        return getAsLocalReference(ref.getReference().substring(1));
    }

    public <T> Optional<T> getAsLocalReference(Reference ref, Class<T> clazz) {
        return getAsLocalReference(ref.getReference().substring(1), clazz);
    }

    private <T> T castTo(Object o, Class<T> clazz) {
        if (clazz.isAssignableFrom(o.getClass())) {
            return clazz.cast(o);
        } else {
            throw new IllegalArgumentException(o.getClass().getName() + " can not cast to " + clazz.getName());
        }
    }

    @Override
    public boolean add(Resource resource) {
        validateResource(resource);
        return super.add(resource);
    }

    @Override
    public boolean addAll(@NonNull Collection<? extends Resource> resources) {
        resources.forEach(this::validateResource);
        return super.addAll(resources);
    }

    public Reference addAsLocalReference(Resource resource) {
        validateResource(resource);
        super.add(resource);
        return Reference.of(resource);
    }

    private void validateResource(Resource resource) throws IllegalArgumentException {
        if (resource == null || resource.getId() == null || resource.getId().isBlank()) {
            throw new IllegalArgumentException("existing resource with non-blank (more than whitespace) id is needed "
                + "for this operation");
        }
        super.stream()
            .filter(existingResource -> existingResource.getId().equals(resource.getId()))
            .findFirst()
            .ifPresent(existingResource -> {
                throw new IllegalArgumentException("resource with id " + existingResource.getId()
                    + " already exists");
            });
    }

}
