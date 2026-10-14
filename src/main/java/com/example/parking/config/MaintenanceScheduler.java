package com.example.parking.config;

import com.example.parking.service.ReservationService;
import com.example.parking.service.SubscriptionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Taches de maintenance periodiques. Sans elles, une reservation non honoree
 * laisse sa place en RESERVEE indefiniment (l'attribution automatique ne la
 * propose plus) et un abonnement echu reste ACTIF.
 * Desactivable avec {@code parking.maintenance.enabled: false}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "parking.maintenance", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class MaintenanceScheduler {

    private final ReservationService reservationService;
    private final SubscriptionService subscriptionService;

    public MaintenanceScheduler(ReservationService reservationService, SubscriptionService subscriptionService) {
        this.reservationService = reservationService;
        this.subscriptionService = subscriptionService;
    }

    @Scheduled(initialDelayString = "${parking.maintenance.initial-delay-ms:60000}",
            fixedDelayString = "${parking.maintenance.fixed-delay-ms:300000}")
    public void expireStaleReservations() {
        reservationService.expireStaleReservations();
    }

    @Scheduled(initialDelayString = "${parking.maintenance.initial-delay-ms:60000}",
            fixedDelayString = "${parking.maintenance.fixed-delay-ms:300000}")
    public void refreshExpiredSubscriptions() {
        subscriptionService.refreshExpiredSubscriptions();
    }
}
