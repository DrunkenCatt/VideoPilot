package com.videopilot.infrastructure.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.agent.port.AgentDecisionPort;
import com.videopilot.answer.port.LanguageModelPort;
import java.util.Objects;

public final class LanguageModelAgentDecisionAdapter implements AgentDecisionPort {

    private final LanguageModelPort model;
    private final ObjectMapper json;

    public LanguageModelAgentDecisionAdapter(LanguageModelPort model, ObjectMapper json) {
        this.model = Objects.requireNonNull(model, "model");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public PlanResponse plan(PlanRequest request) {
        String prompt = """
                You are the bounded planner for a teaching-video evidence agent.
                Return exactly one JSON object with keys schemaVersion, action, query, expectedFacts.
                action must be RETRIEVE, NEEDS_CLARIFICATION, or REFUSE.
                For RETRIEVE, write one focused search query (max 200 characters) and 1-3 short facts
                that the final answer must cover (each max 72 characters).
                For terminal actions, query must be empty and expectedFacts must be empty.
                Never return owner IDs, budgets, SQL, URLs, file paths, tool names, or explanations.
                Goal: %s
                """.formatted(request.goal());
        LanguageModelPort.Response response = model.generate(
                new LanguageModelPort.Request(prompt, request.inputFingerprint()));
        try {
            Plan plan = json.readerFor(Plan.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(response.candidateText());
            return new PlanResponse(plan, new Usage(
                    response.usage().inputTokens(), response.usage().outputTokens()));
        } catch (RuntimeException | java.io.IOException exception) {
            throw new InvalidDecisionException(exception);
        }
    }

    @Override
    public CritiqueResponse critique(CritiqueRequest request) {
        try {
            String prompt = """
                    Judge whether the verified answer covers the teaching-video goal and is semantically relevant.
                    Return exactly one JSON object with schemaVersion, action, query, startMs, endMs,
                    missingRequirements, unsupportedClaims. action must be PASS, RETRIEVE, or EXPAND_WINDOW.
                    PASS must have empty repair fields. RETRIEVE needs one focused query and missing requirements.
                    EXPAND_WINDOW needs a non-negative [startMs,endMs] and missing requirements.
                    Do not judge citation existence or override the deterministic evidence gate.
                    GOAL: %s
                    EXPECTED_FACTS: %s
                    VERIFIED_ANSWER_CLAIMS: %s
                    EVIDENCE_SUMMARIES: %s
                    """.formatted(request.goal(), json.writeValueAsString(request.expectedFacts()),
                    json.writeValueAsString(request.answerClaims()),
                    json.writeValueAsString(request.evidenceSummaries()));
            LanguageModelPort.Response response = model.generate(
                    new LanguageModelPort.Request(prompt, request.inputFingerprint()));
            Critique critique = json.readerFor(Critique.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(response.candidateText());
            return new CritiqueResponse(critique, new Usage(
                    response.usage().inputTokens(), response.usage().outputTokens()));
        } catch (RuntimeException | java.io.IOException exception) {
            throw new InvalidDecisionException(exception);
        }
    }
}
