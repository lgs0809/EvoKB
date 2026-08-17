package cn.lgs.knowledgebase.interfaces.http;

import cn.lgs.knowledgebase.application.identity.IdentityApplicationService;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.Role;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.User;
import cn.lgs.knowledgebase.domain.identity.IdentityDomain.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/enterprise")
public final class AuthController {
    private final IdentityApplicationService identity;
    private final boolean secureCookie;

    public AuthController(IdentityApplicationService identity,
                          @Value("${ekb.auth.secure-cookie:false}") boolean secureCookie) {
        this.identity = identity;
        this.secureCookie = secureCookie;
    }

    @GetMapping("/auth/status")
    public IdentityApplicationService.Status status() { return identity.status(); }

    @PostMapping("/auth/setup")
    public ResponseEntity<Map<String,Object>> setup(@RequestBody CreateUserRequest request, HttpServletRequest http, HttpServletResponse response) {
        identity.setupAdmin(new IdentityApplicationService.CreateUser(request.username(), request.displayName(), chars(request.password()), Role.ADMIN));
        var login = identity.login(request.username(), chars(request.password()));
        http.setAttribute(EnterpriseRequestContext.USER_ATTRIBUTE, login.user());
        setCookie(response, login.token(), login.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("user", UserView.of(login.user())));
    }

    @PostMapping("/auth/login")
    public Map<String,Object> login(@RequestBody LoginRequest request, HttpServletRequest http, HttpServletResponse response) {
        var result = identity.login(request.username(), chars(request.password()));
        http.setAttribute(EnterpriseRequestContext.USER_ATTRIBUTE, result.user());
        setCookie(response, result.token(), result.expiresAt());
        return Map.of("user", UserView.of(result.user()));
    }

    @GetMapping("/auth/me")
    public Map<String,Object> me(HttpServletRequest request) { return Map.of("user", UserView.of(EnterpriseRequestContext.user(request))); }

    @PostMapping("/auth/logout")
    public Map<String,Object> logout(HttpServletRequest request, HttpServletResponse response) {
        identity.logout(EnterpriseSessionInterceptor.sessionToken(request));
        clearCookie(response);
        return Map.of("status", "logged_out");
    }

    @PostMapping("/auth/password")
    public Map<String,Object> changePassword(@RequestBody PasswordChangeRequest request, HttpServletRequest http, HttpServletResponse response) {
        User actor = EnterpriseRequestContext.user(http);
        identity.changePassword(actor.id(), chars(request.currentPassword()), chars(request.newPassword()));
        clearCookie(response);
        return Map.of("status", "password_changed", "login_required", true);
    }

    @GetMapping("/users")
    public Map<String,Object> users(HttpServletRequest request) {
        EnterpriseRequestContext.admin(request);
        List<UserView> users = identity.listUsers(0, 500).stream().map(UserView::of).toList();
        return Map.of("users", users, "total", users.size());
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String,Object>> createUser(@RequestBody CreateUserRequest request, HttpServletRequest http) {
        EnterpriseRequestContext.admin(http);
        User user = identity.createUser(new IdentityApplicationService.CreateUser(request.username(), request.displayName(), chars(request.password()), parseRole(request.role())));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("user", UserView.of(user)));
    }

    @PatchMapping("/user")
    public Map<String,Object> updateUser(@RequestParam("id") String userId, @RequestBody UpdateUserRequest request, HttpServletRequest http) {
        EnterpriseRequestContext.admin(http);
        User user = identity.updateUser(userId, new IdentityApplicationService.UpdateUser(request.displayName(),
                request.role() == null ? null : parseRole(request.role()), request.status() == null ? null : parseStatus(request.status())));
        return Map.of("user", UserView.of(user));
    }

    @PostMapping("/user/password/reset")
    public Map<String,Object> resetPassword(@RequestBody PasswordResetRequest request, HttpServletRequest http) {
        EnterpriseRequestContext.admin(http);
        identity.resetPassword(request.userId(), chars(request.newPassword()));
        return Map.of("status", "password_reset");
    }

    private void setCookie(HttpServletResponse response, String token, Instant expiresAt) {
        long seconds = Math.max(1, Duration.between(Instant.now(), expiresAt).toSeconds());
        ResponseCookie cookie = ResponseCookie.from(EnterpriseSessionInterceptor.SESSION_COOKIE, token)
                .httpOnly(true).secure(secureCookie).sameSite("Lax").path("/").maxAge(seconds).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
    private void clearCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(EnterpriseSessionInterceptor.SESSION_COOKIE, "")
                .httpOnly(true).secure(secureCookie).sameSite("Lax").path("/").maxAge(0).build().toString());
    }

    private static char[] chars(String value) { return value == null ? new char[0] : value.toCharArray(); }
    private static Role parseRole(String value) {
        if (value == null || value.isBlank()) return Role.USER;
        try { return Role.valueOf(value.trim().toUpperCase()); }
        catch (Exception e) { throw new IdentityApplicationService.Validation("role must be ADMIN or USER"); }
    }
    private static UserStatus parseStatus(String value) {
        try { return UserStatus.valueOf(value.trim().toUpperCase()); }
        catch (Exception e) { throw new IdentityApplicationService.Validation("status must be ACTIVE or DISABLED"); }
    }

    public record CreateUserRequest(String username, String displayName, String password, String role) {}
    public record LoginRequest(String username, String password) {}
    public record PasswordChangeRequest(String currentPassword, String newPassword) {}
    public record PasswordResetRequest(String userId, String newPassword) {}
    public record UpdateUserRequest(String displayName, String role, String status) {}
    public record UserView(String userId, String username, String displayName, String role, String status,
                           Instant lastLoginAt, Instant createdAt, Instant updatedAt) {
        static UserView of(User user) {
            return new UserView(user.id(), user.username(), user.displayName(), user.role().name(), user.status().name(),
                    user.lastLoginAt(), user.createdAt(), user.updatedAt());
        }
    }
}
