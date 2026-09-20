package com.qms.notification;

import com.qms.notification.NotificationMessageRepository.AttemptRow;
import com.qms.notification.NotificationMessageRepository.MessageRow;
import java.util.List;

/** One delivery-log row: a message plus every attempt made to deliver it (FR-NTF-032). */
record MessageWithAttempts(MessageRow message, List<AttemptRow> attempts) {}
