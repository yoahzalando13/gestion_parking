package com.example.parking.controller.web;

import com.example.parking.dto.request.EntryRequest;
import com.example.parking.dto.request.ExitRequest;
import com.example.parking.dto.response.ParkingSessionResponse;
import com.example.parking.entity.enums.SessionStatus;
import com.example.parking.security.CurrentUserProvider;
import com.example.parking.service.ParkingService;
import com.example.parking.service.ParkingSpaceService;
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
import java.util.Map;

/** Entrees et sorties du parking (role OPERATOR et au-dela). */
@Controller
@RequestMapping("/sessions")
public class SessionWebController {

    private final ParkingService parkingService;
    private final ParkingSpaceService spaceService;
    private final CurrentUserProvider currentUserProvider;

    public SessionWebController(ParkingService parkingService,
                               ParkingSpaceService spaceService,
                               CurrentUserProvider currentUserProvider) {
        this.parkingService = parkingService;
        this.spaceService = spaceService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String search,
                       @RequestParam(required = false) SessionStatus status,
                       @RequestParam(required = false) String zone,
                       @RequestParam(required = false)
                       @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime from,
                       @RequestParam(required = false)
                       @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime to,
                       @PageableDefault(size = 15, sort = "entryTime", direction = Sort.Direction.DESC)
                       Pageable pageable,
                       Model model) {
        Page<ParkingSessionResponse> page = parkingService.search(search, status, zone, from, to, pageable);

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("search", search);
        filters.put("status", status);
        filters.put("zone", zone);
        filters.put("sort", WebQueryUtils.sortParam(pageable));

        model.addAttribute("page", page);
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("zone", zone);
        model.addAttribute("zones", spaceService.findZones());
        model.addAttribute("filterQuery", WebQueryUtils.buildFilterQuery(filters));
        model.addAttribute("activeMenu", "sessions");
        return "sessions/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("parkingSession", parkingService.getById(id));
        model.addAttribute("activeMenu", "sessions");
        return "sessions/detail";
    }

    @GetMapping("/entry")
    public String entryForm(Model model) {
        model.addAttribute("form", new EntryRequest());
        model.addAttribute("availableSpaces", spaceService.findAvailable(null, null));
        model.addAttribute("zones", spaceService.findZones());
        model.addAttribute("activeMenu", "entry");
        return "sessions/entry";
    }

    @PostMapping("/entry")
    public String registerEntry(@Valid @ModelAttribute("form") EntryRequest form,
                                BindingResult bindingResult,
                                Model model,
                                RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("availableSpaces", spaceService.findAvailable(null, null));
            model.addAttribute("zones", spaceService.findZones());
            model.addAttribute("activeMenu", "entry");
            return "sessions/entry";
        }
        ParkingSessionResponse session = parkingService.registerEntry(form, currentUserProvider.username());
        redirectAttributes.addFlashAttribute("successMessage",
                "Entree enregistree : " + session.plateNumber() + " sur la place " + session.spaceNumber());
        return "redirect:/sessions";
    }

    @GetMapping("/exit")
    public String exitForm(@RequestParam(required = false) String plateNumber, Model model) {
        ExitRequest form = new ExitRequest();
        form.setPlateNumber(plateNumber);
        model.addAttribute("form", form);
        model.addAttribute("ongoing", parkingService.findOngoing());
        model.addAttribute("activeMenu", "exit");
        return "sessions/exit";
    }

    @PostMapping("/exit")
    public String registerExit(@Valid @ModelAttribute("form") ExitRequest form,
                               BindingResult bindingResult,
                               Model model,
                               RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("ongoing", parkingService.findOngoing());
            model.addAttribute("activeMenu", "exit");
            return "sessions/exit";
        }
        ParkingSessionResponse session = parkingService.registerExit(form, currentUserProvider.username());
        String amount = session.invoice() != null ? session.invoice().totalAmount().toPlainString() : "0";
        redirectAttributes.addFlashAttribute("successMessage",
                "Sortie enregistree : " + session.plateNumber() + " - montant a payer " + amount);
        // La facturation est reservee a ADMIN / MANAGER : un operateur est renvoye
        // vers le detail de la session, qui affiche deja le montant de la facture.
        if (session.invoice() != null && canViewInvoices()) {
            return "redirect:/invoices/" + session.invoice().id();
        }
        return "redirect:/sessions/" + session.id();
    }

    private boolean canViewInvoices() {
        return currentUserProvider.hasRole("ADMIN") || currentUserProvider.hasRole("MANAGER");
    }
}
