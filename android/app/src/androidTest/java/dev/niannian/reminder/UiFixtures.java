package dev.niannian.reminder;

import android.graphics.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;

/** Loopback fixtures in the test APK only. No mail, production service or personal photos. */
final class UiFixtures implements AutoCloseable {
    final ServerSocket server;
    final ExecutorService requests=Executors.newCachedThreadPool();
    final String base;
    volatile int taskDelay,aiDelay,exportDelay;
    volatile boolean taskFailure,gardenFailure,calendarFailure,aiFailure,mediaFailure;
    volatile JSONArray tasks=new JSONArray(),entries=new JSONArray();
    volatile JSONObject garden,parsed,detail;
    final byte[] photo;
    UiFixtures() throws Exception {
        server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));base="http://127.0.0.1:"+server.getLocalPort();
        detail=task("daily","pending");parsed=task("minute_interval","pending");
        tasks.put(detail).put(task("monthly_window","done").put("id","task-02").put("title","申报提醒"));garden=garden(14);
        for(int i=0;i<3;i++)entries.put(record(i==2?"planned":"completion","",i!=2).put("id","record-"+i));
        Bitmap bitmap=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(0xffe8efe0);Paint paint=new Paint(3);paint.setColor(0xff527a58);paint.setTextSize(38);canvas.drawText("UI TEST PHOTO",100,290,paint);paint.setTextSize(24);canvas.drawText("Synthetic attachment / no personal data",100,340,paint);ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,88,out);bitmap.recycle();photo=out.toByteArray();
        requests.submit(()->{while(!server.isClosed())try{Socket socket=server.accept();requests.submit(()->respond(socket));}catch(IOException ignored){break;}});
    }
    static JSONObject obj(Object... pairs)throws JSONException{JSONObject j=new JSONObject();for(int i=0;i<pairs.length;i+=2)j.put((String)pairs[i],pairs[i+1]);return j;}
    static String at(){return LocalDate.now().atTime(9,0).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();}
    static JSONObject task(String kind,String status)throws JSONException{
        JSONObject rule=obj("kind",kind,"mode","until_done","timezone",ZoneId.systemDefault().getId(),"times",new JSONArray().put("20:30"),"start_date",LocalDate.now().toString(),"end_date",kind.equals("date_range")?LocalDate.now().plusDays(6).toString():"","weekdays",new JSONArray().put(1).put(3).put(5),"month_start",1,"month_end",15,"every_days",3,"max_reminders",0,"repeat_minutes",5,"first_at",DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now().plusMinutes(15).withNano(0)));
        return obj("id","task-01","title",kind.equals("minute_interval")?"收衣服":"读书","description","按自己的节奏，照料生活中的小事。","summary",kind.equals("minute_interval")?"每 5 分钟提醒，完成后停止":"每天 20:30 · 完成后停止当天提醒","status",status,"paused",status.equals("paused"),"archived",false,"next_reminder",status.equals("expired")||status.equals("paused")?"":LocalDate.now().atTime(20,30).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString(),"rule",rule);
    }
    static JSONObject record(String type,String status,boolean media)throws JSONException{
        return obj("id","completion-01","task_id","task-01","task_title","读书","type",type,"_type",type,"at",at(),"_at",at(),"created_at",at(),"scheduled_at",at(),"sent_at",status.equals("sent")?at():"","status",status,"state",type.equals("completion")?"completed":type.equals("planned")?"scheduled":"notified","text","今天读完了一个章节。","error",status.equals("failed")?"邮件服务暂时不可用，请稍后查看发送情况。":"","media_ids",media?new JSONArray().put("photo-01"):new JSONArray());
    }
    static JSONObject garden(int count)throws JSONException{
        JSONArray plants=new JSONArray();int total=0;
        for(int i=0;i<count;i++){int n=i%8;total+=n;plants.put(obj("task_id",String.format(java.util.Locale.ROOT,"task-%02d",i+1),"title",new String[]{"读书","申报提醒","收衣服","散步"}[i%4],"completion_count",n,"stage",n==0?"seed":n<3?"sprout":n<7?"leaf":"bloom","archived",false));}
        return obj("timezone",ZoneId.systemDefault().getId(),"total_plants",count,"total_completions",total,"week_completions",5,"truncated",false,"plants",plants,"recent_completions",count==0?new JSONArray():new JSONArray().put(record("completion","",true)));
    }
    private void respond(Socket socket){
        try(Socket connection=socket){
            connection.setSoTimeout(4000);InputStream input=connection.getInputStream();String line=line(input);if(line.isEmpty())return;String[] parts=line.split(" ");String method=parts[0],path=parts[1];int length=0;while(!(line=line(input)).isEmpty())if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());byte[] buffer=new byte[8192];int n=0;while(n<length){int read=input.read(buffer,0,Math.min(buffer.length,length-n));if(read<0)break;n+=read;}
            int status=200;String mime="application/json";JSONObject data=obj("status","ok","version","ui-audit");byte[] bytes=null;
            if(path.equals("/api/v1/tasks")){Thread.sleep(taskDelay);if(taskFailure)status=503;data=method.equals("GET")?obj("tasks",tasks):detail;}
            else if(path.startsWith("/api/v1/garden")){if(gardenFailure)status=503;data=garden;}
            else if(path.startsWith("/api/v1/calendar")){if(calendarFailure)status=503;String month=java.time.YearMonth.now().toString();for(String query:path.substring(path.indexOf('?')+1).split("&"))if(query.startsWith("month="))month=query.substring(6);data=obj("month",month,"timezone",ZoneId.systemDefault().getId(),"entries",entries);}
            else if(path.equals("/api/v1/ai/parse")){Thread.sleep(aiDelay);if(aiFailure)status=503;data=parsed;}
            else if(path.equals("/api/v1/me"))data=obj("email","ui@example.test");
            else if(path.equals("/api/v1/export")){Thread.sleep(exportDelay);data=obj("tasks",tasks,"completions",new JSONArray().put(record("completion","",false)));}
            else if(path.startsWith("/api/v1/media/")){if(mediaFailure)status=503;else{mime="image/jpeg";bytes=photo;}}
            else if(path.equals("/api/v1/media"))data=obj("id","photo-01");
            else if(path.endsWith("/complete"))data=record("completion","",false).put("growth",obj("from_count",0,"to_count",1,"added",true));
            else if(path.startsWith("/api/v1/tasks/"))data=detail;
            else if(path.equals("/api/v1/auth/login")){status=401;data=obj("error","邮箱或密码不正确");}
            if(status!=200)data=obj("error","测试连接暂时不可用，请重试");if(bytes==null)bytes=data.toString().getBytes(StandardCharsets.UTF_8);OutputStream output=connection.getOutputStream();output.write(("HTTP/1.1 "+status+" Test\r\nContent-Type: "+mime+"\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(bytes);output.flush();
        }catch(Exception ignored){}
    }
    private static String line(InputStream input)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();int ch;while((ch=input.read())!=-1&&ch!='\n'){if(ch!='\r')out.write(ch);if(out.size()>8192)throw new IOException("Header too long");}return out.toString(StandardCharsets.US_ASCII.name());}
    @Override public void close()throws IOException{server.close();requests.shutdownNow();}
}
