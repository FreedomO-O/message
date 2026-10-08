package com.freedom.messageprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure data operations shared by the screen and printable document. */
final class SmsData {
    static final class Message {
        final String address, body;
        final long date;
        Message(String address, String body, long date) {
            this.address = address == null ? "알 수 없는 발신자" : address;
            this.body = body == null ? "" : body;
            this.date = date;
        }
    }
    static String key(String address) {
        if (address == null || address.trim().isEmpty()) return "알 수 없는 발신자";
        String cleaned = address.replaceAll("[\\s().-]", "");
        if (!cleaned.matches("\\+?[0-9]+")) return address.trim();
        if (cleaned.startsWith("+82")) cleaned = "0" + cleaned.substring(3);
        return cleaned;
    }
    static Map<String, List<Message>> group(List<Message> messages, long from, long until) {
        Map<String, List<Message>> result = new LinkedHashMap<>();
        List<Message> ordered = new ArrayList<>(messages);
        ordered.sort((a, b) -> Long.compare(b.date, a.date));
        for (Message message : ordered) {
            if (message.date >= from && message.date < until)
                result.computeIfAbsent(key(message.address), ignored -> new ArrayList<>()).add(message);
        }
        return result;
    }
}
