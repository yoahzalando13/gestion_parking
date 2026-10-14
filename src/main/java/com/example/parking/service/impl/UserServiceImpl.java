package com.example.parking.service.impl;

import com.example.parking.dto.request.CreateUserRequest;
import com.example.parking.dto.request.UpdateUserRequest;
import com.example.parking.dto.response.UserResponse;
import com.example.parking.entity.User;
import com.example.parking.entity.enums.Role;
import com.example.parking.entity.enums.UserCategory;
import com.example.parking.exception.BusinessException;
import com.example.parking.exception.ConflictException;
import com.example.parking.exception.ResourceNotFoundException;
import com.example.parking.mapper.UserMapper;
import com.example.parking.repository.ReservationRepository;
import com.example.parking.repository.UserRepository;
import com.example.parking.repository.specification.UserSpecifications;
import com.example.parking.service.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final ReservationRepository reservationRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserRepository userRepository,
                           ReservationRepository reservationRepository,
                           UserMapper userMapper,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.reservationRepository = reservationRepository;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public Page<UserResponse> search(String term, Role role, UserCategory category, Boolean enabled,
                                     Pageable pageable) {
        Specification<User> spec = Specification.where(UserSpecifications.search(term))
                .and(UserSpecifications.hasRole(role))
                .and(UserSpecifications.hasCategory(category))
                .and(UserSpecifications.isEnabled(enabled));
        return userRepository.findAll(spec, pageable).map(userMapper::toResponse);
    }

    @Override
    public List<UserResponse> findAll() {
        return userRepository.findAll().stream().map(userMapper::toResponse).toList();
    }

    @Override
    public UserResponse getById(Long id) {
        return userMapper.toResponse(getEntity(id));
    }

    @Override
    public User getEntity(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur", id));
    }

    @Override
    public User getByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Utilisateur introuvable pour le compte " + username));
    }

    @Override
    @Transactional
    public UserResponse create(CreateUserRequest request) {
        String username = request.getUsername().trim();
        String email = request.getEmail().trim().toLowerCase();
        if (userRepository.existsByUsername(username)) {
            throw new ConflictException("Le nom d'utilisateur " + username + " est deja utilise");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("L'email " + email + " est deja utilise");
        }
        User user = userMapper.toEntity(request);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        return userMapper.toResponse(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request) {
        User user = getEntity(id);
        String email = request.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmailAndIdNot(email, id)) {
            throw new ConflictException("L'email " + email + " est deja utilise");
        }
        boolean losesAdminRights = user.getRole() == Role.ADMIN && user.isEnabled()
                && (request.getRole() != Role.ADMIN || !request.isEnabled());
        if (losesAdminRights && !hasOtherEnabledAdmin(id)) {
            throw new BusinessException(
                    "Modification impossible : il doit rester au moins un administrateur actif");
        }
        userMapper.updateEntity(user, request);
        if (StringUtils.hasText(request.getNewPassword())) {
            user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        }
        return userMapper.toResponse(userRepository.save(user));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        User user = getEntity(id);
        if (!reservationRepository.findByUserIdOrderByExpectedArrivalDesc(id).isEmpty()) {
            throw new BusinessException(
                    "Suppression impossible : cet utilisateur possede des reservations. Desactivez le compte.");
        }
        if (user.getRole() == Role.ADMIN && !hasOtherEnabledAdmin(id)) {
            throw new BusinessException("Suppression impossible : il doit rester au moins un administrateur");
        }
        userRepository.delete(user);
    }

    private boolean hasOtherEnabledAdmin(Long excludedId) {
        return userRepository.findByRole(Role.ADMIN).stream()
                .anyMatch(admin -> admin.isEnabled() && !admin.getId().equals(excludedId));
    }
}
