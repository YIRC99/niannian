package dev.niannian.reminder;

public final class ClarificationFlowTest {
    public static void main(String[] args) {
        ClarificationFlow flow = new ClarificationFlow();
        flow.start("纽约面试提前半小时提醒");
        flow.stage("哪天？", "10月9日");
        if(flow.requestAnswers().size()!=1 || flow.answers().size()!=0)throw new AssertionError("answer committed before success");
        try { flow.stage("几点？", "14点"); throw new AssertionError("double submit"); } catch(IllegalStateException expected) {}
        flow.failed();
        flow.stage("哪天？", "10月9日"); flow.accepted();
        flow.stage("几点？", "纽约时间14点"); flow.accepted();
        if(flow.answers().size()!=2 || !flow.original().equals("纽约面试提前半小时提醒"))throw new AssertionError("lost intent");
        for(int i=2;i<4;i++){flow.stage("补充", "答复");flow.accepted();}
        if(flow.canAnswer())throw new AssertionError("unbounded history");
        flow.start("新事项");
        if(!flow.answers().isEmpty() || !flow.canAnswer())throw new AssertionError("rephrase leaked context");
        flow.clear();if(!flow.original().isEmpty())throw new AssertionError("account isolation");
        System.out.println("ClarificationFlowTest passed");
    }
}
