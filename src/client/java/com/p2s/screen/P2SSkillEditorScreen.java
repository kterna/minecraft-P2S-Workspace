package com.p2s.screen;

import com.p2s.screen.widget.P2SFlatButton;
import com.p2s.screen.widget.P2SThemedEditBox;
import com.p2s.screen.widget.ThemePaint;

import static com.p2s.screen.widget.ThemePaint.color;

import com.p2s.P2SI18n;
import com.p2s.compat.P2SGfx;
import com.p2s.compat.P2SScreenBase;
import com.p2s.compat.P2SWidgetCompat;
import com.p2s.screen.widget.P2SMultiLineTextEditor;
import com.p2s.store.SkillStore;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class P2SSkillEditorScreen extends P2SScreenBase {
    public enum Mode {
        CREATE,
        EDIT,
        RENAME
    }

    private final Screen parent;
    private final Mode mode;
    private final String skillId;

    private EditBox nameInput;
    private EditBox descInput;
    private P2SMultiLineTextEditor bodyEditor;

    private String originalBody = "";
    private Component statusText = Component.empty();
    private String statusColor = "muted";

    public P2SSkillEditorScreen(Screen parent, Mode mode, String skillId) {
        super(switch (mode == null ? Mode.CREATE : mode) {
            case CREATE -> P2SI18n.tr("screen.p2s.skill_editor.title.create");
            case RENAME -> P2SI18n.tr("screen.p2s.skill_editor.title.rename");
            case EDIT -> P2SI18n.tr("screen.p2s.skill_editor.title.edit");
        });
        this.parent = parent;
        this.mode = mode == null ? Mode.CREATE : mode;
        this.skillId = skillId == null ? "" : skillId;
    }

    @Override
    protected void init() {
        super.init();
        loadInitialState();

        int panelWidth = Math.min(860, this.width - 24);
        int left = (this.width - panelWidth) / 2;
        int top = Math.max(14, this.height / 10);

        nameInput = new P2SThemedEditBox(this.font, left + 10, top + 26, panelWidth - 20, 20, P2SI18n.tr("screen.p2s.skill_editor.name"));
        nameInput.setMaxLength(128);
        addRenderableWidget(nameInput);

        descInput = new P2SThemedEditBox(this.font, left + 10, top + 56, panelWidth - 20, 20, P2SI18n.tr("screen.p2s.skill_editor.description"));
        descInput.setMaxLength(256);
        addRenderableWidget(descInput);

        nameInput.setValue(extractName());
        descInput.setValue(extractDescription());

        int bodyTop = top + 94;
        int bodyWidth = panelWidth - 20;
        int buttonY;
        if (mode != Mode.RENAME) {
            int bodyHeight = Math.max(72, this.height - bodyTop - 44);
            bodyEditor = new P2SMultiLineTextEditor(this.font);
            bodyEditor.setMaxLength(65536);
            bodyEditor.setBounds(left + 10, bodyTop, bodyWidth, bodyHeight);
            bodyEditor.setText(originalBody);
            buttonY = bodyTop + bodyHeight + 10;
        } else {
            bodyEditor = null;
            buttonY = top + 104;
        }

        addRenderableWidget(P2SFlatButton.themed(P2SI18n.tr("screen.p2s.common.save"), btn -> saveSkill())
                .bounds(left + 10, buttonY, 80, 20).build());
        addRenderableWidget(P2SFlatButton.themed(P2SI18n.tr("screen.p2s.common.cancel"), btn -> onClose())
                .bounds(left + 98, buttonY, 90, 20).build());
    }

    private void loadInitialState() {
        originalBody = "";
        if (mode == Mode.CREATE) {
            return;
        }
        SkillStore.SkillDocument doc = SkillStore.readSkill(skillId);
        if (doc == null) {
            return;
        }
        if (doc.body() != null) {
            originalBody = doc.body();
        }
        cachedName = doc.meta().name();
        cachedDescription = doc.meta().description();
    }

    private String cachedName = "";
    private String cachedDescription = "";

    private String extractName() {
        if (cachedName == null || cachedName.isBlank()) {
            if (mode == Mode.CREATE) {
                return "";
            }
            return skillId;
        }
        return cachedName;
    }

    private String extractDescription() {
        return cachedDescription == null ? "" : cachedDescription;
    }

    private String buildBodyText() {
        if (bodyEditor == null) {
            return "";
        }
        String[] lines = bodyEditor.getText().replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int end = lines.length;
        while (end > 0 && (lines[end - 1] == null || lines[end - 1].isBlank())) {
            end -= 1;
        }
        if (end <= 0) {
            return "";
        }
        return String.join("\n", java.util.Arrays.copyOf(lines, end));
    }

    private void saveSkill() {
        String name = nameInput == null ? "" : nameInput.getValue();
        String desc = descInput == null ? "" : descInput.getValue();
        if (name == null || name.isBlank()) {
            statusText = P2SI18n.tr("screen.p2s.skill_editor.status.name_required");
            statusColor = "danger";
            return;
        }

        try {
            SkillStore.SkillDocument saved;
            if (mode == Mode.CREATE) {
                String body = buildBodyText();
                saved = SkillStore.createSkill(name, desc, body);
            } else if (mode == Mode.EDIT) {
                String body = buildBodyText();
                saved = SkillStore.updateSkill(skillId, name, desc, body);
            } else {
                SkillStore.SkillDocument renamed = SkillStore.renameSkill(skillId, name);
                if (renamed == null) {
                    saved = null;
                } else {
                    saved = SkillStore.updateSkill(renamed.meta().id(), name, desc, originalBody);
                }
            }
            if (saved == null) {
                statusText = P2SI18n.tr("screen.p2s.skill_editor.status.save_failed");
                statusColor = "danger";
                return;
            }
            if (parent instanceof P2SConfigScreen cfg) {
                cfg.onSkillSaved(saved.meta().id());
            }
            if (this.minecraft != null) {
                com.p2s.compat.P2SClientLifecycleCompat.setScreen(this.minecraft, parent);
            }
        } catch (Exception e) {
            statusText = P2SI18n.tr("screen.p2s.skill_editor.status.save_failed_with_reason", e.getMessage());
            statusColor = "danger";
        }
    }

    @Override
    public boolean p2sKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (P2SWidgetCompat.keyPressed(nameInput, keyCode, scanCode, modifiers)) {
            return true;
        }
        if (P2SWidgetCompat.keyPressed(descInput, keyCode, scanCode, modifiers)) {
            return true;
        }
        if (bodyEditor != null && bodyEditor.keyPressed(keyCode, modifiers)) {
            return true;
        }
        return p2sSuperKeyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean p2sCharTyped(char codePoint, int modifiers) {
        if (P2SWidgetCompat.charTyped(nameInput, codePoint, modifiers)) {
            return true;
        }
        if (P2SWidgetCompat.charTyped(descInput, codePoint, modifiers)) {
            return true;
        }
        if (bodyEditor != null && bodyEditor.charTyped(codePoint, modifiers)) {
            return true;
        }
        return p2sSuperCharTyped(codePoint, modifiers);
    }

    @Override
    public boolean p2sMouseClicked(double mouseX, double mouseY, int button) {
        if (bodyEditor != null && bodyEditor.mouseClicked(mouseX, mouseY, button)) {
            if (nameInput != null) nameInput.setFocused(false);
            if (descInput != null) descInput.setFocused(false);
            return true;
        }
        if (button == 0 && bodyEditor != null) {
            bodyEditor.setFocused(false);
        }
        return p2sSuperMouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean p2sMouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (bodyEditor != null && bodyEditor.mouseDragged(mouseX, mouseY, button)) {
            return true;
        }
        return p2sSuperMouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean p2sMouseReleased(double mouseX, double mouseY, int button) {
        if (bodyEditor != null && bodyEditor.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return p2sSuperMouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean p2sMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (bodyEditor != null && bodyEditor.mouseScrolled(mouseX, mouseY, verticalAmount)) {
            return true;
        }
        return p2sSuperMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void p2sRenderBackground(P2SGfx gfx, int mouseX, int mouseY, float delta) {
        gfx.fill(0, 0, this.width, this.height, color("backdrop"));
    }

    @Override
    public void p2sRender(P2SGfx gfx, int mouseX, int mouseY, float delta) {
        p2sSuperRender(gfx, mouseX, mouseY, delta);

        int panelWidth = Math.min(860, this.width - 24);
        int left = (this.width - panelWidth) / 2;
        int top = Math.max(14, this.height / 10);

        ThemePaint.text(gfx, this.font, this.title, left + 10, top + 8, color("text"), true);
        ThemePaint.text(gfx, this.font, P2SI18n.tr("screen.p2s.skill_editor.name"), left + 10, top + 16, color("muted"), false);
        ThemePaint.text(gfx, this.font, P2SI18n.tr("screen.p2s.skill_editor.description"), left + 10, top + 46, color("muted"), false);
        if (mode != Mode.RENAME) {
            if (bodyEditor != null) {
                bodyEditor.render(gfx);
            }
            ThemePaint.text(gfx, this.font, P2SI18n.tr("screen.p2s.skill_editor.body"), left + 10, top + 84, color("muted"), false);
        } else {
            ThemePaint.text(gfx, this.font, P2SI18n.tr("screen.p2s.skill_editor.rename_only"), left + 10, top + 84, color("muted"), false);
        }
        if (statusText != null && !statusText.getString().isBlank()) {
            ThemePaint.text(gfx, this.font, statusText, left + 10, top + this.height / 2, color(statusColor), false);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            com.p2s.compat.P2SClientLifecycleCompat.setScreen(this.minecraft, parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
