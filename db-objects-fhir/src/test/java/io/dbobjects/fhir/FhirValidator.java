package io.dbobjects.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.validation.IValidatorModule;
import ca.uhn.fhir.validation.ValidationResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.dbobjects.InvalidEntityException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;

@Slf4j
public class FhirValidator {
    private ca.uhn.fhir.validation.FhirValidator validator;
    private FhirContext ctx;
    private static FhirValidator instance;

    private FhirValidator() {
        this.ctx = FhirContext.forR5();
        this.validator = ctx.newValidator();
        validator.setValidateAgainstStandardSchematron(false);
        //validator.setValidateAgainstStandardSchema(true);
        IValidatorModule module = new FhirInstanceValidator(ctx);
        validator.registerValidatorModule(module);
    }

    private static FhirValidator getInstance() {
        if (instance == null) {
            instance = new FhirValidator();
        }
        return instance;
    }

    public static ValidationResult validate(Object object) {
        return validate(JsonUtil.getObjectMapper().serialize(object));
    }

    public static ValidationResult validate(String jsonStr) {
        log.info("resource: \n{}", jsonStr);
        var fhirResource = getInstance().ctx.newJsonParser().parseResource(jsonStr);
        return getInstance().validator.validateWithResult(fhirResource);
    }

    public static String serialize(Object object) {
        return JsonUtil.getObjectMapper().serialize(object);
    }

    public static <T> T deserialize(Class<T> clazz, String str) {
        return JsonUtil.getObjectMapper().deserialize(clazz, str);
    }

    private static class JsonUtil {
        private static JacksonObjectMapper mapper;
        private static JsonFactory jsonFactory = new JsonFactory().setCodec(((JacksonObjectMapper) getObjectMapper()).jacksonObjectMapper);

        public static synchronized io.dbobjects.ObjectMapper getObjectMapper() {
            if (mapper == null) {
                mapper = new JacksonObjectMapper(buildCommonObjectMapper());
            }

            return mapper;
        }

        private static ObjectMapper buildCommonObjectMapper() {
            ObjectMapper mapper = new ObjectMapper();
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
            mapper.configure(DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS, true);
            mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
            mapper.configure(DeserializationFeature.USE_LONG_FOR_INTS, true);
            mapper.registerModule(new JavaTimeModule());
            return mapper;
        }

        public static JsonParser createJsonParser(InputStream inputStream) {
            try {
                return jsonFactory.createParser(inputStream);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @RequiredArgsConstructor
        private static class JacksonObjectMapper implements io.dbobjects.ObjectMapper {

            private final ObjectMapper jacksonObjectMapper;

            @Override
            public String serialize(Object o) {
                if (o == null) {
                    return null;
                }
                try {
                    return jacksonObjectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(o);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public <T> T deserialize(Class<T> clazz, String s) {
                if (s == null) {
                    return null;
                }
                try {
                    return jacksonObjectMapper.readValue(s, clazz);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public <C> Collection<C> fromJsonArrayString(String entityString, Class<C> clazz) {
                try {
                    return entityString == null ? null :
                            jacksonObjectMapper.readValue(entityString,
                                    jacksonObjectMapper.getTypeFactory().constructCollectionType(ArrayList.class, clazz));
                } catch (JsonProcessingException e) {
                    //log.info("error from parsing {} to {}", entityString, clazz.getName());
                    throw new InvalidEntityException(e);
                }
            }

        }

    }
}
