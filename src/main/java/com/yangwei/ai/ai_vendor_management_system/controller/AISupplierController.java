package com.yangwei.ai.ai_vendor_management_system.controller;

import com.yangwei.ai.ai_vendor_management_system.entity.AISupplier;
import com.yangwei.ai.ai_vendor_management_system.service.AISupplierService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
@RestController
@RequestMapping("/api/ai-suppliers")
public class AISupplierController {

    private final AISupplierService service;

    public AISupplierController(AISupplierService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AISupplier> create(@RequestBody AISupplier aiSupplier) {
        AISupplier created = service.create(aiSupplier);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AISupplier> getById(@PathVariable Long id) {
        Optional<AISupplier> aiSupplier = service.findById(id);
        return aiSupplier.map(value -> new ResponseEntity<>(value, HttpStatus.OK))
                .orElse(new ResponseEntity<>(HttpStatus.NOT_FOUND));
    }

    @GetMapping
    public ResponseEntity<List<AISupplier>> getAll() {
        List<AISupplier> aiSuppliers = service.findAll();
        return new ResponseEntity<>(aiSuppliers, HttpStatus.OK);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AISupplier> update(@PathVariable Long id, @RequestBody AISupplier aiSupplier) {
        try {
            AISupplier updated = service.update(id, aiSupplier);
            return new ResponseEntity<>(updated, HttpStatus.OK);
        } catch (RuntimeException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return new ResponseEntity<>(HttpStatus.NO_CONTENT);
        } catch (RuntimeException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }
}
