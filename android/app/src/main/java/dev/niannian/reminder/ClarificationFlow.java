package dev.niannian.reminder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Only accepted answers enter history; failed requests can be retried once. */
final class ClarificationFlow {
    static final class Answer {
        final String question, answer, answeredAt;
        Answer(String question, String answer, String answeredAt) { this.question=question; this.answer=answer; this.answeredAt=answeredAt; }
    }
    private String original="";
    private final List<Answer> history=new ArrayList<>();
    private Answer pending;
    void start(String text) { clear(); original=text; }
    void clear() { original=""; history.clear(); pending=null; }
    String original() { return original; }
    boolean canAnswer() { return history.size()<4; }
    List<Answer> answers() { return Collections.unmodifiableList(new ArrayList<>(history)); }
    List<Answer> requestAnswers() {
        List<Answer> result=new ArrayList<>(history); if(pending!=null)result.add(pending); return result;
    }
    void stage(String question,String answer) {
        if(pending!=null || !canAnswer())throw new IllegalStateException("请重新描述这件事");
        if(question.trim().isEmpty() || question.codePointCount(0,question.length())>200 || answer.trim().isEmpty() || answer.codePointCount(0,answer.length())>500)
            throw new IllegalArgumentException("请补充 1—500 字");
        pending=new Answer(question.trim(),answer.trim(),"");
    }
    void accepted() { accepted(""); }
    void accepted(String at) { if(pending!=null){history.add(new Answer(pending.question,pending.answer,at));pending=null;} }
    void failed() { pending=null; }
}
