package io.dbo.demo.micronaut.petclinic;

import io.micronaut.context.env.Environment;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.runtime.Micronaut;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;

@Singleton
@Slf4j
public class PetclinicApplication {

    @EventListener
    public void onStartup(StartupEvent event) {
        String micronautVersion = io.micronaut.core.version.VersionUtils.getMicronautVersion();
        var env = event.getSource().getBean(Environment.class);
        log.info("Micronaut Version: {}; env: {}", micronautVersion, env.getActiveNames());
        event.getSource().getBeanDefinitions(HttpServerFilter.class)
            .stream()
            .map(def -> event.getSource().getBean(def))
            .sorted(Comparator.comparing(HttpServerFilter::getOrder)).forEach(bean ->
                log.info("loaded HttpServletFilter: {} ({})", bean.getClass().getName() , bean.getOrder()));
    }

    public static void main(String[] args) {
        Micronaut.run(PetclinicApplication.class);
    }

}
