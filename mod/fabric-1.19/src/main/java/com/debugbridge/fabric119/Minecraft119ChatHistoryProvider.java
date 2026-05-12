package com.debugbridge.fabric119;

import com.debugbridge.core.chat.ChatHistoryProvider;
import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.protocol.dto.ChatMessageDto;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Minecraft119ChatHistoryProvider implements ChatHistoryProvider {
    
    private static volatile Field allMessagesField;
    private static volatile Method messageGetter;
    private static volatile Field addedTimeField;
    
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
    
    private static Method messageGetter(Class<?> cls, MappingResolver resolver) throws NoSuchMethodException {
        Method m = messageGetter;
        if (m != null && m.getDeclaringClass() == cls) return m;
        String mojangCls = resolver.unresolveClass(cls.getName());
        String runtime = resolver.resolveMethod(mojangCls != null ? mojangCls : cls.getName(),
                "getMessage", null);
        m = cls.getMethod(runtime);
        messageGetter = m;
        return m;
    }
    
    private static Field addedTimeField(Class<?> cls, MappingResolver resolver) {
        Field f = addedTimeField;
        if (f != null && f.getDeclaringClass() == cls) return f;
        String mojangCls = resolver.unresolveClass(cls.getName());
        String runtime = resolver.resolveField(mojangCls != null ? mojangCls : cls.getName(),
                "addedTime");
        try {
            f = cls.getDeclaredField(runtime);
            f.setAccessible(true);
            addedTimeField = f;
            return f;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }
    
    @Override
    public List<ChatMessageDto> getRecentMessages(int limit, MappingResolver resolver, boolean includeJson) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return Collections.emptyList();
        ChatComponent chat = mc.gui.getChat();
        if (chat == null) return Collections.emptyList();
        
        @SuppressWarnings("unchecked")
        List<Object> messages = (List<Object>) allMessagesField(resolver).get(chat);
        if (messages == null) return Collections.emptyList();
        
        int n = Math.min(limit, messages.size());
        List<ChatMessageDto> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Object msg = messages.get(i);
            ChatMessageDto dto = new ChatMessageDto();
            Object content = messageGetter(msg.getClass(), resolver).invoke(msg);
            dto.plain = content instanceof Component c ? c.getString() : String.valueOf(content);
            Field timeF = addedTimeField(msg.getClass(), resolver);
            if (timeF != null) {
                dto.addedTime = timeF.getInt(msg);
            }
            if (includeJson && content instanceof Component c) {
                try {
                    String jsonStr = Component.Serializer.toJson(c);
                    dto.json = JsonParser.parseString(jsonStr);
                } catch (Exception ignore) {
                    // Skip json field on serialization failure; plain is still there.
                }
            }
            out.add(dto);
        }
        return out;
    }
}
