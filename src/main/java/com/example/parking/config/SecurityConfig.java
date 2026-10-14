package com.example.parking.config;

import com.example.parking.security.CustomUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.http.HttpStatus;

/**
 * Deux chaines de securite :
 * <ul>
 *   <li>{@code /api/**} : authentification HTTP Basic, sans session ;</li>
 *   <li>le reste : formulaire de connexion pour l'interface Thymeleaf.</li>
 * </ul>
 * Les droits sont differencies par role : ADMIN (configuration),
 * MANAGER (gestion metier), OPERATOR (operations quotidiennes),
 * USER (consultation et reservations).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String ADMIN = "ADMIN";
    private static final String MANAGER = "MANAGER";
    private static final String OPERATOR = "OPERATOR";
    private static final String USER = "USER";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(CustomUserDetailsService userDetailsService,
                                                            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> {
                })
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        // Configuration : administrateur uniquement
                        .requestMatchers("/api/tariffs/**").hasRole(ADMIN)
                        .requestMatchers("/api/users/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/parking/spaces/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/parking/spaces/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.DELETE, "/api/parking/spaces/**").hasRole(ADMIN)
                        // Operations quotidiennes
                        .requestMatchers("/api/parking/entries", "/api/parking/exits")
                        .hasAnyRole(ADMIN, MANAGER, OPERATOR)
                        .requestMatchers("/api/parking/sessions/**").hasAnyRole(ADMIN, MANAGER, OPERATOR)
                        // Gestion metier
                        .requestMatchers("/api/subscriptions/**").hasAnyRole(ADMIN, MANAGER)
                        .requestMatchers("/api/invoices/**").hasAnyRole(ADMIN, MANAGER)
                        .requestMatchers("/api/statistics/**", "/api/parking/revenue")
                        .hasAnyRole(ADMIN, MANAGER)
                        .requestMatchers(HttpMethod.DELETE, "/api/vehicles/**").hasAnyRole(ADMIN, MANAGER)
                        .requestMatchers("/api/vehicles/**").hasAnyRole(ADMIN, MANAGER, OPERATOR)
                        // Reservations : tous les roles authentifies (un USER ne voit que les siennes,
                        // controle dans ReservationRestController) ; l'expiration est une tache de gestion
                        .requestMatchers(HttpMethod.POST, "/api/reservations/expire-stale")
                        .hasAnyRole(ADMIN, MANAGER, OPERATOR)
                        .requestMatchers("/api/reservations/**").hasAnyRole(ADMIN, MANAGER, OPERATOR, USER)
                        // Consultation
                        .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                        .anyRequest().authenticated());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain webFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/login", "/logout", "/css/**", "/js/**", "/images/**",
                                "/webjars/**", "/favicon.ico", "/error").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/users/**", "/tariffs/**").hasRole(ADMIN)
                        .requestMatchers("/sessions/**").hasAnyRole(ADMIN, MANAGER, OPERATOR)
                        .requestMatchers("/subscriptions/**").hasAnyRole(ADMIN, MANAGER)
                        .requestMatchers("/invoices/**").hasAnyRole(ADMIN, MANAGER)
                        .requestMatchers("/statistics/**").hasAnyRole(ADMIN, MANAGER)
                        .requestMatchers("/vehicles/**").hasAnyRole(ADMIN, MANAGER, OPERATOR)
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/dashboard", true)
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll())
                .exceptionHandling(handling -> handling.accessDeniedPage("/access-denied"));
        return http.build();
    }
}
