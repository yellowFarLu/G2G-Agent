package com.wikiagent.interfaces.task.dto;

/** 认领请求：lockVersion 为空时服务端取当前版本做 CAS。 */
public record ClaimRequest(Integer lockVersion) {
}
