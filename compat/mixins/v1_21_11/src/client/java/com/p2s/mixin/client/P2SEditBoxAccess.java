package com.p2s.mixin.client;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Read the native editing state when painting a P2S text field. All edits still go through EditBox. */
@Mixin(EditBox.class)
public interface P2SEditBoxAccess {
    @Accessor("displayPos") int p2s$displayPos();
    @Accessor("highlightPos") int p2s$highlightPos();
    @Accessor("focusedTime") long p2s$focusedTime();
    @Accessor("isEditable") boolean p2s$isEditable();
    @Accessor("maxLength") int p2s$maxLength();
    @Invoker("applyFormat") FormattedCharSequence p2s$applyFormat(String text, int offset);
    @Accessor("hint") Component p2s$hint();
    @Accessor("suggestion") String p2s$suggestion();
}
