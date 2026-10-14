package com.example.parking.service.impl;

import com.example.parking.dto.request.CreateSpaceRequest;
import com.example.parking.dto.request.UpdateSpaceRequest;
import com.example.parking.dto.response.ParkingSpaceResponse;
import com.example.parking.entity.ParkingSpace;
import com.example.parking.entity.enums.ReservationStatus;
import com.example.parking.entity.enums.SessionStatus;
import com.example.parking.entity.enums.SpaceStatus;
import com.example.parking.entity.enums.SpaceType;
import com.example.parking.exception.BusinessException;
import com.example.parking.exception.ConflictException;
import com.example.parking.exception.InvalidOperationException;
import com.example.parking.exception.ResourceNotFoundException;
import com.example.parking.mapper.ParkingSpaceMapper;
import com.example.parking.repository.ParkingSessionRepository;
import com.example.parking.repository.ParkingSpaceRepository;
import com.example.parking.repository.ReservationRepository;
import com.example.parking.repository.specification.ParkingSpaceSpecifications;
import com.example.parking.service.ParkingSpaceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class ParkingSpaceServiceImpl implements ParkingSpaceService {

    private static final Sort DEFAULT_SORT = Sort.by("zone").ascending().and(Sort.by("number").ascending());

    private final ParkingSpaceRepository spaceRepository;
    private final ParkingSessionRepository sessionRepository;
    private final ReservationRepository reservationRepository;
    private final ParkingSpaceMapper spaceMapper;

    public ParkingSpaceServiceImpl(ParkingSpaceRepository spaceRepository,
                                   ParkingSessionRepository sessionRepository,
                                   ReservationRepository reservationRepository,
                                   ParkingSpaceMapper spaceMapper) {
        this.spaceRepository = spaceRepository;
        this.sessionRepository = sessionRepository;
        this.reservationRepository = reservationRepository;
        this.spaceMapper = spaceMapper;
    }

    @Override
    public Page<ParkingSpaceResponse> search(String term, String zone, SpaceType type, SpaceStatus status,
                                             Integer floor, Pageable pageable) {
        Specification<ParkingSpace> spec = Specification.where(ParkingSpaceSpecifications.search(term))
                .and(ParkingSpaceSpecifications.hasZone(zone))
                .and(ParkingSpaceSpecifications.hasType(type))
                .and(ParkingSpaceSpecifications.hasStatus(status))
                .and(ParkingSpaceSpecifications.hasFloor(floor));
        return spaceRepository.findAll(spec, pageable).map(spaceMapper::toResponse);
    }

    @Override
    public List<ParkingSpaceResponse> findAvailable(SpaceType type, String zone) {
        Specification<ParkingSpace> spec = Specification
                .where(ParkingSpaceSpecifications.hasStatus(SpaceStatus.LIBRE))
                .and(ParkingSpaceSpecifications.hasType(type))
                .and(ParkingSpaceSpecifications.hasZone(zone));
        return spaceRepository.findAll(spec, DEFAULT_SORT).stream().map(spaceMapper::toResponse).toList();
    }

    @Override
    public List<ParkingSpaceResponse> findAllForMap() {
        return spaceRepository.findAllByOrderByZoneAscNumberAsc().stream().map(spaceMapper::toResponse).toList();
    }

    @Override
    public Map<String, List<ParkingSpaceResponse>> findGroupedByZone() {
        Map<String, List<ParkingSpaceResponse>> grouped = new LinkedHashMap<>();
        for (ParkingSpace space : spaceRepository.findAllByOrderByZoneAscNumberAsc()) {
            grouped.computeIfAbsent(space.getZone(), zone -> new ArrayList<>())
                    .add(spaceMapper.toResponse(space));
        }
        return grouped;
    }

    @Override
    public List<String> findZones() {
        return spaceRepository.findDistinctZones();
    }

    @Override
    public ParkingSpaceResponse getById(Long id) {
        return spaceMapper.toResponse(getEntity(id));
    }

    @Override
    public ParkingSpace getEntity(Long id) {
        return spaceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Place de parking", id));
    }

    @Override
    @Transactional
    public ParkingSpaceResponse create(CreateSpaceRequest request) {
        String number = request.getNumber().trim().toUpperCase();
        if (spaceRepository.existsByNumberIgnoreCase(number)) {
            throw new ConflictException("La place " + number + " existe deja");
        }
        ParkingSpace space = spaceMapper.toEntity(request);
        space.setStatus(SpaceStatus.LIBRE);
        return spaceMapper.toResponse(spaceRepository.save(space));
    }

    @Override
    @Transactional
    public ParkingSpaceResponse update(Long id, UpdateSpaceRequest request) {
        ParkingSpace space = getEntity(id);
        String number = request.getNumber().trim().toUpperCase();
        if (spaceRepository.existsByNumberIgnoreCaseAndIdNot(number, id)) {
            throw new ConflictException("La place " + number + " existe deja");
        }
        if (space.getStatus() == SpaceStatus.OCCUPEE && request.getType() != space.getType()) {
            throw new InvalidOperationException(
                    "Impossible de changer le type d'une place occupee. Attendez la sortie du vehicule.");
        }

        space.setNumber(number);
        space.setZone(request.getZone().trim().toUpperCase());
        space.setFloor(request.getFloor());
        space.setType(request.getType());

        SpaceStatus requested = request.getStatus();
        if (requested != null && requested != space.getStatus()) {
            applyStatusChange(space, requested, request.getOutOfServiceReason());
        }
        return spaceMapper.toResponse(spaceRepository.save(space));
    }

    @Override
    @Transactional
    public ParkingSpaceResponse setOutOfService(Long id, String reason) {
        ParkingSpace space = getEntity(id);
        applyStatusChange(space, SpaceStatus.HORS_SERVICE, reason);
        return spaceMapper.toResponse(spaceRepository.save(space));
    }

    @Override
    @Transactional
    public ParkingSpaceResponse setInService(Long id) {
        ParkingSpace space = getEntity(id);
        applyStatusChange(space, SpaceStatus.LIBRE, null);
        return spaceMapper.toResponse(spaceRepository.save(space));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        ParkingSpace space = getEntity(id);
        if (space.getStatus() == SpaceStatus.OCCUPEE) {
            throw new BusinessException("Suppression impossible : la place est occupee");
        }
        if (reservationRepository.existsOverlapping(id, LocalDateTime.now(), LocalDateTime.now().plusYears(5),
                ReservationStatus.activeStatuses(), -1L)) {
            throw new BusinessException("Suppression impossible : des reservations actives utilisent cette place");
        }
        spaceRepository.delete(space);
    }

    /**
     * Seules les transitions administratives sont autorisees ici :
     * LIBRE <-> HORS_SERVICE. Les statuts RESERVEE et OCCUPEE sont pilotes par
     * les reservations et les entrees / sorties.
     */
    private void applyStatusChange(ParkingSpace space, SpaceStatus target, String reason) {
        if (target == SpaceStatus.OCCUPEE || target == SpaceStatus.RESERVEE) {
            throw new InvalidOperationException(
                    "Le statut " + target.getLabel() + " est gere automatiquement par les reservations "
                            + "et les entrees / sorties");
        }
        if (space.getStatus() == SpaceStatus.OCCUPEE) {
            throw new InvalidOperationException(
                    "La place " + space.getNumber() + " est occupee : enregistrez d'abord la sortie du vehicule");
        }
        if (target == SpaceStatus.HORS_SERVICE) {
            if (sessionRepository.findFirstBySpaceIdAndStatus(space.getId(), SessionStatus.EN_COURS).isPresent()) {
                throw new InvalidOperationException("Une session est en cours sur cette place");
            }
            space.setStatus(SpaceStatus.HORS_SERVICE);
            space.setOutOfServiceReason(StringUtils.hasText(reason) ? reason.trim() : "Maintenance");
        } else {
            // Une place remise en service reste RESERVEE si des reservations actives
            // la ciblent : sinon l'attribution automatique la donnerait a un autre vehicule.
            long activeReservations = reservationRepository.countBySpaceIdAndStatusIn(space.getId(),
                    ReservationStatus.activeStatuses());
            space.setStatus(activeReservations > 0 ? SpaceStatus.RESERVEE : SpaceStatus.LIBRE);
            space.setOutOfServiceReason(null);
        }
    }
}
