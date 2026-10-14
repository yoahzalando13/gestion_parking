package com.example.parking.repository;

import com.example.parking.entity.Subscription;
import com.example.parking.entity.enums.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long>,
        JpaSpecificationExecutor<Subscription> {

    Optional<Subscription> findByReference(String reference);

    List<Subscription> findByUserIdOrderByStartDateDesc(Long userId);

    long countByStatus(SubscriptionStatus status);

    /** Abonnement valide pour un vehicule a une date donnee. */
    @Query("""
            select s from Subscription s
            where s.vehicle.id = :vehicleId
              and s.status = :status
              and s.startDate <= :date
              and s.endDate >= :date
            order by s.endDate desc
            """)
    List<Subscription> findValidForVehicle(@Param("vehicleId") Long vehicleId,
                                           @Param("date") LocalDate date,
                                           @Param("status") SubscriptionStatus status);

    /** Chevauchement d'abonnements actifs pour un meme vehicule. */
    @Query("""
            select count(s) > 0 from Subscription s
            where s.vehicle.id = :vehicleId
              and s.status = :status
              and s.id <> :excludedId
              and s.startDate <= :end
              and s.endDate >= :start
            """)
    boolean existsOverlapping(@Param("vehicleId") Long vehicleId,
                              @Param("start") LocalDate start,
                              @Param("end") LocalDate end,
                              @Param("status") SubscriptionStatus status,
                              @Param("excludedId") Long excludedId);

    /** Vrai si l'utilisateur possede encore un abonnement actif non echu. */
    boolean existsByUserIdAndStatusAndEndDateGreaterThanEqual(Long userId, SubscriptionStatus status,
                                                              LocalDate date);

    /** Abonnements actifs dont la date de fin est depassee. */
    @Query("select s from Subscription s where s.status = :status and s.endDate < :date")
    List<Subscription> findExpired(@Param("status") SubscriptionStatus status, @Param("date") LocalDate date);
}
