package com.clawcode.agent.core.session;

import com.clawcode.agent.shared.message.Message;
import java.util.List;

public record Session(
    String sessionId,
    List<Message> messages
) {
}
