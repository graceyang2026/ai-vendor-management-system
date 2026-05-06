package com.yangwei.ai.ai_vendor_management_system.service.impl;
import com.yangwei.ai.ai_vendor_management_system.entity.Supplier;
import org.springframework.stereotype.Service;
import com.yangwei.ai.ai_vendor_management_system.repository.SupplierRepository;
import com.yangwei.ai.ai_vendor_management_system.service.SupplierService;

import java.util.List;

@Service
public class SupplierServiceImpl implements SupplierService {

    private final SupplierRepository repository;

    public SupplierServiceImpl(SupplierRepository repository) {
        this.repository = repository;
    }

    @Override
    public Supplier create(Supplier supplier) {
        return repository.save(supplier);
    }

    @Override
    public List<Supplier> findAll() {
        return repository.findAll();
    }
}
