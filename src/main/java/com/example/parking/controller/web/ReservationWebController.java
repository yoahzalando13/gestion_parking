package com.example.parking.controller.web;

import com.example.parking.dto.request.CreateReservationRequest;
import com.example.parking.dto.request.UpdateReservationRequest;
import com.example.parking.dto.response.ReservationResponse;
import com.example.parking.entity.Reservation;
import com.example.parking.entity.enums.ReservationStatus;
import com.example.parking.mapper.ReservationMapper;
import com.example.parking.security.CurrentUserProvider;
import com.example.parking.service.ParkingSpaceService;
import com.example.parking.service.ReservationService;
import com.example.parking.service.UserService;
import com.example.parking.service.VehicleService;
import com.example.parking.util.WebQueryUtils;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/reservations")
public class ReservationWebController {

    private final ReservationService reservationService;
    private final UserService userService;
    private final VehicleService vehicleService;
    private final ParkingSpaceService spaceService;
    private final ReservationMapper reservationMapper;
    private final CurrentUserProvider currentUserProvider;

    public ReservationWebController(ReservationService reservationService,
                                    UserService userService,
                                    VehicleService vehicleService,
                                    ParkingSpaceService spaceService,
                                    ReservationMapper reservationMapper,
                                    CurrentUserProvider currentUserProvider) {
        this.reservationService = reservationService;
        this.userService = userService;
        this.vehicleService = vehicleService;
        this.spaceService = spaceService;
        this.reservationMapper = reservationMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String search,
                       @RequestParam(required = false) ReservationStatus status,
                       @RequestParam(required = false) Long userId,
                       @RequestParam(required = false)
                       @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime from,
                       @RequestParam(required = false)
                       @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime to,
                       @PageableDefault(size = 15, sort = "expectedArrival", direction = Sort.Direction.DESC)
                       Pageable pageable,
                       Model model) {
        Long effectiveUserId = restrictToSelf(userId);
        Page<ReservationResponse> page = reservationService.search(search, status, effectiveUserId, from, to,
                pageable);

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("search", search);
        filters.put("status", status);
        filters.put("userId", userId);
        filters.put("sort", WebQueryUtils.sortParam(pageable));

        model.addAttribute("page", page);
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("userId", userId);
        model.addAttribute("users", currentUserProvider.restrictedUserId() != null
                ? List.of()
                : userService.findAll());
        model.addAttribute("filterQuery", WebQueryUtils.buildFilterQuery(filters));
        model.addAttribute("activeMenu", "reservations");
        return "reservations/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        ReservationResponse reservation = reservationService.getById(id);
        currentUserProvider.checkOwnership(reservation.userId());
        model.addAttribute("reservation", reservation);
        model.addAttribute("activeMenu", "reservations");
        return "reservations/detail";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        CreateReservationRequest form = new CreateReservationRequest();
        form.setExpectedArrival(LocalDateTime.now().plusHours(1).withSecond(0).withNano(0));
        form.setExpectedDeparture(LocalDateTime.now().plusHours(3).withSecond(0).withNano(0));
        Long selfId = currentUserProvider.restrictedUserId();
        if (selfId != null) {
            form.setUserId(selfId);
        }
        model.addAttribute("form", form);
        populateFormData(model, selfId);
        return "reservations/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") CreateReservationRequest form,
                         BindingResult bindingResult,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        Long selfId = currentUserProvider.restrictedUserId();
        if (selfId != null) {
            form.setUserId(selfId);
        }
        if (bindingResult.hasErrors()) {
            populateFormData(model, selfId);
            return "reservations/form";
        }
        ReservationResponse created = reservationService.create(form);
        redirectAttributes.addFlashAttribute("successMessage",
                "Reservation " + created.reference() + " confirmee sur la place " + created.spaceNumber());
        return "redirect:/reservations";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Reservation reservation = reservationService.getEntity(id);
        currentUserProvider.checkOwnership(reservation.getUser().getId());
        UpdateReservationRequest form = reservationMapper.toUpdateRequest(reservation);
        model.addAttribute("form", form);
        model.addAttribute("reservationId", id);
        model.addAttribute("availableSpaces", spaceService.findAvailable(null, null));
        model.addAttribute("activeMenu", "reservations");
        return "reservations/edit";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") UpdateReservationRequest form,
                         BindingResult bindingResult,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        currentUserProvider.checkOwnership(reservationService.getById(id).userId());
        if (bindingResult.hasErrors()) {
            model.addAttribute("reservationId", id);
            model.addAttribute("availableSpaces", spaceService.findAvailable(null, null));
            model.addAttribute("activeMenu", "reservations");
            return "reservations/edit";
        }
        ReservationResponse updated = reservationService.update(id, form);
        redirectAttributes.addFlashAttribute("successMessage",
                "Reservation " + updated.reference() + " mise a jour");
        return "redirect:/reservations";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id,
                         @RequestParam(required = false) String reason,
                         RedirectAttributes redirectAttributes) {
        currentUserProvider.checkOwnership(reservationService.getById(id).userId());
        ReservationResponse cancelled = reservationService.cancel(id, reason);
        redirectAttributes.addFlashAttribute("successMessage",
                "Reservation " + cancelled.reference() + " annulee");
        return "redirect:/reservations";
    }

    private void populateFormData(Model model, Long selfId) {
        model.addAttribute("users", selfId != null
                ? List.of(userService.getById(selfId))
                : userService.findAll());
        model.addAttribute("vehicles", selfId != null
                ? vehicleService.findByOwner(selfId)
                : vehicleService.findAll());
        model.addAttribute("availableSpaces", spaceService.findAvailable(null, null));
        model.addAttribute("lockedUser", selfId != null);
        model.addAttribute("activeMenu", "reservations");
    }

    /** Un compte USER ne consulte que ses propres reservations. */
    private Long restrictToSelf(Long requestedUserId) {
        Long selfId = currentUserProvider.restrictedUserId();
        return selfId != null ? selfId : requestedUserId;
    }
}
