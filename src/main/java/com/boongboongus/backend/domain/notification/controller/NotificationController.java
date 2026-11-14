package com.boongboongus.backend.domain.notification.controller;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.domain.notification.dto.NotificationResponse;
import com.boongboongus.backend.domain.notification.dto.NotificationResponse.Page;
import com.boongboongus.backend.domain.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {
    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @Operation(operationId = "getNotifications", summary = "내 알림 목록 조회")
    ApiResponse<Page> list(Principal principal, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return ApiResponse.success(notifications.list(userId(principal), cursor, limit, unreadOnly));
    }

    @PatchMapping("/{notificationId}/read")
    @Operation(operationId = "markNotificationRead", summary = "내 알림 읽음 처리")
    ApiResponse<NotificationResponse> markRead(Principal principal, @PathVariable String notificationId) {
        String userId = userId(principal);
        if (notificationId.isBlank() || notificationId.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return ApiResponse.success(notifications.markRead(userId, notificationId));
    }

    private static String userId(Principal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return principal.getName();
    }
}
