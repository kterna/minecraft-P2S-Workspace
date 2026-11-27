package com.p2s.workbench;

import com.p2s.ClientThemes;
import com.p2s.P2SI18n;
import com.p2s.theme.P2STheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** A picker inside the workbench. Opening it keeps the underlying screen and edit box alive. */
public final class WbThemeMenu {
    private static final int ROW = 14;
    private static final List<String> SWATCHES = List.of("panel", "raised", "accent", "text");
    private final List<P2STheme> themes = P2STheme.available();
    private final Runnable close;
    private final Runnable back;
    private int first, cursor, rows = 1, left, right, top;
    private boolean keyboard;

    public WbThemeMenu(Runnable close, Runnable back) {
        this.close = close;
        this.back = back;
        cursor = Math.max(0, themes.indexOf(P2STheme.current()));
        first = cursor;
    }

    public void render(GuiGraphics gfx, Font font, int anchorRight, int anchorTop, int height, int mx, int my) {
        right = anchorRight;
        left = Math.max(2, right - 142);
        top = anchorTop;
        rows = Math.clamp((height - top - ROW - 4) / ROW, 1, themes.size());
        first = Math.clamp(first, 0, themes.size() - rows);
        int bottom = top + (rows + 1) * ROW + 2;
        P2STheme current = P2STheme.current();
        gfx.pose().pushPose();
        gfx.pose().translate(0, 0, 400);
        gfx.fill(left, top, right, bottom, current.color("line"));
        gfx.fill(left + 1, top + 1, right - 1, bottom - 1, current.color("raised"));
        gfx.drawString(font, "‹ " + P2SI18n.tr("screen.p2s.wb.menu.appearance").getString(),
                left + 5, top + 3, current.color("muted"), false);
        for (int row = 0; row < rows; row++) {
            int index = first + row;
            P2STheme theme = themes.get(index);
            int y = top + 1 + (row + 1) * ROW;
            boolean selected = theme.id().equals(current.id());
            if (selected) gfx.fill(left + 1, y, right - 1, y + ROW, current.color("selected"));
            if (mx > left && mx < right && my >= y && my < y + ROW) {
                gfx.fill(left + 1, y, right - 1, y + ROW, current.color("hover"));
            }
            if (keyboard && cursor == index) gfx.fill(left + 1, y, left + 2, y + ROW, current.color("focus"));
            if (selected) gfx.drawString(font, "✓", left + 4, y + 3, current.color("accent"), false);
            int swatchX = right - 34;
            String name = font.plainSubstrByWidth(theme.name(), Math.max(0, swatchX - left - 17));
            gfx.drawString(font, name, left + 13, y + 3, current.color("text"), false);
            gfx.fill(swatchX - 1, y + 3, swatchX + 25, y + 11, current.color("line"));
            for (String field : SWATCHES) {
                gfx.fill(swatchX, y + 4, swatchX + 6, y + 10, theme.color(field));
                swatchX += 6;
            }
        }
        if (rows < themes.size()) {
            int track = rows * ROW;
            int thumb = Math.max(4, track * rows / themes.size());
            int y = top + ROW + 1 + (track - thumb) * first / (themes.size() - rows);
            gfx.fill(right - 3, y, right - 1, y + thumb, current.color("scrollbar"));
        }
        gfx.pose().popPose();
    }

    public void click(double x, double y) {
        if (x <= left || x >= right || y < top || y >= top + 1 + (rows + 1) * ROW) {
            close.run();
        } else if (y < top + ROW + 1) {
            back.run();
        } else {
            choose(first + (int) ((y - top - ROW - 1) / ROW));
        }
    }

    public void scroll(double delta) {
        first = Math.clamp(first - (int) Math.signum(delta), 0, themes.size() - rows);
        cursor = Math.clamp(cursor, first, first + rows - 1);
        keyboard = false;
    }

    public void keyPressed(int key) {
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_LEFT -> back.run();
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> choose(cursor);
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_HOME, GLFW.GLFW_KEY_END -> {
                cursor = switch (key) {
                    case GLFW.GLFW_KEY_HOME -> 0;
                    case GLFW.GLFW_KEY_END -> themes.size() - 1;
                    default -> Math.clamp(cursor + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1), 0, themes.size() - 1);
                };
                first = Math.clamp(first, cursor - Math.max(1, rows) + 1, cursor);
                keyboard = true;
            }
        }
    }

    private void choose(int index) {
        ClientThemes.select(themes.get(index).id());
        close.run();
    }
}
