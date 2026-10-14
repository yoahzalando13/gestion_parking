package com.example.parking.service.impl;

import com.example.parking.config.ParkingProperties;
import com.example.parking.dto.request.CreateSubscriptionRequest;
import com.example.parking.dto.response.SubscriptionResponse;
import com.example.parking.entity.Subscription;
import com.example.parking.entity.User;
import com.example.parking.entity.Vehicle;
import com.example.parking.entity.enums.SubscriptionStatus;
import com.example.parking.entity.enums.SubscriptionType;
import com.example.parking.entity.enums.UserCategory;
import com.example.parking.exception.BusinessException;
import com.example.parking.exception.ConflictException;
import com.example.parking.exception.InvalidOperationException;
import com.example.parking.exception.ResourceNotFoundException;
import com.example.parking.mapper.SubscriptionMapper;
import com.example.parking.repository.SubscriptionRepository;
import com.example.parking.repository.UserRepository;
import com.example.parking.repository.VehicleRepository;
import com.example.parking.repository.specification.SubscriptionSpecifications;
import com.example.parking.service.SubscriptionService;
import com.example.parking.util.ReferenceGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class SubscriptionServiceImpl implements SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionServiceImpl.class);

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final SubscriptionMapper subscriptionMapper;
    private final ParkingProperties properties;

    public SubscriptionServiceImpl(SubscriptionRepository subscriptionRepository,
                                   UserRepository userRepository,
                                   VehicleRepository vehicleRepository,
                                   SubscriptionMapper subscriptionMapper,
                                   ParkingProperties properties) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.subscriptionMapper = subscriptionMapper;
        this.properties = properties;
    }

    @Override
    public Page<SubscriptionResponse> search(String term, SubscriptionStatus status, SubscriptionType type,
                                             Long userId, LocalDate validOn, Pageable pageable) {
        Specification<Subscription> spec = Specification.where(SubscriptionSpecifications.search(term))
                .and(SubscriptionSpecifications.hasStatus(status))
                .and(SubscriptionSpecifications.hasType(type))
                .and(SubscriptionSpecifications.hasUser(userId))
                .and(SubscriptionSpecifications.validOn(validOn));
        return subscriptionRepository.findAll(spec, pageable).map(subscriptionMapper::toResponse);
    }

    @Override
    public SubscriptionResponse getById(Long id) {
        return subscriptionMapper.toResponse(getEntity(id));
    }

    @Override
    @Transactional
    public SubscriptionResponse create(CreateSubscriptionRequest request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur", request.getUserId()));
        Vehicle vehicle = vehicleRepository.findById(request.getVehicleId())
                .orElseThrow(() -> new ResourceNotFoundException("Vehicule", request.getVehicleId()));

        if (vehicle.getOwner() == null || !vehicle.getOwner().getId().equals(user.getId())) {
            throw new BusinessException("Le vehicule " + vehicle.getPlateNumber()
                    + " n'appartient pas a " + user.getFullName());
        }
        if (request.getStartDate() == null) {
            throw new BusinessException("La date de debut est obligatoire");
        }

        LocalDate startDate = request.getStartDate();
        LocalDate endDate = request.getType().computeEndDate(startDate);

        if (subscriptionRepository.existsOverlapping(vehicle.getId(), startDate, endDate,
                SubscriptionStatus.ACTIF, -1L)) {
            throw new ConflictException("Le vehicule " + vehicle.getPlateNumber()
                    + " possede deja un abonnement actif couvrant cette periode");
        }

        Subscription subscription = new Subscription();
        subscription.setReference(ReferenceGenerator.subscription());
        subscription.setUser(user);
        subscription.setVehicle(vehicle);
        subscription.setType(request.getType());
        subscription.setStartDate(startDate);
        subscription.setEndDate(endDate);
        subscription.setStatus(SubscriptionStatus.ACTIF);
        subscription.setPrice(request.getPrice() != null && request.getPrice().signum() > 0
                ? request.getPrice()
                : catalogPrice(request.getType()));

        Subscription saved = subscriptionRepository.save(subscription);
        promoteToSubscriber(user);

        log.info("Abonnement {} cree pour {} ({} -> {})", saved.getReference(), vehicle.getPlateNumber(),
                startDate, endDate);
        return subscriptionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public SubscriptionResponse cancel(Long id) {
        Subscription subscription = getEntity(id);
        if (subscription.getStatus() == SubscriptionStatus.ANNULE) {
            throw new InvalidOperationException("Cet abonnement est deja annule");
        }
        subscription.setStatus(SubscriptionStatus.ANNULE);
        Subscription saved = subscriptionRepository.save(subscription);
        demoteIfNoLongerSubscriber(saved.getUser());
        return subscriptionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public SubscriptionResponse suspend(Long id) {
        Subscription subscription = getEntity(id);
        if (subscription.getStatus() != SubscriptionStatus.ACTIF) {
            throw new InvalidOperationException("Seul un abonnement actif peut etre suspendu");
        }
        subscription.setStatus(SubscriptionStatus.SUSPENDU);
        Subscription saved = subscriptionRepository.save(subscription);
        demoteIfNoLongerSubscriber(saved.getUser());
        return subscriptionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public SubscriptionResponse reactivate(Long id) {
        Subscription subscription = getEntity(id);
        if (subscription.getStatus() != SubscriptionStatus.SUSPENDU) {
            throw new InvalidOperationException("Seul un abonnement suspendu peut etre reactive");
        }
        if (subscription.isExpiredOn(LocalDate.now())) {
            throw new InvalidOperationException("Cet abonnement est arrive a echeance le "
                    + subscription.getEndDate() + " : creez un nouvel abonnement");
        }
        subscription.setStatus(SubscriptionStatus.ACTIF);
        Subscription saved = subscriptionRepository.save(subscription);
        promoteToSubscriber(saved.getUser());
        return subscriptionMapper.toResponse(saved);
    }

    @Override
    public Optional<Subscription> findValidSubscription(Long vehicleId, LocalDate date) {
        List<Subscription> valid = subscriptionRepository.findValidForVehicle(vehicleId, date,
                SubscriptionStatus.ACTIF);
        return valid.isEmpty() ? Optional.empty() : Optional.of(valid.get(0));
    }

    @Override
    public BigDecimal catalogPrice(SubscriptionType type) {
        ParkingProperties.SubscriptionPricing catalog = properties.getSubscription();
        return switch (type) {
            case JOURNALIER -> catalog.getDailyPrice();
            case HEBDOMADAIRE -> catalog.getWeeklyPrice();
            case MENSUEL -> catalog.getMonthlyPrice();
            case ANNUEL -> catalog.getYearlyPrice();
        };
    }

    @Override
    @Transactional
    public int refreshExpiredSubscriptions() {
        List<Subscription> expired = subscriptionRepository.findExpired(SubscriptionStatus.ACTIF, LocalDate.now());
        for (Subscription subscription : expired) {
            subscription.setStatus(SubscriptionStatus.EXPIRE);
            subscriptionRepository.save(subscription);
        }
        expired.stream().map(Subscription::getUser).distinct().forEach(this::demoteIfNoLongerSubscriber);
        if (!expired.isEmpty()) {
            log.info("{} abonnement(s) passe(s) en EXPIRE", expired.size());
        }
        return expired.size();
    }

    /** Un abonne beneficie de la priorite sur les places de type ABONNE. */
    private void promoteToSubscriber(User user) {
        if (user.getCategory() == UserCategory.STANDARD) {
            user.setCategory(UserCategory.ABONNE);
            userRepository.save(user);
        }
    }

    /**
     * Retire la categorie ABONNE des qu'il ne reste plus aucun abonnement actif :
     * sans cela l'utilisateur conserverait indefiniment la reduction et la
     * priorite abonne apres une annulation, une suspension ou une echeance.
     */
    private void demoteIfNoLongerSubscriber(User user) {
        if (user.getCategory() != UserCategory.ABONNE) {
            return;
        }
        boolean stillSubscriber = subscriptionRepository.existsByUserIdAndStatusAndEndDateGreaterThanEqual(
                user.getId(), SubscriptionStatus.ACTIF, LocalDate.now());
        if (!stillSubscriber) {
            user.setCategory(UserCategory.STANDARD);
            userRepository.save(user);
        }
    }

    private Subscription getEntity(Long id) {
        return subscriptionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Abonnement", id));
    }
}
