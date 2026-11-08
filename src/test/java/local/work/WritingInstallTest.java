package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class WritingInstallTest {
    @TempDir Path temporary;
    String read(Path file) throws Exception { return Files.readString(file, StandardCharsets.UTF_8); }
    void write(Path file, String text) throws Exception { Files.createDirectories(file.getParent()); Files.writeString(file, text, StandardCharsets.UTF_8); }
    record Run(int exit, String output) {}
    Run install(String scope, String... options) throws Exception {
        List<String> command = new ArrayList<>(List.of("pwsh", "-NoProfile", "-File", Path.of("scripts/install-writing.ps1").toAbsolutePath().toString(), "-Scope", scope, "-TargetRoot", temporary.toString())); command.addAll(Arrays.asList(options));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS)); return new Run(process.exitValue(), output);
    }
    @Test void projectInstallKeepsPersonalInstructionsAndSettingsAndIsRepeatable() throws Exception {
        Path agents = temporary.resolve("AGENTS.md"), settings = temporary.resolve(".claude/settings.local.json");
        write(agents, "# 프로젝트 규칙\n사용자 규칙을 유지한다.\n"); write(settings, "{\"outputStyle\":\"personal\",\"permissions\":{\"allow\":[\"Read\"]}}");
        Run first = install("Project", "-ActivateStyle"); assertEquals(0, first.exit(), first.output());
        assertTrue(read(agents).contains("사용자 규칙을 유지한다."));
        assertEquals("work-agent-korean", Json.text(Json.read(read(settings)), "outputStyle")); assertEquals("Read", Json.read(read(settings)).path("permissions").path("allow").get(0).asText());
        String common = read(Path.of("skills/work-agent-writing/common.md"));
        assertEquals(common, read(temporary.resolve(".agents/skills/work-agent-writing/common.md")));
        assertTrue(read(temporary.resolve(".claude/output-styles/work-agent-korean.md")).contains(common));
        assertTrue(read(temporary.resolve(".claude/output-styles/work-agent-korean.md")).contains("keep-coding-instructions: true"));
        assertEquals(0, install("Project", "-ActivateStyle", "-Check").exit());
        Run repeated = install("Project", "-ActivateStyle"); assertEquals(0, repeated.exit()); assertEquals(0, Json.read(repeated.output()).path("changed").asInt());
        assertFalse(Files.exists(temporary.resolve(".codex/AGENTS.md")));
    }
    @Test void userFilesChangedOutsideManagedBlockRemainAndUnknownSkillEditsBlockBeforeWrites() throws Exception {
        assertEquals(0, install("User").exit());
        Path agents = temporary.resolve(".codex/AGENTS.md"), skill = temporary.resolve(".agents/skills/write-message/SKILL.md");
        write(agents, read(agents) + "\n# 추가 규칙\n내 규칙\n");
        assertEquals(0, install("User").exit()); assertTrue(read(agents).contains("내 규칙"));
        String changed = read(skill) + "\n직접 수정한 규칙\n"; write(skill, changed); String before = read(agents);
        assertNotEquals(0, install("User", "-ActivateStyle").exit()); assertEquals(changed, read(skill)); assertEquals(before, read(agents));
        assertFalse(Files.exists(temporary.resolve(".claude/settings.json")));
    }
    @Test void legacyMigrationMovesOnlyOldSkillsAndRetainsFullKoreanRulesInSharedSource() throws Exception {
        String common = read(Path.of("skills/work-agent-writing/common.md"));
        String original = common.substring(0, common.indexOf("# 업무 글의 사실과 근거")).strip();
        String legacy = read(Path.of("src/test/resources/writing/legacy-section.md"));
        Path agents = temporary.resolve(".codex/AGENTS.md"), claude = temporary.resolve(".claude/CLAUDE.md");
        write(agents, "# Personal\n개인 규칙\n\n" + original + "\n\n" + legacy); write(claude, "# Coding\n코딩 규칙\n\n" + legacy);
        write(temporary.resolve("agent-skills/global/polish-message/SKILL.md"), "old message"); write(temporary.resolve(".claude/skills/ct-doc/SKILL.md"), "old document");
        Path alias = temporary.resolve(".agents/skills/polish-message"), oldFolder = temporary.resolve("agent-skills/global/polish-message"); Files.createDirectories(alias.getParent());
        String link = "New-Item -ItemType Junction -Path '" + alias.toString().replace("'", "''") + "' -Target '" + oldFolder.toString().replace("'", "''") + "' | Out-Null";
        Process junction = new ProcessBuilder("pwsh", "-NoProfile", "-Command", link).redirectErrorStream(true).start(); assertTrue(junction.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, junction.exitValue());
        Run migrated = install("User", "-MigrateLegacy", "-ActivateStyle"); assertEquals(0, migrated.exit(), migrated.output());
        assertTrue(read(agents).contains("개인 규칙")); assertFalse(read(agents).contains("polish-message")); assertFalse(read(agents).contains("# 한국어 출력 지침"));
        assertTrue(read(claude).contains("코딩 규칙")); assertFalse(read(claude).contains("polish-message"));
        JsonNode report = Json.read(migrated.output()); Path backup = Path.of(Json.required(report, "backup"));
        assertTrue(read(backup.resolve(".codex/AGENTS.md")).contains(original));
        assertTrue(Files.exists(backup.resolve("agent-skills/global/polish-message/SKILL.md"))); assertFalse(Files.exists(temporary.resolve("agent-skills/global/polish-message")));
        assertEquals("old message", read(backup.resolve("agent-skills/global/polish-message/SKILL.md"))); assertTrue(Files.exists(backup.resolve(".agents/skills/polish-message.link.txt"))); assertFalse(Files.exists(alias));
        assertEquals(0, install("User", "-MigrateLegacy", "-ActivateStyle", "-Check").exit());
    }
}
