package io.dbobjects;

import io.dbobjects.context.TestcontainersTestContext;
import io.dbobjects.fhir.HealthcareService;
import io.dbobjects.fhir.storage.HealthCareServiceStorage;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.dbobjects.context.TestcontainersTestContext.randomName;
import static io.dbobjects.db.postgres.PostgresQuerySearchCriteria.putToMap;
import static io.dbobjects.storage.SearchCriteria.Direction.asc;
import static io.dbobjects.storage.SearchCriteria.Direction.desc;
import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@DisplayName("Test criteria based selects")
public class SearchCriteriaTest {

    private static TestcontainersTestContext ctx;
    private DbObjects dbo;
    private HealthCareServiceStorage healthCareServiceStorage;

    @BeforeAll
    public static void beforeAllTests() {
        ctx = new TestcontainersTestContext();
    }

    @AfterAll
    public static void afterAllTests() {
        if (ctx != null) {
            ctx.close();
            ctx = null;
        }
    }

    @BeforeEach
    public void setUpTest() {
        this.healthCareServiceStorage = new HealthCareServiceStorage(randomName("domain_"));
        this.dbo = ctx.createNode(randomName("app_"), randomName("node_"), List.of(this.healthCareServiceStorage));
    }

    @Test
    @DisplayName("Test pagination aspect within criteria based selects")
    public void testPagination() {

        var recordCount = 301;
        // lets insert 301 records
        //data.createBooks(recordCount, i -> new Book().setTitle("title " + i).setIsbn("isbn-" + i));
        createHealthCareServices(recordCount, i -> new HealthcareService().setName("service-" + i));

        assertEquals(recordCount, healthCareServiceStorage.select(null).size(),
            () -> format("without limiting, the result must contain all records (%s)", recordCount));

        assertEquals(recordCount, healthCareServiceStorage.select(
                healthCareServiceStorage.createCriteria()).size(),
            () -> format("without limiting, the result must contain all records (%s)", recordCount));

        try (var mock = new AutoCloseable() {
            @Override
            public void close() {

            }
        }) {
            var criteria = healthCareServiceStorage.createCriteria().withPaginationInfo(0, 5);
            assertTrue(healthCareServiceStorage.select(criteria).size() <= 5,
                () -> "result count must not be bigger than limit");
        }
        {
            var criteria = healthCareServiceStorage.createCriteria().withPaginationInfo(10, 5);
            var records = healthCareServiceStorage.select(criteria);
            assertEquals(5, records.size(), () -> "result count must not be bigger than limit");
            HealthcareService firstRecord = null;
            HealthcareService lastRecord = null;
            for (HealthcareService record : records) {
                if (firstRecord == null) {
                    firstRecord = record;
                }
                lastRecord = record;
            }
            assertNotNull(firstRecord);
            assertNotNull(lastRecord);
            assertEquals("service-11", firstRecord.getName());
            assertEquals("service-15", lastRecord.getName());
        }
    }

    @Test
    @DisplayName("Test order by aspect within criteria based selects")
    public void testOrderBy() {

            createHealthCareServices(
                new HealthcareService().setName("service-2").setActive(true),
                new HealthcareService().setName("service-1").setActive(true),
                new HealthcareService().setName("service-3").setActive(false)
            );

            // preconditions
            var record1StorageItem = healthCareServiceStorage.selectAsStorageObjects(
                    healthCareServiceStorage.createCriteria().withValueIfExists("name", "service-1"))
                .stream().findFirst().orElse(null);
            assertNotNull(record1StorageItem);

            {
                var criteria = healthCareServiceStorage.createCriteria().orderBy("name", asc);
                var books = toArray(HealthcareService.class, healthCareServiceStorage.select(criteria));
                assertEquals(3, books.length);
                assertEquals("service-1", books[0].getName());
                assertEquals("service-2", books[1].getName());
                assertEquals("service-3", books[2].getName());
            }
            {
                var criteria = healthCareServiceStorage.createCriteria().orderBy("name", desc);
                var books = toArray(HealthcareService.class, healthCareServiceStorage.select(criteria));
                assertEquals(3, books.length);
                assertEquals("service-3", books[0].getName());
                assertEquals("service-2", books[1].getName());
                assertEquals("service-1", books[2].getName());
            }
            {
                var criteria = healthCareServiceStorage.createCriteria().orderBy("active", asc).orderBy("name", asc);
                var books = toArray(HealthcareService.class, healthCareServiceStorage.select(criteria));
                assertEquals(3, books.length);
                assertEquals("service-3", books[0].getName());
                assertEquals("service-1", books[1].getName());
                assertEquals("service-2", books[2].getName());
            }

    }

    @Test
    @DisplayName("Test where equals (string or boolean) within criteria based selects")
    public void testWhereEquals() {

            createHealthCareServices(
                new HealthcareService().setName("service-2").setActive(true),
                new HealthcareService().setName("service-1").setActive(false),
                new HealthcareService().setName("service-3").setActive(true)
            );

            // preconditions
            var record1StorageItem = healthCareServiceStorage.selectAsStorageObjects(
                    healthCareServiceStorage.createCriteria().withValueIfExists("name", "service-1"))
                .stream().findFirst().orElse(null);
            assertNotNull(record1StorageItem);

            {
                var criteria = healthCareServiceStorage.createCriteria().withValueIfExists("name", "service-3");
                var books = toArray(HealthcareService.class, healthCareServiceStorage.select(criteria));
                assertEquals(1, books.length);
                assertEquals("service-3", books[0].getName());
            }
            {
                var criteria = healthCareServiceStorage.createCriteria().withValueIfExists("name", "service-2").withValueIfExists("active", true);
                var books = toArray(HealthcareService.class, healthCareServiceStorage.select(criteria));
                assertEquals(1, books.length);
                assertEquals("service-2", books[0].getName());
            }
            {
                var criteria = healthCareServiceStorage.createCriteria().withValueIfExists("active", false);
                var books = toArray(HealthcareService.class, healthCareServiceStorage.select(criteria));
                assertEquals(1, books.length);
                assertEquals("service-1", books[0].getName());
            }
    }

    @Test
    public void testMapToObject() {
        var oMap = putToMap(null, "this.is.a.key", "value");
        putToMap(oMap, "this.is.array[*].key", "value in array");
        log.info("result: {}", oMap);
    }

    public void createHealthCareServices(int count, Function<Integer, HealthcareService> creator) {
        for (int i = 1; i <= count; i++) {
            healthCareServiceStorage.put(creator.apply(i));
        }
        dbo.synchronize();
    }

    public void createHealthCareServices(HealthcareService... records) {
        Stream.of(records).forEach(r -> healthCareServiceStorage.put(r));
        dbo.synchronize();
    }

    public <A> A[] toArray(Class<A> clazz, Collection<A> collection) {
        return collection.toArray((A[]) Array.newInstance(clazz, collection.size()));
    }

}
