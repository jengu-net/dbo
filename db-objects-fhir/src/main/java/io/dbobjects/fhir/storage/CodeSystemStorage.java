package io.dbobjects.fhir.storage;

import io.dbobjects.fhir.ValueSet;
import io.dbobjects.fhir.terminology.CodeSystem;
import io.dbobjects.jsonstorage.JsonStorage;
import io.dbobjects.storage.PayloadInfo;
import io.dbobjects.storage.StorageObjectContext;
import io.dbobjects.storage.StorageObjectIdentifier;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.dbobjects.fhir.FhirConstants.CS_INTERNAL_CODE_SYSTEM_ID;

@Slf4j
public class CodeSystemStorage extends JsonStorage<CodeSystem> {
    public CodeSystemStorage(String domain) {
        super(CodeSystem.class, 1, domain);
        super.setEnvObjectBuilder(super::toMap);
    }

    public String importCodeSystem(CodeSystem codeSystem) {
        // TODO: ignore put if object hash is not changed

        return super.putByIdentifier(new StorageObjectIdentifier(CS_INTERNAL_CODE_SYSTEM_ID, codeSystem.getId()), codeSystem, so -> {
            var concepts = Optional.ofNullable(codeSystem.getConcept()).orElse(new ArrayList<>())
                .stream()
                .map(CodeSystem.CodeSystemConcept::getCode)
                .collect(Collectors.toList());
            var action = so == null ? "import" : "update";
            log.info("{} CodeSystem {} - {}", action, codeSystem.getId(), concepts);
        });
    }

    public String importValueSet(ValueSet valueSet) {
        return null;
    }

    public Optional<CodeSystem> getCodeSystemById(String id) {
        return super.getAsObject(id);
    }

    @Override
    public StorageObjectContext buildContextFor(PayloadInfo payloadInfo) {
        var result = super.buildContextFor(payloadInfo);
        var entity = getObjectMapper().deserialize(CodeSystem.class, payloadInfo.getPayload());
        result.withIdentifierIfExists(CS_INTERNAL_CODE_SYSTEM_ID, entity.getId());
        return result;
    }
}
