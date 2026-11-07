package com.videopilot.analysis.port;

@FunctionalInterface
public interface QueueDepthProbe {

    long backlog();
}
