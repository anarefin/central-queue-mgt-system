package com.qms.platform.realtime;

/** The transport under one hub connection. It must be safe to call from several threads. */
interface Outbound {

    /** Sends one text frame. Frames sent by one thread arrive in order. */
    void send(String frame) throws Exception;

    void close(int code, String reason);
}
