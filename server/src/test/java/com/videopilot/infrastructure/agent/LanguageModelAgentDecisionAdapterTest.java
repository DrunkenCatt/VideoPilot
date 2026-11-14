package com.videopilot.infrastructure.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.agent.port.AgentDecisionPort;
import com.videopilot.answer.port.LanguageModelException;
import com.videopilot.answer.port.LanguageModelPort;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class LanguageModelAgentDecisionAdapterTest {

    private final LanguageModelPort model = mock(LanguageModelPort.class);
    private final LanguageModelAgentDecisionAdapter adapter =
            new LanguageModelAgentDecisionAdapter(model, new ObjectMapper());

    @Test
    void strictlyParsesDifferentPlannerQueriesAndExpectedFacts() {
        when(model.generate(any())).thenReturn(
                new LanguageModelPort.Response("""
                        {"schemaVersion":1,"action":"RETRIEVE","query":"String 定义和用途",
                         "expectedFacts":["定义","使用场景"]}
                        """, new LanguageModelPort.Usage(60, 15)),
                new LanguageModelPort.Response("""
                        {"schemaVersion":1,"action":"RETRIEVE","query":"help 命令的调用方式",
                         "expectedFacts":["命令格式"]}
                        """, new LanguageModelPort.Usage(50, 12)));

        AgentDecisionPort.PlanResponse response = adapter.plan(request("解释 String"));
        AgentDecisionPort.PlanResponse second = adapter.plan(request("怎样获得帮助"));

        assertThat(response.plan().query()).isEqualTo("String 定义和用途");
        assertThat(response.plan().expectedFacts()).containsExactly("定义", "使用场景");
        assertThat(response.usage()).isEqualTo(new AgentDecisionPort.Usage(60, 15));
        assertThat(second.plan().query()).isEqualTo("help 命令的调用方式");
        assertThat(second.plan().expectedFacts()).containsExactly("命令格式");
    }

    @Test
    void rejectsInvalidJsonUnknownActionsExtraAuthorityAndLongQueries() {
        assertInvalid("not-json", "MALFORMED_JSON");
        assertInvalid("""
                {"schemaVersion":1,"action":"OPEN_URL","query":"x","expectedFacts":["x"]}
                """, "INVALID_VALUE");
        assertInvalid("""
                {"schemaVersion":1,"action":"RETRIEVE","query":"x","expectedFacts":["x"],
                 "ownerId":"00000000-0000-0000-0000-000000000000"}
                """, "UNKNOWN_FIELD");
        assertInvalid("""
                {"schemaVersion":1,"action":"RETRIEVE","query":"%s","expectedFacts":["x"]}
                """.formatted("q".repeat(201)), "CONSTRAINT_VIOLATION");
        assertInvalid("""
                {"schemaVersion":1,"action":"RETRIEVE","query":"https://example.com/private",
                 "expectedFacts":["x"]}
                """, "CONSTRAINT_VIOLATION");
    }

    @Test
    void strictlyParsesPassRetrievalAndWindowCritiques() {
        when(model.generate(any())).thenReturn(
                new LanguageModelPort.Response("""
                        {"schemaVersion":1,"action":"PASS","query":"","startMs":null,"endMs":null,
                         "missingRequirements":[],"unsupportedClaims":[]}
                        """, new LanguageModelPort.Usage(30, 5)),
                new LanguageModelPort.Response("""
                        {"schemaVersion":1,"action":"RETRIEVE","query":"String 使用场景",
                         "startMs":null,"endMs":null,"missingRequirements":["使用场景"],
                         "unsupportedClaims":[]}
                        """, new LanguageModelPort.Usage(35, 8)),
                new LanguageModelPort.Response("""
                        {"schemaVersion":1,"action":"EXPAND_WINDOW","query":"","startMs":1000,
                         "endMs":9000,"missingRequirements":["上下文"],"unsupportedClaims":["结论过宽"]}
                        """, new LanguageModelPort.Usage(40, 10)));

        assertThat(adapter.critique(critiqueRequest()).critique().action())
                .isEqualTo(AgentDecisionPort.CritiqueAction.PASS);
        assertThat(adapter.critique(critiqueRequest()).critique().query()).isEqualTo("String 使用场景");
        assertThat(adapter.critique(critiqueRequest()).critique().startMs()).isEqualTo(1000);
    }

    @Test
    void promptsSpecifyExactNullAndEmptyFieldsForEveryDecisionShape() {
        when(model.generate(any())).thenReturn(
                new LanguageModelPort.Response("""
                        {"schemaVersion":1,"action":"RETRIEVE","query":"String",
                         "expectedFacts":["定义"]}
                        """, new LanguageModelPort.Usage(10, 5)),
                new LanguageModelPort.Response("""
                        {"schemaVersion":1,"action":"PASS","query":"","startMs":null,
                         "endMs":null,"missingRequirements":[],"unsupportedClaims":[]}
                        """, new LanguageModelPort.Usage(10, 5)));

        adapter.plan(request("解释 String"));
        adapter.critique(critiqueRequest());

        ArgumentCaptor<LanguageModelPort.Request> requests =
                ArgumentCaptor.forClass(LanguageModelPort.Request.class);
        verify(model, times(2)).generate(requests.capture());
        assertThat(requests.getAllValues().get(0).prompt())
                .contains("RETRIEVE template", "NEEDS_CLARIFICATION template", "REFUSE template",
                        "combine the complete list into one fact", "Never output more than 3 entries");
        assertThat(requests.getAllValues().get(1).prompt())
                .contains("PASS template", "\"startMs\":null", "RETRIEVE template",
                        "EXPAND_WINDOW template", "ANSWER_STATUS: REFUSED",
                        "A REFUSED answer cannot PASS", "at most 3 entries",
                        "missing requirement is at most 72 characters",
                        "adjacent temporal context", "missing topic may be elsewhere");
    }

    @Test
    void propagatesCriticProviderTimeoutInsteadOfMisclassifyingItAsInvalidOutput() {
        LanguageModelException timeout = new LanguageModelException(
                LanguageModelException.Reason.UNKNOWN_OUTCOME, "TIMEOUT");
        when(model.generate(any())).thenThrow(timeout);

        assertThatThrownBy(() -> adapter.critique(critiqueRequest()))
                .isSameAs(timeout);
    }

    @Test
    void rejectsCritiquesThatTryToPassWithGapsOrRequestIncompleteRepairs() {
        assertThatThrownBy(() -> new AgentDecisionPort.Critique(
                1, AgentDecisionPort.CritiqueAction.PASS, "", null, null,
                java.util.List.of("still missing"), java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentDecisionPort.Critique(
                1, AgentDecisionPort.CritiqueAction.RETRIEVE, "", null, null,
                java.util.List.of("missing"), java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentDecisionPort.Critique(
                1, AgentDecisionPort.CritiqueAction.EXPAND_WINDOW, "", 9000L, 1000L,
                java.util.List.of("context"), java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classifiesCriticJsonShapeAndConstraintFailuresWithoutPersistingContent() {
        assertInvalidCritique("not-json", "MALFORMED_JSON");
        assertInvalidCritique("""
                {"schemaVersion":1,"action":"RETRIEVE","query":"String", "startMs":null,
                 "endMs":null,"missingRequirements":["%s"],"unsupportedClaims":[]}
                """.formatted("x".repeat(73)), "CONSTRAINT_VIOLATION");
        assertInvalidCritique("""
                {"schemaVersion":1,"action":"PASS","query":"","startMs":null,"endMs":null,
                 "missingRequirements":[],"unsupportedClaims":[],"reason":"hidden"}
                """, "UNKNOWN_FIELD");
    }

    private void assertInvalid(String candidate, String expectedCode) {
        when(model.generate(any())).thenReturn(new LanguageModelPort.Response(
                candidate, new LanguageModelPort.Usage(10, 5)));
        assertThatThrownBy(() -> adapter.plan(request("目标")))
                .isInstanceOfSatisfying(AgentDecisionPort.InvalidDecisionException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expectedCode));
    }

    private void assertInvalidCritique(String candidate, String expectedCode) {
        when(model.generate(any())).thenReturn(new LanguageModelPort.Response(
                candidate, new LanguageModelPort.Usage(10, 5)));
        assertThatThrownBy(() -> adapter.critique(critiqueRequest()))
                .isInstanceOfSatisfying(AgentDecisionPort.InvalidDecisionException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expectedCode));
    }

    private AgentDecisionPort.PlanRequest request(String goal) {
        return new AgentDecisionPort.PlanRequest(
                UUID.randomUUID(), UUID.randomUUID(), null, null, goal,
                "model-v1", "planner-v1", "a".repeat(64));
    }

    private AgentDecisionPort.CritiqueRequest critiqueRequest() {
        return new AgentDecisionPort.CritiqueRequest(
                UUID.randomUUID(), UUID.randomUUID(), null, null, "解释 String",
                java.util.List.of("定义", "使用场景"), "REFUSED", java.util.List.of(),
                java.util.List.of("ASR 0-1000: String 是字符串"), "model-v1", "critic-v1",
                "b".repeat(64));
    }
}
