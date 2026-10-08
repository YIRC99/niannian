package dev.niannian.reminder;

import java.time.Instant;
import java.util.*;

public class SowingDialogueTest {
    static int checks;
    public static void main(String[] args) {
        Instant now=Instant.parse("2026-10-08T01:00:00Z");
        Map<String,String> minute=fields("first_at","2026-10-08T09:03:00+08:00","first_date","2026-10-08","first_time","09:03:00","timezone","Asia/Shanghai","repeat_minutes","5");
        eq("约 3 分钟后",SowingDialogue.time("minute_interval",minute,List.of(),now));
        eq("马上",SowingDialogue.time("minute_interval",minute,List.of(),now.plusSeconds(175)));
        eq("原定今天 09:03:00",SowingDialogue.time("minute_interval",minute,List.of(),now.plusSeconds(240)));
        eq("还没完成，我会接着提醒你，至少间隔 5 分钟。\n做完就停。",SowingDialogue.followUp("minute_interval","until_done",minute));
        eq("只提醒这一次，之后不再催你。",SowingDialogue.followUp("once","notify_once",fields()));
        eq("每个提醒日只提醒一次，之后不再催你。",SowingDialogue.followUp("daily","notify_once",fields()));
        eq("每周只提醒一次，之后不再催你。",SowingDialogue.followUp("weekly","notify_once",fields()));
        eq("没完成时继续提醒，当天做完就停。\n下个提醒日继续。",SowingDialogue.followUp("weekly","every_day",fields()));
        eq("没完成时继续提醒，本周做完就停。\n下周继续。",SowingDialogue.followUp("weekly","until_done",fields()));
        eq("没完成时继续提醒，本月做完就停。\n下月继续。",SowingDialogue.followUp("monthly_window","until_done",fields()));
        eq("没完成时继续提醒，做完就停。",SowingDialogue.followUp("ongoing","notify_once",fields()));
        eq("每周一、三 09:00",SowingDialogue.time("weekly",fields("times","09:00"),List.of(3,1,1),now));
        eq("明天 08:30",SowingDialogue.time("once",fields("start_date","2026-10-09","times","08:30","timezone","Asia/Shanghai"),List.of(),now));
        eq("每天 09:00、18:00",SowingDialogue.time("daily",fields("times","09:00, 18:00"),List.of(),now));
        eq("请先补全提醒时间",SowingDialogue.time("minute_interval",fields("first_at","bad","timezone","bad"),List.of(),now));
        eq("还没完成，我会接着提醒你。\n做完就停。",SowingDialogue.followUp("minute_interval","until_done",fields("repeat_minutes","bad")));
        eq("请先调整提醒规则。",SowingDialogue.followUp("unsupported","until_done",fields()));
        eq("2026-10-08T09:03:00+08:00",minute.get("first_at"));
        eq("",SowingDialogue.visibleNote("daily","until_done",fields("timezone","Asia/Shanghai"),java.time.ZoneId.of("Asia/Shanghai")));
        eq("时区 America/New_York",SowingDialogue.visibleNote("daily","until_done",fields("timezone","America/New_York"),java.time.ZoneId.of("Asia/Shanghai")));
        eq("最多 4 次",SowingDialogue.visibleNote("minute_interval","until_done",fields("timezone","Asia/Shanghai","max_reminders","4"),java.time.ZoneId.of("Asia/Shanghai")));
        eq("截止 2026-10-08 09:03:17，之后停止提醒",SowingDialogue.visibleNote("minute_interval","until_done",fields("timezone","Asia/Shanghai","end_at","2026-10-08T01:03:17Z"),java.time.ZoneId.of("Asia/Shanghai")));
        eq("时区 invalid",SowingDialogue.visibleNote("daily","until_done",fields("timezone","invalid"),java.time.ZoneId.of("Asia/Shanghai")));
        System.out.println("SowingDialogueTest: "+checks+" checks passed");
    }
    static Map<String,String> fields(String... pairs){Map<String,String> result=new HashMap<>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i],pairs[i+1]);return result;}
    static void eq(String expected,String actual){checks++;if(!expected.equals(actual))throw new AssertionError("expected "+expected+", got "+actual);}
}
