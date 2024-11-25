package io.dbobjects.eventing;

import java.util.Collection;

public interface EventingConfiguration {

  Collection<String> getHosts();

  String getGlobalErrorTopic();
}
