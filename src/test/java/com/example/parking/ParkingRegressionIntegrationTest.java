package com.example.parking;

import com.example.parking.entity.ParkingSpace;
import com.example.parking.entity.Reservation;
import com.example.parking.entity.User;
import com.example.parking.entity.enums.ReservationStatus;
import com.example.parking.entity.enums.Role;
import com.example.parking.entity.enums.SpaceStatus;
import com.example.parking.entity.enums.UserCategory;
import com.example.parking.repository.ParkingSpaceRepository;
import com.example.parking.repository.ReservationRepository;
import com.example.parking.repository.UserRepository;
import com.example.parking.repository.VehicleRepository;
import com.example.parking.security.CustomUserDetailsService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests d'integration (API REST + interface Thymeleaf) sur le jeu de donnees
 * de demonstration, chaque test etant annule en fin d'execution.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ParkingRegressionIntegrationTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomUserDetailsService userDetailsService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private ParkingSpaceRepository spaceRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Test
    @DisplayName("Sortie par un operateur : redirection vers la session, pas vers la facture interdite (403)")
    void operatorExitRedirectsToSessionDetail() throws Exception {
        UserDetails operator = userDetailsService.loadUserByUsername("operator");

        mockMvc.perform(post("/sessions/entry").with(user(operator)).with(csrf()).param("plateNumber", "1234TAA"))
                .andExpect(redirectedUrl("/sessions"));
        mockMvc.perform(post("/sessions/exit").with(user(operator)).with(csrf()).param("plateNumber", "1234TAA"))
                .andExpect(redirectedUrlPattern("/sessions/*"));
    }

    @Test
    @DisplayName("Sortie par un manager : redirection vers la facture")
    void managerExitRedirectsToInvoice() throws Exception {
        UserDetails manager = userDetailsService.loadUserByUsername("manager");

        mockMvc.perform(post("/sessions/entry").with(user(manager)).with(csrf()).param("plateNumber", "1234TAA"))
                .andExpect(redirectedUrl("/sessions"));
        mockMvc.perform(post("/sessions/exit").with(user(manager)).with(csrf()).param("plateNumber", "1234TAA"))
                .andExpect(redirectedUrlPattern("/invoices/*"));
    }

    @Test
    @DisplayName("La categorie ABONNE est retiree a l'annulation du dernier abonnement actif")
    void cancellingSubscriptionRevertsSubscriberCategory() throws Exception {
        User client = userRepository.findByUsername("client").orElseThrow();
        Long motoId = vehicleRepository.findByPlateNumberIgnoreCase("7890TEE").orElseThrow().getId();

        MvcResult created = mockMvc.perform(post("/api/subscriptions").with(httpBasic("manager", "manager123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": %d, "vehicleId": %d, "type": "MENSUEL", "startDate": "%s"}
                                """.formatted(client.getId(), motoId, LocalDate.now())))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(client.getCategory()).isEqualTo(UserCategory.ABONNE);

        mockMvc.perform(delete("/api/subscriptions/" + idOf(created)).with(httpBasic("manager", "manager123")))
                .andExpect(status().isOk());
        assertThat(client.getCategory()).isEqualTo(UserCategory.STANDARD);
    }

    @Test
    @DisplayName("Un compte USER ne voit, ne modifie et n'annule que ses propres reservations")
    void userCannotAccessOtherUsersReservations() throws Exception {
        User client = userRepository.findByUsername("client").orElseThrow();
        User vip = userRepository.findByUsername("vip").orElseThrow();
        Long clientCarId = vehicleRepository.findByPlateNumberIgnoreCase("1234TAA").orElseThrow().getId();
        Long vipCarId = vehicleRepository.findByPlateNumberIgnoreCase("9012TCC").orElseThrow().getId();

        long vipReservation = createReservation("vip", "vip123", vip.getId(), vipCarId, null, 2, 4);
        createReservation("client", "client123", client.getId(), clientCarId, null, 2, 4);

        mockMvc.perform(get("/api/reservations/" + vipReservation).with(httpBasic("client", "client123")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/reservations/" + vipReservation).with(httpBasic("client", "client123")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/reservations/" + vipReservation).with(httpBasic("client", "client123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(periodJson(5, 6)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reservations").with(httpBasic("client", "client123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].userId", everyItem(is(client.getId().intValue()))));
        // userId est impose au compte connecte : le vehicule du VIP ne lui appartient pas
        mockMvc.perform(post("/api/reservations").with(httpBasic("client", "client123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson(vip.getId(), vipCarId, null, 10, 12)))
                .andExpect(status().isBadRequest());

        UserDetails clientDetails = userDetailsService.loadUserByUsername("client");
        mockMvc.perform(get("/reservations/" + vipReservation).with(user(clientDetails)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/reservations/" + vipReservation + "/cancel").with(user(clientDetails)).with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(reservationRepository.findById(vipReservation).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMEE);
    }

    @Test
    @DisplayName("Un compte USER n'a pas acces a la liste des vehicules ni a l'expiration des reservations")
    void userCannotListVehiclesNorExpireReservations() throws Exception {
        mockMvc.perform(get("/api/vehicles").with(httpBasic("client", "client123")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/reservations/expire-stale").with(httpBasic("client", "client123")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/vehicles").with(httpBasic("operator", "operator123")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Un tri ou un parametre inconnu renvoie 400 et non une erreur interne")
    void invalidRequestParametersReturnBadRequest() throws Exception {
        mockMvc.perform(get("/api/parking/spaces").param("sort", "foo,asc").with(httpBasic("client", "client123")))
                .andExpect(status().isBadRequest());

        UserDetails manager = userDetailsService.loadUserByUsername("manager");
        mockMvc.perform(get("/spaces").param("sort", "foo,asc").with(user(manager)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/spaces").param("type", "INCONNU").with(user(manager)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/reservations/abc").with(user(manager)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/reservations/999999").with(user(manager)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Le dernier administrateur actif ne peut pas etre retrograde ni desactive")
    void lastAdministratorCannotBeDemoted() throws Exception {
        User admin = userRepository.findByUsername("admin").orElseThrow();
        String demotion = """
                {"firstName": "Alice", "lastName": "Rakoto", "email": "admin@parking.local",
                 "role": "%s", "category": "STANDARD", "enabled": %s}
                """;

        mockMvc.perform(put("/api/users/" + admin.getId()).with(httpBasic("admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(demotion.formatted("USER", true)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/users/" + admin.getId()).with(httpBasic("admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(demotion.formatted("ADMIN", false)))
                .andExpect(status().isBadRequest());

        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("Une place remise en service reste RESERVEE si une reservation active la cible")
    void inServiceSpaceKeepsActiveReservation() throws Exception {
        User client = userRepository.findByUsername("client").orElseThrow();
        Long clientCarId = vehicleRepository.findByPlateNumberIgnoreCase("1234TAA").orElseThrow().getId();
        ParkingSpace space = spaceRepository.findByNumberIgnoreCase("A05").orElseThrow();

        createReservation("admin", "admin123", client.getId(), clientCarId, space.getId(), 2, 4);
        assertThat(space.getStatus()).isEqualTo(SpaceStatus.RESERVEE);

        mockMvc.perform(post("/api/parking/spaces/" + space.getId() + "/out-of-service")
                        .with(httpBasic("admin", "admin123")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/parking/spaces/" + space.getId() + "/in-service")
                        .with(httpBasic("admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVEE"));
    }

    @Test
    @DisplayName("Entree sur une place imposee : la reservation suit le vehicule et l'ancienne place est liberee")
    void imposedSpaceMovesReservationAndReleasesReservedSpace() throws Exception {
        User client = userRepository.findByUsername("client").orElseThrow();
        Long clientCarId = vehicleRepository.findByPlateNumberIgnoreCase("1234TAA").orElseThrow().getId();
        ParkingSpace reserved = spaceRepository.findByNumberIgnoreCase("A03").orElseThrow();
        ParkingSpace imposed = spaceRepository.findByNumberIgnoreCase("A10").orElseThrow();

        long reservationId = createReservation("client", "client123", client.getId(), clientCarId,
                reserved.getId(), 0, 2);

        mockMvc.perform(post("/api/parking/entries").with(httpBasic("operator", "operator123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"plateNumber": "1234TAA", "spaceId": %d}
                                """.formatted(imposed.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationId").value((int) reservationId))
                .andExpect(jsonPath("$.spaceNumber").value("A10"));

        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EN_COURS);
        assertThat(reservation.getSpace().getId()).isEqualTo(imposed.getId());
        assertThat(reserved.getStatus()).isEqualTo(SpaceStatus.LIBRE);
        assertThat(imposed.getStatus()).isEqualTo(SpaceStatus.OCCUPEE);

        mockMvc.perform(post("/api/parking/exits").with(httpBasic("operator", "operator123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plateNumber\": \"1234TAA\"}"))
                .andExpect(status().isOk());
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.TERMINEE);
        assertThat(imposed.getStatus()).isEqualTo(SpaceStatus.LIBRE);
    }

    // ------------------------------------------------------------------ outils

    /** Cree une reservation commencant dans {@code fromHours} h (10 min si 0) et finissant dans {@code toHours} h. */
    private long createReservation(String username, String password, Long userId, Long vehicleId, Long spaceId,
                                   int fromHours, int toHours) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/reservations").with(httpBasic(username, password))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson(userId, vehicleId, spaceId, fromHours, toHours)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private static String reservationJson(Long userId, Long vehicleId, Long spaceId, int fromHours, int toHours) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime arrival = fromHours == 0 ? now.plusMinutes(10) : now.plusHours(fromHours);
        return """
                {"userId": %d, "vehicleId": %d, "spaceId": %s,
                 "expectedArrival": "%s", "expectedDeparture": "%s"}
                """.formatted(userId, vehicleId, spaceId, ISO.format(arrival), ISO.format(now.plusHours(toHours)));
    }

    private static String periodJson(int fromHours, int toHours) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        return """
                {"expectedArrival": "%s", "expectedDeparture": "%s"}
                """.formatted(ISO.format(now.plusHours(fromHours)), ISO.format(now.plusHours(toHours)));
    }

    private static long idOf(MvcResult result) throws Exception {
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }
}
