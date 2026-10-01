package cloud.jengu.dbo.samples.worker;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Import;

/**
 * This application's steps, and its asking, arriving in an application that
 * embeds it.
 *
 * <p>The clinic's own application depends on this one and runs its steps in
 * the same JVM, beside the store. It cannot find them by scanning: widening its
 * scan to this package would also find {@link WorkerApplication}, and with it
 * every step a second time — which the worker starter refuses, because the
 * runner keys a step by its code and one of the two would silently never run.
 *
 * <p>So the steps travel as an auto-configuration, and step aside when this
 * application is the one running: standing alone, {@link WorkerApplication}
 * scans them itself, and importing them again here would be that same second
 * registration. Spring's own scan leaves a class listed as an
 * auto-configuration alone, which is what keeps the two paths apart.
 *
 * <p>The beans are the same classes in both modes. None holds a URL or names a
 * tenant; whether the work reaches them over the application's own port, over
 * HTTP from another JVM or over the deployment's substrate is configuration.
 */
@AutoConfiguration
@ConditionalOnMissingBean(WorkerApplication.class)
@Import({AdmittingAPatient.class, RegisteringAPatient.class, MeasuringASpecimen.class,
        AskingForADirectoryCheck.class, AskingForAnAdmission.class, HearingBack.class})
public class TheWorkersSteps {
}
