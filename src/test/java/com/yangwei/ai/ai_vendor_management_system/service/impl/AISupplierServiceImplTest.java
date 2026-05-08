package com.yangwei.ai.ai_vendor_management_system.service.impl;

import com.yangwei.ai.ai_vendor_management_system.entity.AISupplier;
import com.yangwei.ai.ai_vendor_management_system.repository.AISupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AISupplierServiceImplTest {

    @Mock
    private AISupplierRepository repository;

    @InjectMocks
    private AISupplierServiceImpl service;

    private AISupplier supplier;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        supplier = new AISupplier();
        supplier.setId(1L);
        supplier.setName("Test Supplier");
        supplier.setContactPerson("John Doe");
        supplier.setEmail("john@test.com");
        supplier.setPhone("1234567890");
        supplier.setAddress("123 Test St");
        supplier.setStatus("ACTIVE");
        supplier.setCreatedBy("admin");
        supplier.setCreatedAt(LocalDateTime.now());
        supplier.setUpdatedAt(LocalDateTime.now());
    }

    @Test
    void create_shouldReturnSavedSupplier() {
        when(repository.save(supplier)).thenReturn(supplier);

        AISupplier result = service.create(supplier);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("Test Supplier", result.getName());
        assertEquals("John Doe", result.getContactPerson());
        assertEquals("john@test.com", result.getEmail());
        assertEquals("1234567890", result.getPhone());
        assertEquals("123 Test St", result.getAddress());
        assertEquals("ACTIVE", result.getStatus());
        verify(repository, times(1)).save(supplier);
    }

    @Test
    void findById_shouldReturnSupplierWhenExists() {
        when(repository.findById(1L)).thenReturn(Optional.of(supplier));

        Optional<AISupplier> result = service.findById(1L);

        assertTrue(result.isPresent());
        assertEquals(1L, result.get().getId());
        assertEquals("Test Supplier", result.get().getName());
        assertEquals("John Doe", result.get().getContactPerson());
        assertEquals("john@test.com", result.get().getEmail());
        assertEquals("ACTIVE", result.get().getStatus());
        verify(repository, times(1)).findById(1L);
    }

    @Test
    void findById_shouldReturnEmptyWhenNotExists() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        Optional<AISupplier> result = service.findById(99L);

        assertTrue(result.isEmpty());
        verify(repository, times(1)).findById(99L);
    }

    @Test
    void findAll_shouldReturnAllSuppliers() {
        AISupplier supplier2 = new AISupplier();
        supplier2.setId(2L);
        supplier2.setName("Supplier 2");
        supplier2.setStatus("INACTIVE");

        when(repository.findAll()).thenReturn(Arrays.asList(supplier, supplier2));

        List<AISupplier> results = service.findAll();

        assertEquals(2, results.size());
        assertEquals("Test Supplier", results.get(0).getName());
        assertEquals("Supplier 2", results.get(1).getName());
        verify(repository, times(1)).findAll();
    }

    @Test
    void update_shouldUpdateAndReturnSupplier() {
        AISupplier updatedData = new AISupplier();
        updatedData.setName("Updated Name");
        updatedData.setContactPerson("Jane Doe");
        updatedData.setEmail("jane@test.com");
        updatedData.setPhone("0987654321");
        updatedData.setAddress("456 New Ave");
        updatedData.setStatus("INACTIVE");

        when(repository.findById(1L)).thenReturn(Optional.of(supplier));
        when(repository.save(supplier)).thenReturn(supplier);

        AISupplier result = service.update(1L, updatedData);

        assertNotNull(result);
        assertEquals("Updated Name", result.getName());
        assertEquals("Jane Doe", result.getContactPerson());
        assertEquals("jane@test.com", result.getEmail());
        assertEquals("0987654321", result.getPhone());
        assertEquals("456 New Ave", result.getAddress());
        assertEquals("INACTIVE", result.getStatus());
        verify(repository, times(1)).findById(1L);
        verify(repository, times(1)).save(supplier);
    }

    @Test
    void update_shouldThrowExceptionWhenSupplierNotFound() {
        AISupplier updatedData = new AISupplier();
        when(repository.findById(99L)).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            service.update(99L, updatedData);
        });

        assertEquals("AI Supplier not found with id: 99", exception.getMessage());
        verify(repository, times(1)).findById(99L);
        verify(repository, never()).save(any());
    }

    @Test
    void delete_shouldDeleteSupplierWhenExists() {
        when(repository.existsById(1L)).thenReturn(true);

        service.delete(1L);

        verify(repository, times(1)).existsById(1L);
        verify(repository, times(1)).deleteById(1L);
    }

    @Test
    void delete_shouldThrowExceptionWhenSupplierNotFound() {
        when(repository.existsById(99L)).thenReturn(false);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            service.delete(99L);
        });

        assertEquals("AI Supplier not found with id: 99", exception.getMessage());
        verify(repository, times(1)).existsById(99L);
        verify(repository, never()).deleteById(anyLong());
    }
}
