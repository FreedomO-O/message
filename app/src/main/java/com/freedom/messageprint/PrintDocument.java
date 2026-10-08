package com.freedom.messageprint;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class PrintDocument {
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    static String html(String name, String number, String range, List<SmsData.Message> messages) {
        StringBuilder out = new StringBuilder("<!doctype html><html lang='ko'><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>@page{size:A4;margin:16mm}body{font-family:sans-serif;color:#172438;font-size:11pt}"
            + "h1{font-size:20pt}header{border-bottom:2px solid #285b76;padding-bottom:12px}"
            + ".message{margin:18px 0;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.6}"
            + ".date{font-size:9pt;color:#526575;page-break-after:avoid}"
            + ".body{margin-top:4px}footer{font-size:9pt;color:#526575}</style></head><body><header><h1>받은 문자 기록</h1><p>");
        out.append(escape(name)).append(" · ").append(escape(number)).append("</p><p>")
            .append(escape(range)).append(" · ").append(messages.size()).append("건</p></header>");
        List<SmsData.Message> ordered = new ArrayList<>(messages);
        ordered.sort((a, b) -> Long.compare(a.date, b.date));
        SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA);
        for (SmsData.Message message : ordered) {
            out.append("<section class='message'><div class='date'>")
                .append(date.format(new Date(message.date))).append("</div><div class='body'>")
                .append(escape(message.body)).append("</div></section>");
        }
        return out.append("<footer>휴대폰에 저장된 받은 SMS 문자 기록</footer></body></html>").toString();
    }
}
