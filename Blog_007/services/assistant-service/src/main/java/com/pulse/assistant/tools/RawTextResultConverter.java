package com.pulse.assistant.tools;

import org.springframework.ai.tool.execution.ToolCallResultConverter;

import java.lang.reflect.Type;

/**
 * Spring AI's default converter JSON-encodes a tool's return value, so a String becomes "\"<tool_result …\"" —
 * our untrusted-data fence would reach the model as an escaped string literal. Our tools already return
 * finished text (JSON inside a fence), so pass it through unchanged.
 */
public class RawTextResultConverter implements ToolCallResultConverter {

    @Override
    public String convert(Object result, Type returnType) {
        return result == null ? "" : result.toString();
    }
}
