package com.p2s.agent.sh;

import com.p2s.agent.core.AbortSignal;
import com.p2s.agent.vfs.MemoryFs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShellTest {
    private final MemoryFs fs = new MemoryFs()
            .put("workspace/house.toml", MemoryFs.sampleToml())
            .put("workspace/notes/a.txt", "beta\nalpha\nbeta\n");

    private Shell.Result sh(String command) {
        return Shell.run(command, fs, AbortSignal.create());
    }

    @Test
    void pipesGrepSortUniq() {
        assertEquals("workspace/house.toml:13:block = \"minecraft:oak_planks\"\n",
                sh("grep -rn oak_planks workspace").stdout());
        assertEquals("      2 beta\n      1 alpha\n", sh("cat workspace/notes/a.txt | sort | uniq -c | sort -rn").stdout());
        assertEquals("1\n", sh("grep -c alpha workspace/notes/a.txt").stdout());
    }

    @Test
    void sedInPlaceIsStagedAfterTheWholeLineSucceeds() {
        var result = sh("sed -i 's/oak_planks/spruce_planks/g' workspace/house.toml && diff workspace/house.toml");
        assertFalse(result.failed(), result.stderr());
        assertEquals(1, result.exitCode(), "diff exits 1 when files differ");
        assertTrue(result.stdout().contains("-block = \"minecraft:oak_planks\""));
        assertTrue(result.stdout().contains("+block = \"minecraft:spruce_planks\""));
        assertEquals(List.of("write workspace/house.toml"), fs.log);
    }

    @Test
    void anyFailureDiscardsAllWrites() {
        var result = sh("sed -i 's/oak/birch/' workspace/house.toml && cat workspace/missing.toml");
        assertTrue(result.failed());
        assertTrue(fs.log.isEmpty());
        assertTrue(result.stderr().contains("no files were changed"));
    }

    @Test
    void invalidWorkspaceTomlIsRejectedBeforeStaging() {
        var result = sh("echo 'not toml at all [[' > workspace/house.toml");
        assertTrue(result.failed());
        assertTrue(fs.log.isEmpty());
    }

    @Test
    void sedAddressesAndGroups() {
        assertEquals("beta\n", sh("sed -n '1p' workspace/notes/a.txt").stdout());
        assertEquals("alpha\nbeta\n", sh("sed '1d' workspace/notes/a.txt").stdout());
        // GNU: the end regex is searched from the next line; without a match the range runs to the end.
        assertEquals("alpha\nbeta\n", sh("sed -n '/alpha/,/alpha/p' workspace/notes/a.txt").stdout());
        assertEquals("ateb\nalpha\nateb\n", sh("sed -E 's/(b)(e)(t)(a)/\\4\\3\\2\\1/' workspace/notes/a.txt").stdout());
        assertEquals("[beta]\n", sh("echo beta | sed 's/.*/[&]/'").stdout());
    }

    @Test
    void unsupportedSyntaxFailsWithAHint() {
        assertTrue(sh("echo $HOME").stderr().contains("variables"));
        assertTrue(sh("cat <<EOF").stderr().contains("here-documents"));
        assertTrue(sh("python3 x.py").stderr().contains("script tool"));
        assertTrue(sh("echo $(ls)").failed());
        assertTrue(sh("echo hi > skills/x/SKILL.md").stderr().contains("Read-only"));
    }

    @Test
    void connectorsAndRedirects() {
        assertEquals("none\n", sh("grep -q zzz workspace/notes/a.txt || echo none").stdout());
        assertEquals("", sh("grep -q zzz workspace/notes/a.txt && echo found").stdout());
        assertEquals("a=1\nb=2\n", sh("printf '%s=%d\\n' a 1 b 2").stdout());
        assertEquals("B,C\n", sh("echo 'a,b,c' | cut -d, -f2,3 | tr a-z A-Z").stdout());
        assertEquals("workspace/house.toml\nworkspace/notes/a.txt\n", sh("find workspace -type f").stdout());
        assertEquals("workspace/house.toml\n", sh("ls workspace/*.toml").stdout());
    }

    @Test
    void stopEndsTheCommandLine() {
        AbortSignal signal = AbortSignal.create();
        signal.abort("stop");
        var result = Shell.run("cat workspace/notes/a.txt; cat workspace/notes/a.txt", fs, signal);
        assertTrue(result.failed());
        assertEquals("", result.stdout());
    }
}
