package com.p2s;

import com.p2s.compat.P2SClientLifecycleCompat;

public final class SelectionInputHandler {
    private SelectionInputHandler() {
    }

    public static void register() {
        P2SClientLifecycleCompat.registerSelectionCallbacks(
                ClientSelectionManager::isSelectionToolHeld,
                ClientSelectionManager::onLeftClick,
                ClientSelectionManager::onRightClick
        );
    }
}
