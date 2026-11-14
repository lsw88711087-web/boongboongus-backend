package com.boongboongus.backend.domain.notification.service;

import com.boongboongus.backend.domain.home.PlaceShareNotification;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

@Component
@Fallback
class PlaceShareNotificationAdapter implements PlaceShareNotification {
    private final NotificationService notifications;

    PlaceShareNotificationAdapter(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public Receipt create(String coupleId, String senderUserId, String recipientUserId, String placeId) {
        return notifications.placeShared(senderUserId, recipientUserId, placeId);
    }
}
