package io.dbobjects.fhir.util;

import com.fasterxml.jackson.core.JsonToken;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
public class BundleUtil {

    public static void processBundleEntries(InputStream bundleStream,
                                            Map<String, Class<?>> interestedTypes,
                                            Consumer<BundleEntry> bundleItemConsumer) {
        if (bundleStream == null) {
            return;
        }
        try (var bundleParser = JsonUtil.createJsonParser(bundleStream)) {
            log.info("parsing types {} from bundle ...", interestedTypes.keySet());
            //noinspection StatementWithEmptyBody
            while (bundleParser.nextToken() != null && !"entry".equals(bundleParser.getCurrentName())) {
            };
            while (bundleParser.currentToken() != null && bundleParser.currentToken() != JsonToken.START_ARRAY) {
                bundleParser.nextToken();
                log.warn("next {}", bundleParser.currentToken());
            }
            var currentToken = bundleParser.currentToken();
            int count = 0;
            while (bundleParser.nextToken() != JsonToken.END_ARRAY) {
                count++;
                //var book = bookParser.readValueAs(Book.class);
                var entry = bundleParser.readValueAsTree();
                var fullUrl = entry.get("fullUrl").toString();
                var resourceStr = entry.get("resource").toString();
                var resourceInfo = JsonUtil.getObjectMapper().deserialize(InternalBundleResourceInfo.class, resourceStr);
                var resourceType = resourceInfo.getResourceType();
                var resourceId = resourceInfo.getId();
                var resourceClass = interestedTypes.get(resourceType);
                if (resourceClass != null) {
                    log.debug("parsing resource {}: {} - {}", resourceType, resourceId, fullUrl);
                    var resourcesObject = JsonUtil.getObjectMapper().deserialize(resourceClass, resourceStr);
                    //bundleParser.readValueAs(resourceClass);
                    bundleItemConsumer.accept(new BundleEntry()
                            .setResourceObject(resourcesObject)
                            .setFullUrl(fullUrl)
                            .setResourceId(resourceId)
                            .setResourceType(resourceType)
                            .setResourceClass(resourceClass));

                } else {
                    log.debug("ignoring resource {}: {} - {}", resourceType, resourceId, fullUrl);
                }
            }
            log.info("bundle parsing finished! found {} entries", count);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Data
    public static class BundleEntry {
        private String fullUrl;
        private String resourceType;
        private Class<?> resourceClass;
        private String resourceId;
        private String resourceAsString;
        private Object resourceObject;

        public <T> T castResource(Class<T> clazz) {
            return clazz.cast(resourceObject);
        }
    }

    @Data
    private static class InternalBundleResourceInfo {
        private String resourceType;
        private String id;
    }
}
