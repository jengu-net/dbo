package cloud.jengu.dbo.samples.stories;

import cloud.jengu.dbo.samples.server.ServerApplication;
import cloud.jengu.dbo.samples.worker.AdmittingAPatient;
import cloud.jengu.dbo.samples.worker.AskingForADirectoryCheck;
import cloud.jengu.dbo.samples.worker.MeasuringASpecimen;
import cloud.jengu.dbo.spring.test.DboSpringBootTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A user story, walked in Rowling Land, the sample world.
 *
 * <p><b>Everything a story class may vary is its legs.</b> The application,
 * the profile and the beans are fixed here, because Spring caches a context by
 * its configuration: a story class that differed in any of them would get a
 * second context, bringing up a second world over the same database, and Spring
 * would not close the first. Nothing on a story class should sit beside this
 * annotation except what JUnit reads.
 *
 * <p><b>The stories run at once.</b> {@code storyTest} runs classes
 * concurrently, and a class's legs in order on one thread, because a leg is set
 * up by the one before it. So a story owns what it makes and names it with
 * {@link StoryNames}, and asserts only on that.
 *
 * <p><b>Nothing starts until the whole world serves.</b> A story that began
 * while a tenant another story needs was still coming up would be measuring the
 * bring-up, and its failure would name the wrong thing.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("story")
@DboSpringBootTest
@ActiveProfiles("stories")
@SpringBootTest(classes = ServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({AdmittingAPatient.class, MeasuringASpecimen.class, AskingForADirectoryCheck.class})
@ExtendWith(TheWholeWorldServes.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public @interface AUserStory {
}
