package io.dbo.eventing;

import io.dbobjects.eventing.EventingConfiguration;
import lombok.Data;

import java.util.Collection;

@Data
public class SimpleEventConfiguration implements EventingConfiguration {
    private Collection<String> hosts;
    private String globalErrorTopic;
}
