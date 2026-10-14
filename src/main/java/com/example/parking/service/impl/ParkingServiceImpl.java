package com.example.parking.service.impl;

import com.example.parking.config.ParkingProperties;
import com.example.parking.dto.request.EntryRequest;
import com.example.parking.dto.request.ExitRequest;
import com.example.parking.dto.response.ParkingSessionResponse;
import com.example.parking.entity.Invoice;
import com.example.parking.entity.ParkingSession;
import com.example.parking.entity.ParkingSpace;
import com.example.parking.entity.Reservation;
import com.example.parking.entity.Subscription;
import com.example.parking.entity.User;
import com.example.parking.entity.Vehicle;
import com.example.parking.entity.enums.ReservationStatus;
import com.example.parking.entity.enums.SessionStatus;
import com.example.parking.entity.enums.SpaceStatus;
import com.example.parking.exception.BusinessException;
import com.example.parking.exception.ConflictException;
import com.example.parking.exception.ResourceNotFoundException;
import com.example.parking.mapper.ParkingSessionMapper;
import com.example.parking.repository.InvoiceRepository;
import com.example.parking.repository.ParkingSessionRepository;
import com.example.parking.repository.ParkingSpaceRepository;
import com.example.parking.repository.ReservationRepository;
import com.example.parking.repository.VehicleRepository;
import com.example.parking.repository.specification.ParkingSessionSpecifications;
import com.example.parking.service.BillingService;
import com.example.parking.service.ParkingService;
import com.example.parking.service.SpaceAllocationService;
import com.example.parking.service.SubscriptionService;
import com.example.parking.util.ReferenceGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class ParkingServiceImpl implements ParkingService {

    private static final Logger log = LoggerFactory.getLogger(ParkingServiceImpl.class);

    private final ParkingSessionRepository sessionRepository;
    private final ParkingSpaceRepository spaceRepository;
    private final ReservationRepository reservationRepository;
    private final VehicleRepository vehicleRepository;
    private final InvoiceRepository invoiceRepository;
    private final SpaceAllocationService allocationService;
    private final SubscriptionService subscriptionService;
    private final BillingService billingService;
    private final ParkingSessionMapper sessionMapper;
    private final ParkingProperties properties;

    public ParkingServiceImpl(ParkingSessionRepository sessionRepository,
                              ParkingSpaceRepository spaceRepository,
                              ReservationRepository reservationRepository,
                              VehicleRepository vehicleRepository,
                              InvoiceRepository invoiceRepository,
                              SpaceAllocationService allocationService,
                              SubscriptionService subscriptionService,
                              BillingService billingService,
                              ParkingSessionMapper sessionMapper,
                              ParkingProperties properties) {
        this.sessionRepository = sessionRepository;
        this.spaceRepository = spaceRepository;
        this.reservationRepository = reservationRepository;
        this.vehicleRepository = vehicleRepository;
        this.invoiceRepository = invoiceRepository;
        this.allocationService = allocationService;
        this.subscriptionService = subscriptionService;
        this.billingService = billingService;
        this.sessionMapper = sessionMapper;
        this.properties = properties;
    }

    /**
     * Entree du parking, en une seule transaction :
     * <ol>
     *   <li>le vehicule ne doit pas deja etre dans le parking ;</li>
     *   <li>recherche d'une reservation honorable ;</li>
     *   <li>verrouillage puis verification de la disponibilite de la place ;</li>
     *   <li>attribution automatique si aucune place n'est imposee ;</li>
     *   <li>passage de la place a OCCUPEE et de la reservation a EN_COURS ;</li>
     *   <li>creation de la session.</li>
     * </ol>
     */
    @Override
    @Transactional
    public ParkingSessionResponse registerEntry(EntryRequest request, String operator) {
        Vehicle vehicle = findVehicle(request.getPlateNumber());
        User owner = vehicle.getOwner();

        sessionRepository.findFirstByVehicleIdAndStatus(vehicle.getId(), SessionStatus.EN_COURS)
                .ifPresent(existing -> {
                    throw new ConflictException("Le vehicule " + vehicle.getPlateNumber()
                            + " est deja dans le parking (place " + existing.getSpace().getNumber() + ")");
                });

        LocalDateTime entryTime = request.getEntryTime() != null ? request.getEntryTime() : LocalDateTime.now();
        if (entryTime.isAfter(LocalDateTime.now().plusMinutes(1))) {
            throw new BusinessException("L'heure d'entree ne peut pas etre dans le futur");
        }

        Reservation reservation = findHonorableReservation(vehicle.getId(), entryTime);
        ParkingSpace space = resolveEntrySpace(request, reservation, owner);

        space.setStatus(SpaceStatus.OCCUPEE);
        spaceRepository.save(space);

        if (reservation != null) {
            ParkingSpace reservedSpace = reservation.getSpace();
            boolean spaceChanged = !reservedSpace.getId().equals(space.getId());
            reservation.setStatus(ReservationStatus.EN_COURS);
            if (spaceChanged) {
                // L'operateur a impose une autre place : la reservation suit le vehicule,
                // sinon la place initialement reservee resterait RESERVEE indefiniment.
                reservation.setSpace(space);
            }
            reservationRepository.save(reservation);
            if (spaceChanged && reservedSpace.getStatus() == SpaceStatus.RESERVEE) {
                releaseSpace(reservedSpace);
            }
        }

        Subscription subscription = subscriptionService
                .findValidSubscription(vehicle.getId(), entryTime.toLocalDate())
                .orElse(null);

        ParkingSession session = new ParkingSession();
        session.setReference(ReferenceGenerator.session());
        session.setUser(owner);
        session.setVehicle(vehicle);
        session.setSpace(space);
        session.setReservation(reservation);
        session.setSubscription(subscription);
        session.setEntryTime(entryTime);
        session.setStatus(SessionStatus.EN_COURS);
        session.setEntryOperator(operator);

        ParkingSession saved = sessionRepository.save(session);
        log.info("Entree {} : vehicule {} sur la place {}", saved.getReference(), vehicle.getPlateNumber(),
                space.getNumber());
        return sessionMapper.toResponse(saved);
    }

    /**
     * Sortie du parking, en une seule transaction : cloture de la session,
     * liberation de la place, cloture de la reservation et emission de la
     * facture (depassement inclus).
     */
    @Override
    @Transactional
    public ParkingSessionResponse registerExit(ExitRequest request, String operator) {
        Vehicle vehicle = findVehicle(request.getPlateNumber());
        ParkingSession session = sessionRepository
                .findFirstByVehicleIdAndStatus(vehicle.getId(), SessionStatus.EN_COURS)
                .orElseThrow(() -> new BusinessException("Aucune session en cours pour le vehicule "
                        + vehicle.getPlateNumber()));

        LocalDateTime exitTime = request.getExitTime() != null ? request.getExitTime() : LocalDateTime.now();
        if (exitTime.isBefore(session.getEntryTime())) {
            throw new BusinessException("L'heure de sortie ne peut pas preceder l'heure d'entree ("
                    + session.getEntryTime() + ")");
        }
        if (exitTime.isAfter(LocalDateTime.now().plusMinutes(1))) {
            throw new BusinessException("L'heure de sortie ne peut pas etre dans le futur");
        }

        session.setExitTime(exitTime);
        session.setStatus(SessionStatus.TERMINEE);
        session.setExitOperator(operator);
        ParkingSession closed = sessionRepository.save(session);

        Reservation reservation = closed.getReservation();
        if (reservation != null && reservation.getStatus() == ReservationStatus.EN_COURS) {
            reservation.setStatus(ReservationStatus.TERMINEE);
            reservationRepository.save(reservation);
        }

        releaseSpace(closed.getSpace());

        Subscription subscription = closed.getSubscription() != null
                ? closed.getSubscription()
                : subscriptionService.findValidSubscription(vehicle.getId(), exitTime.toLocalDate()).orElse(null);

        Invoice invoice = billingService.issueInvoice(closed, subscription, request.isMarkAsPaid());

        log.info("Sortie {} : vehicule {}, montant {} {}", closed.getReference(), vehicle.getPlateNumber(),
                invoice.getTotalAmount(), properties.getPricing().getCurrency());
        return sessionMapper.toResponse(closed, invoice);
    }

    @Override
    public Page<ParkingSessionResponse> search(String term, SessionStatus status, String zone,
                                               LocalDateTime from, LocalDateTime to, Pageable pageable) {
        Specification<ParkingSession> spec = Specification.where(ParkingSessionSpecifications.search(term))
                .and(ParkingSessionSpecifications.hasStatus(status))
                .and(ParkingSessionSpecifications.hasZone(zone))
                .and(ParkingSessionSpecifications.entryFrom(from))
                .and(ParkingSessionSpecifications.entryTo(to));
        return sessionRepository.findAll(spec, pageable)
                .map(session -> sessionMapper.toResponse(session,
                        invoiceRepository.findBySessionId(session.getId()).orElse(null)));
    }

    @Override
    public ParkingSessionResponse getById(Long id) {
        ParkingSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Session de stationnement", id));
        return sessionMapper.toResponse(session, invoiceRepository.findBySessionId(id).orElse(null));
    }

    @Override
    public List<ParkingSessionResponse> findOngoing() {
        return sessionRepository.findByStatusOrderByEntryTimeAsc(SessionStatus.EN_COURS).stream()
                .map(sessionMapper::toResponse)
                .toList();
    }

    // ------------------------------------------------------------------ regles

    private Vehicle findVehicle(String plateNumber) {
        String normalized = plateNumber == null ? "" : plateNumber.trim().toUpperCase().replace(" ", "");
        return vehicleRepository.findByPlateNumberIgnoreCase(normalized)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Aucun vehicule enregistre avec l'immatriculation " + normalized));
    }

    /**
     * Reservation confirmee que le vehicule peut honorer maintenant : la periode
     * de tolerance evite de refuser une arrivee legerement anticipee.
     */
    private Reservation findHonorableReservation(Long vehicleId, LocalDateTime entryTime) {
        int grace = properties.getReservation().getGracePeriodMinutes();
        List<Reservation> candidates = reservationRepository.findHonorableForEntry(
                vehicleId, ReservationStatus.CONFIRMEE, entryTime.plusMinutes(grace), entryTime);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private ParkingSpace resolveEntrySpace(EntryRequest request, Reservation reservation, User owner) {
        // 1. Place imposee par l'operateur
        if (request.getSpaceId() != null) {
            ParkingSpace space = spaceRepository.findByIdForUpdate(request.getSpaceId())
                    .orElseThrow(() -> new ResourceNotFoundException("Place de parking", request.getSpaceId()));
            assertUsable(space, reservation);
            return space;
        }

        // 2. Place de la reservation honoree
        if (reservation != null) {
            ParkingSpace space = spaceRepository.findByIdForUpdate(reservation.getSpace().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Place de parking",
                            reservation.getSpace().getId()));
            assertUsable(space, reservation);
            return space;
        }

        // 3. Attribution automatique
        return allocationService.allocate(owner.getCategory(), request.getPreferredZone());
    }

    private void assertUsable(ParkingSpace space, Reservation reservation) {
        if (space.getStatus() == SpaceStatus.HORS_SERVICE) {
            throw new BusinessException("La place " + space.getNumber() + " est hors service");
        }
        if (space.getStatus() == SpaceStatus.OCCUPEE) {
            throw new ConflictException("La place " + space.getNumber() + " est deja occupee");
        }
        if (space.getStatus() == SpaceStatus.RESERVEE) {
            boolean ownReservation = reservation != null && reservation.getSpace().getId().equals(space.getId());
            if (!ownReservation) {
                throw new ConflictException("La place " + space.getNumber()
                        + " est reservee par un autre utilisateur");
            }
        }
    }

    /**
     * Libere la place apres une sortie : elle redevient RESERVEE s'il reste des
     * reservations actives, sinon LIBRE.
     */
    private void releaseSpace(ParkingSpace space) {
        Optional<ParkingSpace> locked = spaceRepository.findByIdForUpdate(space.getId());
        ParkingSpace target = locked.orElse(space);
        if (target.getStatus() == SpaceStatus.HORS_SERVICE) {
            return;
        }
        long activeReservations = reservationRepository.countBySpaceIdAndStatusIn(target.getId(),
                ReservationStatus.activeStatuses());
        target.setStatus(activeReservations > 0 ? SpaceStatus.RESERVEE : SpaceStatus.LIBRE);
        spaceRepository.save(target);
    }
}
