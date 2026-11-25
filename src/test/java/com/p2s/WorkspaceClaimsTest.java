package com.p2s;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.agent.vfs.VfsException;
import com.p2s.agent.core.AbortSignal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceClaimsTest {
    @Test
    void overlappingClaimsAreAtomicAndRespectProjectAndOwner() throws Exception {
        WorkspaceClaims claims = new WorkspaceClaims();
        List<String> files = WorkspaceClaims.parse(JsonParser.parseString("[\"/workspace/a\",\"workspace/./a\"]"));
        assertEquals(List.of("workspace/a"), files);
        assertThrows(VfsException.class, () -> WorkspaceClaims.parse(JsonParser.parseString("[\"skills/a\"]")));
        claims.reserve("p", "worker", files);
        assertThrows(VfsException.class, () -> claims.reserve("p", "other", List.of("workspace/b", "workspace/a")));
        claims.reserve("p", "third", List.of("workspace/b"));
        JsonObject path = new JsonObject();
        path.addProperty("path", "a");
        for (String tool : List.of("create_workspace_file", "save_workspace_file", "stage_workspace_file", "delete_workspace_file", "propose_patch")) {
            assertThrows(VfsException.class, () -> claims.checkMutation("p", "", tool, path, ""));
            claims.checkMutation("p", "worker", tool, path, "");
            claims.checkMutation("different-project", "", tool, path, "");
        }
        path.addProperty("path", "unclaimed");
        path.addProperty("new_path", "/workspace/a");
        assertThrows(VfsException.class, () -> claims.checkMutation("p", "", "rename_workspace_file", path, ""));
        claims.release("p", "other", files);
        assertThrows(VfsException.class, () -> claims.checkMutation("p", "", "rename_workspace_file", path, ""));
        claims.release("p", "worker", files);
        claims.checkMutation("p", "", "rename_workspace_file", path, "");
    }

    @Test
    void newWorkspaceBoundsUseWorldOriginAndCheckAllThreeAxesWithoutOverflow() {
        var project = new ProjectPersistence.ProjectRecord();
        project.boundsOrigin = vec(-10, 5, 60);
        project.boundsSize = vec(10, 2, 40);
        var file = new ProjectPersistence.WorkspaceFileRecord();
        WorkspaceTomlCodec.validateNewFileBounds(file, project);
        file.size = vec(1, 1, 1);
        WorkspaceTomlCodec.validateNewFileBounds(file, project);
        file.origin = vec(-1, 6, 99);
        WorkspaceTomlCodec.validateNewFileBounds(file, project);
        file.size = vec(Integer.MAX_VALUE, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTomlCodec.validateNewFileBounds(file, project));
        file.size = vec(1, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTomlCodec.validateNewFileBounds(file, project));
        file.size = vec(1, 1, 2);
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTomlCodec.validateNewFileBounds(file, project));
        file.origin = vec(-11, 5, 60);
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTomlCodec.validateNewFileBounds(file, project));
    }

    @Test
    void waitingRespectsCompletionTimeoutAndStop() throws Exception {
        Object monitor = new Object();
        assertTrue(SubagentManager.awaitCompletion(monitor, () -> false, 100, AbortSignal.none()));
        assertFalse(SubagentManager.awaitCompletion(monitor, () -> true, 1, AbortSignal.none()));
        var signal = AbortSignal.create();
        var entered = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var waiter = executor.submit(() -> assertThrows(AbortSignal.AbortedException.class,
                    () -> SubagentManager.awaitCompletion(monitor, () -> {
                        entered.countDown();
                        return true;
                    }, 30_000, signal)));
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            signal.abort("Stop");
            waiter.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static ProjectPersistence.Vec3Data vec(int x, int y, int z) {
        var v = new ProjectPersistence.Vec3Data();
        v.x = x;
        v.y = y;
        v.z = z;
        return v;
    }
}
