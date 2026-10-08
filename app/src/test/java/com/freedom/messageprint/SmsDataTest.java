package com.freedom.messageprint;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class SmsDataTest {
    @Test public void koreanInternationalAndLocalNumbersGroupTogether() {
        assertEquals(SmsData.key("010-1234-5678"), SmsData.key("+82 10 1234 5678"));
        assertNotEquals(SmsData.key("010-1234-5678"), SmsData.key("010-9999-5678"));
        assertEquals("Bank Notice", SmsData.key("Bank Notice"));
    }
    @Test public void rangeIncludesStartAndExcludesNextDayAndSortsLatestFirst() {
        List<SmsData.Message> messages = Arrays.asList(
            new SmsData.Message("0101", "start", 100), new SmsData.Message("0101", "end", 199),
            new SmsData.Message("0101", "next", 200), new SmsData.Message("0101", "before", 99));
        Map<String, List<SmsData.Message>> groups = SmsData.group(messages, 100, 200);
        assertEquals(2, groups.get("0101").size());
        assertEquals("end", groups.get("0101").get(0).body);
    }
    @Test public void emptyRangeProducesNoGroups() {
        assertTrue(SmsData.group(Arrays.asList(new SmsData.Message("123", "text", 20)), 30, 40).isEmpty());
    }
    @Test public void printableContentEscapesMarkupAndPreservesLineBreaks() {
        String html = PrintDocument.html("<이름>", "123", "전체", Arrays.asList(
            new SmsData.Message("123", "<script>alert('x')</script>\n둘째 줄 & 끝", 100)));
        assertTrue(html.contains("&lt;이름&gt;"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("\n둘째 줄 &amp; 끝"));
    }
    @Test public void printOrderIsChronologicalWithoutMutatingInput() {
        List<SmsData.Message> input = Arrays.asList(new SmsData.Message("123", "NEWER", 200), new SmsData.Message("123", "OLDER", 100));
        String html = PrintDocument.html("이름", "123", "전체", input);
        assertTrue(html.indexOf("OLDER") < html.indexOf("NEWER"));
        assertEquals("NEWER", input.get(0).body);
    }
}
