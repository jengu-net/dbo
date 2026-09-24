package cloud.jengu.dbo.samples.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * An application that performs a store's work because it added one dependency.
 *
 * <p>The mirror of the serving application beside it, and what is <b>absent</b>
 * is the point. There is no tenant declared here, no database, no store and no
 * way to get one: this application is handed work, performs it, and answers.
 * Where the work comes from is configuration — a lane naming a tenant, where
 * it answers, and a credential that tenant issued.
 *
 * <p>What an integrator writes is the step. Everything that used to sit around
 * one — an executor identity, a runner with its durations, a registration, a
 * lane built from a base URI and a bearer supplier — is configuration in
 * {@code application.yaml}, and a bean implementing {@code StepService} is
 * found by the container's own whiteboard.
 */
@SpringBootApplication
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
