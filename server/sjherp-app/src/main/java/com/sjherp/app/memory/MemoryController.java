package com.sjherp.app.memory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.memory.MemoryDtos.CreateMemoryRequest;
import com.sjherp.app.memory.MemoryDtos.ConflictResultResponse;
import com.sjherp.app.memory.MemoryDtos.GovernanceCandidatesResponse;
import com.sjherp.app.memory.MemoryDtos.MarkConflictRequest;
import com.sjherp.app.memory.MemoryDtos.MemoryResponse;
import com.sjherp.app.memory.MemoryDtos.PageResponse;
import com.sjherp.app.memory.MemoryDtos.RebuildResponse;
import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.memory.MemoryEntryQuery;
import com.sjherp.domain.memory.MemoryIndexStatus;
import com.sjherp.domain.memory.MemoryStatus;
import com.sjherp.domain.memory.MemoryType;

import jakarta.validation.Valid;

/** 大记忆真源、版本治理和派生索引的受控管理入口。 */
@RestController
@RequestMapping("/api/memories")
@PreAuthorize("@perm.has('memory:manage')")
@ConditionalOnProperty(prefix = "sjherp.memory", name = "enabled", havingValue = "true")
public class MemoryController {

    private final MemoryService memoryService;
    private final MemoryIndexingService indexingService;
    private final MemoryGovernanceService governanceService;

    public MemoryController(MemoryService memoryService, MemoryIndexingService indexingService,
                            MemoryGovernanceService governanceService) {
        this.memoryService = memoryService;
        this.indexingService = indexingService;
        this.governanceService = governanceService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemoryResponse create(@Valid @RequestBody CreateMemoryRequest request) {
        return MemoryResponse.from(
                memoryService.create(request.toCommand(), CurrentUser.operator()));
    }

    @GetMapping("/{memoryNo}")
    public MemoryResponse get(@PathVariable String memoryNo) {
        return MemoryResponse.from(memoryService.get(memoryNo));
    }

    @GetMapping
    public PageResponse<MemoryResponse> search(
            @RequestParam(required = false) MemoryType type,
            @RequestParam(required = false) MemoryStatus status,
            @RequestParam(required = false) MemoryIndexStatus indexStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(memoryService.search(
                new MemoryEntryQuery(type, status, indexStatus, page, size)));
    }

    @GetMapping("/governance/candidates")
    public GovernanceCandidatesResponse governanceCandidates(
            @RequestParam(defaultValue = "50") int limit) {
        return GovernanceCandidatesResponse.from(governanceService.findCandidates(limit));
    }

    @PostMapping("/governance/conflicts")
    public ConflictResultResponse markConflict(
            @Valid @RequestBody MarkConflictRequest request) {
        return ConflictResultResponse.from(memoryService.markConflict(
                request.memoryNos(), CurrentUser.operator()));
    }

    @PostMapping("/{memoryNo}/activate")
    public MemoryResponse activate(@PathVariable String memoryNo) {
        return MemoryResponse.from(
                memoryService.activate(memoryNo, CurrentUser.operator()));
    }

    @PutMapping("/{memoryNo}")
    public MemoryResponse replace(@PathVariable String memoryNo,
                                  @Valid @RequestBody CreateMemoryRequest request) {
        return MemoryResponse.from(memoryService.replace(
                memoryNo, request.toCommand(), CurrentUser.operator()));
    }

    @PostMapping("/{memoryNo}/expire")
    public MemoryResponse expire(@PathVariable String memoryNo) {
        return MemoryResponse.from(memoryService.expire(memoryNo, CurrentUser.operator()));
    }

    @PostMapping("/{memoryNo}/retry-index")
    public MemoryResponse retry(@PathVariable String memoryNo) {
        indexingService.retryIndex(memoryNo, CurrentUser.operator());
        return MemoryResponse.from(memoryService.get(memoryNo));
    }

    @PostMapping("/rebuild-index")
    public RebuildResponse rebuild() {
        return RebuildResponse.from(indexingService.rebuildIndex(CurrentUser.operator()));
    }
}
