package com.qms.integration.webhook;

import com.qms.integration.webhook.WebhookDeliveryRepository.AttemptRow;
import com.qms.integration.webhook.WebhookDeliveryRepository.DeliveryRow;
import java.util.List;

record WebhookDeliveryWithAttempts(DeliveryRow delivery, List<AttemptRow> attempts) {}
