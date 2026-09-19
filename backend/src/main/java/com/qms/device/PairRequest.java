package com.qms.device;

/** Body of {@code POST /devices/pair}: the code an administrator gave the device (FR-OPS-011). */
record PairRequest(String code) {}
