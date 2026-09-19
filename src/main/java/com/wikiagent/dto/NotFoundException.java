package com.wikiagent.dto;

/** 资源不存在（全局异常处理映射为 404）。 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
