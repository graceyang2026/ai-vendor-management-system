package com.yangwei.ai.ai_vendor_management_system.service.impl;

import com.yangwei.ai.ai_vendor_management_system.entity.AISupplier;
import com.yangwei.ai.ai_vendor_management_system.repository.AISupplierRepository;
import com.yangwei.ai.ai_vendor_management_system.service.AISupplierService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class AISupplierServiceImpl implements AISupplierService {

    private final AISupplierRepository repository;

    public AISupplierServiceImpl(AISupplierRepository repository) {
        this.repository = repository;
    }

    @Override
    public AISupplier create(AISupplier aiSupplier) {
        return repository.save(aiSupplier);
    }

    @Override
    public Optional<AISupplier> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public List<AISupplier> findAll() {
        return repository.findAll();
    }

    @Override
    public AISupplier update(Long id, AISupplier aiSupplier) {
        AISupplier existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("AI Supplier not found with id: " + id));

        existing.setName(aiSupplier.getName());
        existing.setContactPerson(aiSupplier.getContactPerson());
        existing.setEmail(aiSupplier.getEmail());
        existing.setPhone(aiSupplier.getPhone());
        existing.setAddress(aiSupplier.getAddress());
        existing.setStatus(aiSupplier.getStatus());

        return repository.save(existing);
    }

    @Override
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new RuntimeException("AI Supplier not found with id: " + id);
        }
        repository.deleteById(id);
    }
}
