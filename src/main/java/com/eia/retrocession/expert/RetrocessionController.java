package com.eia.retrocession.expert;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/retrocessions")
public class RetrocessionController {
    private final RetrocessionService service;

    public RetrocessionController(RetrocessionService service) {
        this.service = service;
    }

    @GetMapping
    public List<Retrocession> list(@RequestParam UUID tenantId) {
        return service.list(tenantId);
    }

    @GetMapping("/{id}")
    public Retrocession get(@RequestParam UUID tenantId, @PathVariable UUID id) {
        return service.get(tenantId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Retrocession create(@Valid @RequestBody CreateRetrocessionRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}/status")
    public Retrocession transition(@RequestParam UUID tenantId,
                                   @PathVariable UUID id,
                                   @Valid @RequestBody UpdateRetrocessionStatusRequest request) {
        return service.transition(tenantId, id, request.status(), request.version());
    }
}
