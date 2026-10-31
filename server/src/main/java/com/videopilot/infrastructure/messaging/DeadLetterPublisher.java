package com.videopilot.infrastructure.messaging;

import org.springframework.amqp.core.Message;

interface DeadLetterPublisher {

    boolean publish(Message message);
}
