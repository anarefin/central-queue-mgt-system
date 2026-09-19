package com.qms.platform;

import java.util.Map;

/** Thrown by any layer to produce a §20.3 error response with a code from the closed {@link ErrorCode} set. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final String messageKey;
    private final transient Object[] messageArgs;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code) {
        this(code, code.messageKey(), new Object[0], Map.of());
    }

    public ApiException(ErrorCode code, Map<String, Object> details) {
        this(code, code.messageKey(), new Object[0], details);
    }

    public ApiException(ErrorCode code, String messageKey, Object[] messageArgs, Map<String, Object> details) {
        super(code.wire());
        this.code = code;
        this.messageKey = messageKey;
        this.messageArgs = messageArgs;
        this.details = details;
    }

    public ErrorCode code() {
        return code;
    }

    public String messageKey() {
        return messageKey;
    }

    public Object[] messageArgs() {
        return messageArgs;
    }

    public Map<String, Object> details() {
        return details;
    }
}
