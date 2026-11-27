package com.p2s.theme;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ThemeCatalogTest {
    @TempDir Path directory;

    private final ThemeCatalog catalog = ThemeCatalog.fromResources();
    private final List<String> errors = new ArrayList<>();

    private Path write(String name, String... entries) throws IOException {
        return Files.writeString(directory.resolve(name), """
                {"version":2,"default":"ember","themes":[%s]}
                """.formatted(String.join(",", entries)));
    }

    private static String theme(String id, String name, String colors) {
        return """
                {"id":"%s","name":"%s","colors":{%s}}
                """.formatted(id, name, colors);
    }

    @Test
    void builtinsExposeEightNamesAndEveryPreviewFieldAsArgb() {
        assertEquals(List.of("余烬", "冰川", "靛夜", "陶笺", "苔庭", "暮藤", "砂纸", "石墨"),
                catalog.available().stream().map(P2STheme::name).toList());
        assertEquals("ember", catalog.current().id());
        assertEquals(35, ThemePack.FIELDS.size());
        for (P2STheme theme : catalog.available()) {
            for (String field : ThemePack.FIELDS) {
                assertDoesNotThrow(() -> { theme.color(field); });
            }
        }
        assertEquals(0xffaac4a3, catalog.current().color("preview-add"));
        assertEquals(0x0bf1eee4, catalog.current().color("hover"));
        assertThrows(IllegalArgumentException.class, () -> catalog.current().color("preview-addition"));
    }

    @Test
    void playerThemesExtendAndOverrideBuiltinsWithDefaultFieldFallback() throws Exception {
        write("a.json", theme("ember", "自定余烬", "\"text\":\"#112233\""),
                theme("glacier", "自定冰川", "\"accent\":\"#aBcDeF12\""));
        write("b.json", theme("my-theme", "我的主题", "\"accent\":\"#00000100\""));
        catalog.reload(directory, "glacier", errors::add);

        assertEquals("自定冰川", catalog.current().name());
        assertEquals(0x12abcdef, catalog.current().color("accent"));
        assertEquals(0xff112233, catalog.current().color("text"));
        assertEquals(0xff2b322e, catalog.current().color("panel"));
        assertEquals(9, catalog.available().size());
        assertTrue(catalog.select("my-theme"));
        assertEquals(0x00000001, catalog.current().color("accent"));
        assertEquals(0xff112233, catalog.current().color("text"));
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void invalidFieldSkipsEntireFileKeepsLastGoodSelectionAndCanBeCorrected() throws Exception {
        String original = theme("dusk", "Dusk", "\"panel\":\"#554433\"");
        write("player.json", original);
        catalog.reload(directory, "dusk", errors::add);
        var observed = new ArrayList<Integer>();
        catalog.addListener(() -> observed.add(catalog.current().color("panel")));

        write("player.json", theme("leak", "Incomplete import", ""), theme("dusk", "Dusk", "\"panel\":\"#543\""));
        write("other.json", theme("valid", "Valid", ""));
        catalog.reload(directory, "dusk", errors::add);

        assertFalse(catalog.select("leak"));
        assertTrue(catalog.available().stream().anyMatch(t -> t.id().equals("valid")));
        assertEquals("dusk", catalog.current().id());
        assertEquals(0xff554433, catalog.current().color("panel"));
        assertTrue(observed.isEmpty());
        assertTrue(errors.getFirst().contains("player.json [themes[1].colors.panel]"), errors.toString());

        write("player.json", theme("dusk", "Dusk again", "\"panel\":\"#010203\""));
        catalog.reload(directory, "dusk", errors::add);
        assertEquals(List.of(0xff010203), observed);
        assertEquals("Dusk again", catalog.current().name());
    }

    @Test
    void malformedPackAndInvalidTypesReportFileAndOffendingField() throws Exception {
        Files.writeString(directory.resolve("syntax.json"), "{invalid: ");
        Files.writeString(directory.resolve("version.json"), "{\"version\":1,\"themes\":[]}");
        write("typo.json", theme("x", "X", "\"fonttt\":\"#123456\""));
        write("numeric.json", theme("x", "X", "\"info\":17"));
        write("duplicate.json", theme("dup", "First", ""), theme("dup", "Second", ""));
        catalog.reload(directory, "glacier", errors::add);

        assertEquals(8, catalog.available().size());
        assertEquals("glacier", catalog.current().id());
        assertEquals(5, errors.size(), errors.toString());
        for (String prefix : List.of("syntax.json [$]", "version.json [version]",
                "typo.json [themes[0].colors.fonttt]", "numeric.json [themes[0].colors.info]",
                "duplicate.json [themes[1].id]")) {
            assertTrue(errors.stream().anyMatch(e -> e.startsWith(prefix)), prefix + " in " + errors);
        }
    }

    @Test
    void switchingAndReloadingNotifyOnlyAfterAnEffectiveChange() {
        AtomicInteger calls = new AtomicInteger();
        Runnable listener = () -> {
            assertEquals("clay", catalog.current().id());
            calls.incrementAndGet();
        };
        catalog.addListener(listener);
        catalog.addListener(listener);
        assertFalse(catalog.select("absent"));
        assertTrue(catalog.select("clay"));
        assertTrue(catalog.select("clay"));
        catalog.reload(directory, "clay", errors::add);
        assertEquals(1, calls.get());
        catalog.removeListener(listener);
        catalog.reload(directory, "", errors::add);
        assertEquals("ember", catalog.current().id());
        assertEquals(1, calls.get());
    }

    @Test
    void removedFilesDisappearAndMissingSavedIdRetainsAnAvailableSelection() throws Exception {
        Path file = write("removed.json", theme("temporary", "Temporary", ""));
        catalog.reload(directory, "temporary", errors::add);
        Files.delete(file);
        catalog.reload(directory, "temporary", errors::add);
        assertFalse(catalog.select("temporary"));
        assertEquals("ember", catalog.current().id());
        assertTrue(errors.getFirst().startsWith("p2s_client.json [theme]"));
        assertTrue(catalog.select("indigo"));
        catalog.reload(directory, "missing", errors::add);
        assertEquals("indigo", catalog.current().id());
    }

    @Test
    void invalidThemeDirectoryDoesNotClearInstalledThemes() throws Exception {
        write("valid.json", theme("mine", "Mine", ""));
        catalog.reload(directory, "mine", errors::add);
        Path notDirectory = Files.createFile(directory.resolve("file"));
        catalog.reload(notDirectory, "mine", errors::add);
        assertEquals("mine", catalog.current().id());
        assertTrue(errors.getFirst().startsWith("file [$]"), errors.toString());
    }
}
