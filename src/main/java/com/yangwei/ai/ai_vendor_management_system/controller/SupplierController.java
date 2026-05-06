package com.yangwei.ai.ai_vendor_management_system.controller;
import com.yangwei.ai.ai_vendor_management_system.entity.Supplier;
import org.springframework.web.bind.annotation.*;
import com.yangwei.ai.ai_vendor_management_system.service.SupplierService;

import java.util.List;

@RestController
@RequestMapping("/suppliers")
public class SupplierController {

    private final SupplierService service;

    public SupplierController(SupplierService service) {
        this.service = service;
    }

    @PostMapping
    public Supplier create(@RequestBody Supplier supplier) {
        return service.create(supplier);
    }

    @GetMapping
    public List<Supplier> getAll() {
        return service.findAll();
    }
    // 访问路径为：http://localhost:8080/hello
    @GetMapping("/hello")
    public String sayHello(@RequestParam(value = "name", defaultValue = "World") String name) {
        return String.format("Hello, %s!", name);
    }


}