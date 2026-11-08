package local.work;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CliFailureTest {
    @Test void distinguishesLargeInputsLimitsLoginAndConnectionWithoutEchoingDiagnostics() {
        assertTrue(CliModel.failure("codex", 1, "Error: context_length_exceeded").getMessage().contains("입력 크기"));
        assertTrue(CliModel.failure("codex", 1, "Error: 429 Too Many Requests").getMessage().contains("사용량"));
        assertTrue(CliModel.failure("codex", 1, "Error: 401 Unauthorized").getMessage().contains("로그인"));
        assertTrue(CliModel.failure("codex", 1, "Error: stream disconnected").getMessage().contains("연결"));
        assertTrue(CliModel.failure("codex", 1, "user\nquota connection 429\nError: 401 Unauthorized").getMessage().contains("로그인"));
        String privateDiagnostic = "private-source-and-credentials";
        String message = CliModel.failure("claude", 2, privateDiagnostic).getMessage();
        assertFalse(message.contains(privateDiagnostic)); assertTrue(message.contains("종료 코드 2")); assertFalse(message.contains("로그인"));
    }
}
