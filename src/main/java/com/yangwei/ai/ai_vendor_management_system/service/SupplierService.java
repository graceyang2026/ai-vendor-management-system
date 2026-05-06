package com.yangwei.ai.ai_vendor_management_system.service;

import com.yangwei.ai.ai_vendor_management_system.entity.Supplier;

import java.util.List;

public interface SupplierService {
    Supplier create(Supplier supplier);
    List<Supplier> findAll();
}