package com.videopilot.infrastructure.analysis.persistence;

import com.videopilot.analysis.port.QueueDepthProbe;
import com.videopilot.infrastructure.messaging.MessagingProperties;
import java.util.Properties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

final class RabbitQueueDepthProbe implements QueueDepthProbe {

    private final RabbitAdmin admin;
    private final MessagingProperties properties;

    RabbitQueueDepthProbe(RabbitAdmin admin, MessagingProperties properties) {
        this.admin = admin;
        this.properties = properties;
    }

    @Override
    public long backlog() {
        return messages(properties.queue()) + messages(properties.retryQueue()) + messages(properties.deadLetterQueue());
    }

    private long messages(String queue) {
        Properties details = admin.getQueueProperties(queue);
        if (details == null) throw new IllegalStateException("RabbitMQ queue is unavailable");
        Object value = details.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
        if (!(value instanceof Number count)) throw new IllegalStateException("RabbitMQ queue depth is unavailable");
        return count.longValue();
    }
}
