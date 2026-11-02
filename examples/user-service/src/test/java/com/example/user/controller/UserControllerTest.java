package com.example.user.controller;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.user.config.Correlation;
import com.example.user.config.SecurityConfig;
import com.example.user.dto.UserResponse;
import com.example.user.exception.EmailAlreadyUsedException;
import com.example.user.exception.UserNotFoundException;
import com.example.user.exception.VersionConflictException;
import com.example.user.model.UserStatus;
import com.example.user.service.UserService;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    private static final UserResponse ADA = new UserResponse(1L, "ada@example.com", "Ada", UserStatus.ACTIVE,
            Instant.parse("2026-10-31T10:00:00Z"), Instant.parse("2026-10-31T10:00:00Z"), 0L);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserService userService;

    @Test
    void register_valid_returns201WithLocation() throws Exception {
        when(userService.register(any())).thenReturn(ADA);

        mvc.perform(post("/api/v1/users").with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "ada@example.com", "displayName": "Ada"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/users/1")))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void register_invalidBody_returns400ProblemWithFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/users").with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email", "displayName": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[?(@.field == 'email')].code").value("email"))
                .andExpect(jsonPath("$.errors[?(@.field == 'displayName' && @.code == 'required')]").exists());

        verifyNoInteractions(userService);
    }

    @Test
    void register_malformedJson_returns400() throws Exception {
        mvc.perform(post("/api/v1/users").with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void register_duplicateEmail_returns409() throws Exception {
        when(userService.register(any())).thenThrow(new EmailAlreadyUsedException());

        mvc.perform(post("/api/v1/users").with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "ada@example.com", "displayName": "Ada"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("unique"));
    }

    @Test
    void register_concurrentDuplicate_uniqueConstraint_returns409() throws Exception {
        when(userService.register(any())).thenThrow(new DataIntegrityViolationException("duplicate",
                new ConstraintViolationException("duplicate", new SQLException("dup"), "uk_users_email")));

        mvc.perform(post("/api/v1/users").with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "ada@example.com", "displayName": "Ada"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Email already in use"));
    }

    @Test
    void otherIntegrityViolation_returnsGeneric409_withoutSqlDetails() throws Exception {
        when(userService.register(any())).thenThrow(new DataIntegrityViolationException("ERROR: secret SQL"));

        mvc.perform(post("/api/v1/users").with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "ada@example.com", "displayName": "Ada"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The request conflicts with the current state"));
    }

    @Test
    void get_unknown_returns404Problem() throws Exception {
        when(userService.findById(99L)).thenThrow(new UserNotFoundException(99L));

        mvc.perform(get("/api/v1/users/99").with(reader()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("User not found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/users/99"));
    }

    @Test
    void list_returnsPagedModel_andPassesStatusFilter() throws Exception {
        var pageable = PageRequest.of(0, 20);
        when(userService.findAll(eq(UserStatus.ACTIVE), any())).thenReturn(new PageImpl<>(List.of(ADA), pageable, 1));

        mvc.perform(get("/api/v1/users").param("status", "ACTIVE").with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value("ada@example.com"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void list_unknownStatus_returns400() throws Exception {
        mvc.perform(get("/api/v1/users").param("status", "SLEEPING").with(reader()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }

    @Test
    void update_staleVersion_returns409() throws Exception {
        when(userService.update(eq(1L), any())).thenThrow(new VersionConflictException(1L));

        mvc.perform(put("/api/v1/users/1").with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "ada@example.com", "displayName": "Ada", "version": 0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Version conflict"));
    }

    @Test
    void update_missingVersion_returns400() throws Exception {
        mvc.perform(put("/api/v1/users/1").with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "ada@example.com", "displayName": "Ada"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"))
                .andExpect(jsonPath("$.errors[0].code").value("required"));
    }

    @Test
    void deactivate_returns200() throws Exception {
        var deactivated = new UserResponse(1L, "ada@example.com", "Ada", UserStatus.DEACTIVATED,
                ADA.createdAt(), ADA.updatedAt(), 1L);
        when(userService.deactivate(1L)).thenReturn(deactivated);

        mvc.perform(post("/api/v1/users/1/deactivate").with(writer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEACTIVATED"));
    }

    @Test
    void unexpectedError_returns500_withoutLeakingMessage() throws Exception {
        when(userService.findById(1L)).thenThrow(new IllegalStateException("db password=hunter2"));

        mvc.perform(get("/api/v1/users/1").with(reader()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("hunter2"))));
    }

    @Test
    void noToken_returns401() throws Exception {
        mvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void readScope_cannotWrite_returns403() throws Exception {
        mvc.perform(post("/api/v1/users/1/deactivate").with(reader()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @Test
    void writeScopeOnly_cannotRead_returns403() throws Exception {
        mvc.perform(get("/api/v1/users/1").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_users.write"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void unmappedPath_isDenied() throws Exception {
        mvc.perform(get("/internal/debug").with(writer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void correlationId_isEchoed_andUnsafeValueReplaced() throws Exception {
        when(userService.findAll(isNull(), any())).thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/v1/users").with(reader()).header(Correlation.HEADER, "order-123"))
                .andExpect(header().string(Correlation.HEADER, "order-123"));
        mvc.perform(get("/api/v1/users").with(reader()).header(Correlation.HEADER, "bad value with spaces"))
                .andExpect(header().string(Correlation.HEADER, not("bad value with spaces")));
    }

    private static RequestPostProcessor reader() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_users.read"));
    }

    private static RequestPostProcessor writer() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_users.read"),
                new SimpleGrantedAuthority("SCOPE_users.write"));
    }
}
