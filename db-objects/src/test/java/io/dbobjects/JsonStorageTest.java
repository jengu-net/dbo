package io.dbobjects;

import io.dbobjects.context.TestcontainersTestContext;
import io.dbobjects.storage.PersonStorage;
import io.dbobjects.storage.Storage;
import io.dbobjects.testdomain.Person;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static io.dbobjects.context.TestcontainersTestContext.randomName;
import static io.dbobjects.util.Misc.runWithLoggerLevel;
import static java.util.List.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
public class JsonStorageTest {

    private static TestcontainersTestContext ctx;

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

    @Test
    public void testPassiveNodeCanNotBeUsedForBusiness() {
        // TODO implement PassiveNodeCanNotBeUsedForBusiness
        // can not be used:
        //   -
        var app = randomName("app_");
        var domain = randomName("my_domain_");
        var nodeId1 = randomName("node_");
        var personStorage = new PersonStorage(domain);
        Collection<Storage<?>> storages = of(personStorage);

        var node = ctx.createNode(app, nodeId1, storages).synchronize();
        var johnId = personStorage.put(new Person().setName("John Dow"));
        johnId = personStorage.put(johnId, new Person().setName("John Doe"));
        var donaldId = personStorage.put(null, new Person().setName("Donald Duck"));
        assertEquals(3, ctx.getUnprocessedDomainEvents(node, domain).size());
        node.synchronize();
        assertEquals(0, ctx.getUnprocessedDomainEvents(node, domain).size());

        var mickeyId = personStorage.put(new Person().setName("Mickey Mouse"));
        assertEquals(1, ctx.getUnprocessedDomainEvents(node, domain).size());
        node.synchronize();
        assertEquals(0, ctx.getUnprocessedDomainEvents(node, domain).size());

        runWithLoggerLevel("io.dbobjects.database", "DEBUG", () -> {
            var persons = personStorage.selectAll();
            assertNotNull(persons);
            assertEquals(3, persons.size());
        });

        var mapper = node.getNodeContext().getObjectMapper();

        personStorage.getById(johnId).ifPresentOrElse(p -> log.info(mapper.serialize(p)),
            () -> Assertions.fail("person John Doe should be created"));
        personStorage.getById(donaldId).ifPresentOrElse(p -> log.info(mapper.serialize(p)),
            () -> Assertions.fail("person Donald Duck should be created"));
        personStorage.getById(mickeyId).ifPresentOrElse(p -> log.info(mapper.serialize(p)),
            () -> Assertions.fail("person Mickey Mouse should be created"));

    }
}
