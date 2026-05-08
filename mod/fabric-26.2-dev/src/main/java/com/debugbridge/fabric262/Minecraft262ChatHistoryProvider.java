package com.debugbridge.fabric262;

import com.debugbridge.core.chat.ChatHistoryProvider;
import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.protocol.dto.ChatMessageDto;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.ComponentSerialization;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Minecraft262ChatHistoryProvider implements ChatHistoryProvider {

    private static volatile Field allMessagesField;

    private static Field allMessagesField(MappingResolver resolver) throws NoSuchFieldException {
        Field f = allMessagesField;
        if (f != null) return f;
        String runtime = resolver.resolveField(
                "net.minecraft.client.gui.components.ChatComponent", "allMessages");
        f = ChatComponent.class.getDeclaredField(runtime);
        f.setAccessible(true);
        allMessagesField = f;
        return f;
    }

    @Override
    public List<ChatMessageDto> getRecentMessages(int limit, MappingResolver resolver, boolean includeJson) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return Collections.emptyList();
        ChatComponent chat = mc.gui.hud.getChat();
        if (chat == null) return Collections.emptyList();

        @SuppressWarnings("unchecked")
        List<GuiMessage> messages = (List<GuiMessage>) allMessagesField(resolver).get(chat);
        if (messages == null) return Collections.emptyList();

        // ChatComponent stores newest-first; honor that.
        int n = Math.min(limit, messages.size());
        List<ChatMessageDto> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            GuiMessage msg = messages.get(i);
            ChatMessageDto dto = new ChatMessageDto();
            dto.plain = msg.content().getString();
            dto.addedTime = msg.addedTime();
            if (includeJson) {
                try {
                    JsonElement json = ComponentSerialization.CODEC
                            .encodeStart(JsonOps.INSTANCE, msg.content())
                            .getOrThrow();
                    dto.json = json;
                } catch (Exception ignore) {
                    // Plain text is enough when a registry-bound component cannot be serialized.
                }
            }
            out.add(dto);
        }
        return out;
    }
}
