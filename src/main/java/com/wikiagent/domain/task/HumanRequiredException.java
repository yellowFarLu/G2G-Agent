package com.wikiagent.domain.task;

/**
 * 人工接管请求：handler 判定需要人工介入时抛出，worker 建 human_task 并置 WAITING_HUMAN。
 */
public class HumanRequiredException extends RuntimeException {

    private final HumanTaskKind kind;
    private final String title;
    private final String instruction;
    private final com.fasterxml.jackson.databind.JsonNode formSchema;

    public HumanRequiredException(HumanTaskKind kind, String title, String instruction,
                                  com.fasterxml.jackson.databind.JsonNode formSchema) {
        super("需要人工接管: " + title);
        this.kind = kind;
        this.title = title;
        this.instruction = instruction;
        this.formSchema = formSchema;
    }

    public HumanTaskKind getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    public String getInstruction() {
        return instruction;
    }

    public com.fasterxml.jackson.databind.JsonNode getFormSchema() {
        return formSchema;
    }
}
