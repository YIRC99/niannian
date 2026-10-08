package dev.niannian.reminder;
import java.time.Instant;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Read-only, conversational presentation of the editor's actual rule. */
final class SowingDialogue {
    private SowingDialogue() {}
    static String visibleNote(String kind,String mode,Map<String,String> fields,ZoneId deviceZone){
        String note=ReminderSummary.note(kind,mode,fields),zone=field(fields,"timezone");
        try {
            if(!zone.isEmpty()&&ZoneId.of(zone).normalized().equals(deviceZone.normalized())){
                String suffix="时区 "+zone;
                if(note.equals(suffix))return "";
                if(note.endsWith(" · "+suffix))return note.substring(0,note.length()-suffix.length()-3);
            }
        }catch(DateTimeException invalid){ /* Keep invalid zones visible for correction. */ }
        return note;
    }
    static String time(String kind, Map<String,String> fields, List<Integer> weekdays, Instant now) {
        try {
            String zoneName=field(fields,"timezone");
            ZoneId zone=ZoneId.of(zoneName.isEmpty()?"Asia/Shanghai":zoneName);
            if("minute_interval".equals(kind)) {
                ZonedDateTime first=OffsetDateTime.parse(field(fields,"first_at")).atZoneSameInstant(zone);
                long seconds=Duration.between(now,first.toInstant()).getSeconds();
                if(seconds>=0 && seconds<60)return "马上";
                if(seconds>=60 && seconds<=3600)return "约 "+Math.round(seconds/60d)+" 分钟后";
                String date=day(first.toLocalDate(),now.atZone(zone).toLocalDate());
                return (seconds<0?"原定":"")+date+" "+first.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            }
            String[] values=ReminderSummary.values(kind,"until_done",fields,weekdays);
            if(values[0].startsWith("请")||values[0].equals("暂不支持")||values[1].startsWith("请"))return "请先补全提醒时间";
            if("once".equals(kind)) {
                LocalDate date=LocalDate.parse(field(fields,"start_date"));
                return day(date,now.atZone(zone).toLocalDate())+" "+values[0];
            }
            String schedule=values[1];
            if("ongoing".equals(kind))schedule="每天";
            if("date_range".equals(kind))schedule="指定日期内，每天";
            return schedule+" "+values[0];
        } catch(RuntimeException invalid) {return "请先补全提醒时间";}
    }
    static String followUp(String kind, String mode, Map<String,String> fields) {
        if("minute_interval".equals(kind)) {
            String interval="";
            try {int minutes=Integer.parseInt(field(fields,"repeat_minutes"));if(minutes>0&&minutes<=1440)interval="，至少间隔 "+minutes+" 分钟";}catch(NumberFormatException ignored){}
            return "还没完成，我会接着提醒你"+interval+"。\n做完就停。";
        }
        if("ongoing".equals(kind))return "没完成时继续提醒，做完就停。";
        if(!java.util.Arrays.asList("once","daily","weekly","monthly_window","date_range","interval").contains(kind))return "请先调整提醒规则。";
        if("notify_once".equals(mode)) {
            String scope="once".equals(kind)?"只提醒这一次":
                "weekly".equals(kind)?"每周只提醒一次":
                "monthly_window".equals(kind)?"每月只提醒一次":
                "date_range".equals(kind)?"这段日期内只提醒一次":"每个提醒日只提醒一次";
            return scope+"，之后不再催你。";
        }
        if("once".equals(kind)||"date_range".equals(kind)&&!"every_day".equals(mode))return "没完成时继续提醒，做完就停。";
        if("every_day".equals(mode)||java.util.Arrays.asList("daily","interval").contains(kind))return "没完成时继续提醒，当天做完就停。\n下个提醒日继续。";
        if("weekly".equals(kind))return "没完成时继续提醒，本周做完就停。\n下周继续。";
        if("monthly_window".equals(kind))return "没完成时继续提醒，本月做完就停。\n下月继续。";
        return "请先调整提醒规则。";
    }
    private static String day(LocalDate date,LocalDate today){return date.equals(today)?"今天":date.equals(today.plusDays(1))?"明天":date.toString();}
    private static String field(Map<String,String> fields,String key){String value=fields==null?null:fields.get(key);return value==null?"":value.trim();}
}
