package io.dbo.eventing;

import java.util.Map;

public record BusinessActionEvent(String eventId,
                                  Map<String, String> eventContext,
                                  String appCode,
                                  String processCode,
                                  String stepCode,
                                  String actionCode,
                                  byte[] actionData) {

}
