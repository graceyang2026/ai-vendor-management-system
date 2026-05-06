package com.yangwei.ai.ai_vendor_management_system.service;

import com.yangwei.ai.ai_vendor_management_system.entity.AISupplier;

import java.util.List;
import java.util.Optional;

public interface AISupplierService {
    AISupplier create(AISupplier aiSupplier);
    Optional<AISupplier> findById(Long id);
    List<AISupplier> findAll();
    AISupplier update(Long id, AISupplier aiSupplier);
    void delete(Long id);
}
