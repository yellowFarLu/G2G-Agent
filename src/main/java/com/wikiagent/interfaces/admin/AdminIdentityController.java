package com.wikiagent.interfaces.admin;

import com.wikiagent.application.identity.IdentityPermissionService;
import com.wikiagent.domain.identity.BusinessIdentity;
import com.wikiagent.domain.identity.DomainTag;
import com.wikiagent.domain.identity.SubDomainTag;
import com.wikiagent.domain.memory.UserProfile;
import com.wikiagent.domain.memory.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * v3 §6.5 身份权限管理 Controller（管理员专用）。
 * <p>
 * GET    /api/admin/identity/{userId}        — 查看用户身份和权限
 * PUT    /api/admin/identity/{userId}        — 更新用户身份
 * GET    /api/admin/identity/permissions       — 权限矩阵模板
 * GET    /api/admin/identity/domains            — 可用领域列表
 */
@RestController
@RequestMapping("/api/admin/identity")
public class AdminIdentityController {

    private static final Logger log = LoggerFactory.getLogger(AdminIdentityController.class);

    private final UserProfileRepository userProfileRepo;
    private final IdentityPermissionService permissionService;

    public AdminIdentityController(UserProfileRepository userProfileRepo,
                                   IdentityPermissionService permissionService) {
        this.userProfileRepo = userProfileRepo;
        this.permissionService = permissionService;
    }

    @GetMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> getIdentity(@PathVariable String userId) {
        BusinessIdentity identity = permissionService.getIdentity(userId);
        Set<String> allowedSubDomains = permissionService.getAllowedSubDomains(userId);

        UserProfile profile = userProfileRepo.findByUserId(userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("businessIdentity", identity.code());
        result.put("identityLabel", identity.label());
        result.put("allowedSubDomains", allowedSubDomains);
        result.put("overrides", profile != null ? profile.overrides() : null);
        result.put("assignedDomains", profile != null ? profile.assignedDomains() : null);
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> updateIdentity(
            @PathVariable String userId,
            @RequestBody Map<String, Object> body) {
        String identityCode = (String) body.get("businessIdentity");
        String overrides = (String) body.get("overrides");
        String assignedDomains = (String) body.get("assignedDomains");

        userProfileRepo.updateIdentity(userId, identityCode, overrides, assignedDomains);
        log.info("管理员更新用户身份: userId={}, identity={}", userId, identityCode);

        return ResponseEntity.ok(Map.of("status", "updated", "userId", userId,
                "businessIdentity", identityCode));
    }

    @GetMapping("/permissions")
    public ResponseEntity<List<Map<String, Object>>> permissionMatrix() {
        List<Map<String, Object>> matrix = new ArrayList<>();
        for (BusinessIdentity identity : BusinessIdentity.values()) {
            Set<String> allowed = SubDomainTag.allowedForIdentity(identity);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("identity", identity.code());
            row.put("label", identity.label());
            row.put("allowedSubDomains", allowed);
            matrix.add(row);
        }
        return ResponseEntity.ok(matrix);
    }

    @GetMapping("/domains")
    public ResponseEntity<Map<String, Object>> domains() {
        List<Map<String, Object>> domains = new ArrayList<>();
        for (DomainTag domain : DomainTag.values()) {
            domains.add(Map.of("code", domain.code(), "label", domain.label()));
        }
        List<Map<String, Object>> subDomains = new ArrayList<>();
        for (SubDomainTag sub : SubDomainTag.values()) {
            subDomains.add(Map.of("code", sub.code(), "label", sub.label()));
        }
        return ResponseEntity.ok(Map.of(
                "domains", domains,
                "subDomains", subDomains,
                "totalCombinations", DomainTag.values().length * SubDomainTag.values().length
        ));
    }
}
