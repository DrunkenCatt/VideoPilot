package com.videopilot.infrastructure.agent;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
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
                expectedFacts is a checklist of answer requirements, not one entry per requested item.
                If the goal asks for a list, combine the complete list into one fact. Never output more than 3 entries.
                For terminal actions, query must be empty and expectedFacts must be empty.
                RETRIEVE template: {"schemaVersion":1,"action":"RETRIEVE","query":"focused query","expectedFacts":["fact"]}
                NEEDS_CLARIFICATION template: {"schemaVersion":1,"action":"NEEDS_CLARIFICATION","query":"","expectedFacts":[]}
                REFUSE template: {"schemaVersion":1,"action":"REFUSE","query":"","expectedFacts":[]}
                Include every template key, add no other keys, and do not use markdown fences.
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
            throw invalidDecision(exception);
        }
    }

    @Override
    public CritiqueResponse critique(CritiqueRequest request) {
        String expectedFacts;
        String answerClaims;
        String answerMissingRequirements;
        String evidenceSummaries;
        try {
            expectedFacts = json.writeValueAsString(request.expectedFacts());
            answerClaims = json.writeValueAsString(request.answerClaims());
            answerMissingRequirements = json.writeValueAsString(request.answerMissingRequirements());
            evidenceSummaries = json.writeValueAsString(request.evidenceSummaries());
        } catch (java.io.IOException exception) {
            throw new InvalidDecisionException(exception);
        }
        String prompt = """
                Judge whether the verified answer covers the teaching-video goal and is semantically relevant.
                Return exactly one JSON object with schemaVersion, action, query, startMs, endMs,
                missingRequirements, unsupportedClaims.
                PASS is allowed only when ANSWER_STATUS is ANSWERED and all expected facts are covered.
                A REFUSED answer cannot PASS.
                PASS must have empty repair fields. RETRIEVE needs one focused query and missing requirements.
                EXPAND_WINDOW needs a non-negative [startMs,endMs] and missing requirements.
                When REPAIR_ALLOWED is true, an incomplete answer must choose RETRIEVE or EXPAND_WINDOW.
                When REPAIR_ALLOWED is false, do not request another repair: choose PARTIAL when at least one
                verified claim answers part of the goal, or REFUSE when no verified claim can be published.
                PARTIAL and REFUSE require missingRequirements and empty query/startMs/endMs.
                Choose EXPAND_WINDOW when the missing requirement is adjacent temporal context around the
                current timestamped evidence. Choose RETRIEVE when the missing topic may be elsewhere in the video.
                missingRequirements and unsupportedClaims each allow at most 3 entries.
                Each missing requirement is at most 72 characters; each unsupported claim is at most 120 characters.
                PASS template: {"schemaVersion":1,"action":"PASS","query":"","startMs":null,"endMs":null,"missingRequirements":[],"unsupportedClaims":[]}
                RETRIEVE template: {"schemaVersion":1,"action":"RETRIEVE","query":"focused query","startMs":null,"endMs":null,"missingRequirements":["missing fact"],"unsupportedClaims":[]}
                EXPAND_WINDOW template: {"schemaVersion":1,"action":"EXPAND_WINDOW","query":"","startMs":0,"endMs":1000,"missingRequirements":["missing context"],"unsupportedClaims":[]}
                PARTIAL template: {"schemaVersion":1,"action":"PARTIAL","query":"","startMs":null,"endMs":null,"missingRequirements":["unconfirmed part"],"unsupportedClaims":[]}
                REFUSE template: {"schemaVersion":1,"action":"REFUSE","query":"","startMs":null,"endMs":null,"missingRequirements":["unsupported goal"],"unsupportedClaims":[]}
                Include every template key, add no other keys, use null exactly as shown, and do not use markdown fences.
                Do not judge citation existence or override the deterministic evidence gate.
                GOAL: %s
                EXPECTED_FACTS: %s
                ANSWER_STATUS: %s
                VERIFIED_ANSWER_CLAIMS: %s
                ANSWER_DECLARED_GAPS: %s
                REPAIR_ALLOWED: %s
                EVIDENCE_SUMMARIES: %s
                """.formatted(request.goal(), expectedFacts, request.answerStatus(), answerClaims,
                        answerMissingRequirements, request.repairAllowed(), evidenceSummaries);
        LanguageModelPort.Response response = model.generate(
                new LanguageModelPort.Request(prompt, request.inputFingerprint()));
        try {
            Critique critique = json.readerFor(Critique.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(response.candidateText());
            return new CritiqueResponse(critique, new Usage(
                    response.usage().inputTokens(), response.usage().outputTokens()));
        } catch (RuntimeException | java.io.IOException exception) {
            throw invalidDecision(exception);
        }
    }

    private InvalidDecisionException invalidDecision(Exception exception) {
        String code = switch (exception) {
            case JsonParseException ignored -> "MALFORMED_JSON";
            case UnrecognizedPropertyException ignored -> "UNKNOWN_FIELD";
            case InvalidFormatException ignored -> "INVALID_VALUE";
            case ValueInstantiationException ignored -> "CONSTRAINT_VIOLATION";
            case MismatchedInputException ignored -> "INVALID_SHAPE";
            default -> "INVALID_DECISION";
        };
        return new InvalidDecisionException(code, exception);
    }
}
