package io.dbobjects;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Collection;
import java.util.Optional;
import java.util.Properties;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Getter
@Setter
@Slf4j
@ToString
public class DboProperties implements DBO {

    private String applicationName;
    private int applicationVersion;
    private Collection<String> applicationDomains;

    public static DboProperties instance() {
        var propsFilename = System.getProperty(PROP_DBO_PROPERTIES_FILE, DEFAULT_DBO_PROPERTIES_FILE);

        var result = new DboProperties();
        var props = new Properties();
        var foundFile = false;

        try (var is = DboProperties.class.getResourceAsStream(propsFilename)) {
            props.load(is);
            foundFile = true;
        } catch (IOException | NullPointerException e) {
            log.debug("can not read dbo properties from file {}. Reading required properties as System.properties ...", propsFilename);
        }

        extractProp(result, PROP_DBO_APPLICATION_NAME,
                p -> p.setApplicationName(required(prop(props, PROP_DBO_APPLICATION_NAME))));
        extractProp(result, PROP_DBO_APPLICATION_VERSION,
                p -> p.setApplicationVersion(
                        required(numeric(prop(props, PROP_DBO_APPLICATION_VERSION)))));

        extractProp(result, PROP_DBO_DOMAINS, p -> p.setApplicationDomains(
                Stream.of(Optional.ofNullable(
                                        prop(props, PROP_DBO_DOMAINS).get())
                                .orElse(result.getApplicationName())
                                .split(","))
                        .map(String::trim)
                        .collect(Collectors.toList())
        ));
        log.info("Initializing DBO domains using following properties loaded from file {}({}) and overridden by System.properties: {}",
                propsFilename, foundFile ? "found" : "not found", result);
        return result;
    }

    static Supplier<String> prop(Properties props, String propName) {
        return () -> System.getProperty(propName, props.getProperty(propName));
    }

    private static <T> T required(Supplier<T> val) {
        var v = val.get();
        if (v == null) {
            throw new IllegalArgumentException("missing required property");
        }
        return v;
    }

    private static Supplier<Integer> numeric(Supplier<String> strVal) {
        return () -> Integer.valueOf(strVal.get());
    }

    private static void extractProp(DboProperties props, String message,
                                    Consumer<DboProperties> propertiesConsumer) {
        try {
            propertiesConsumer.accept(props);
        } catch (Exception ex) {
            throw (ex instanceof IllegalStateException)
                    ? (IllegalStateException) ex :
                    new IllegalStateException(message != null ? message : ex.getMessage(), ex);
        }
    }

    static BiFunction<Properties, String, String> prop =
            (props, propName) -> System.getProperty(propName, props.getProperty(propName));

    static Function<?, ?> required = (val) ->
            Optional.ofNullable(val).orElseThrow(() ->
                    new IllegalArgumentException("missing required property"));

}
