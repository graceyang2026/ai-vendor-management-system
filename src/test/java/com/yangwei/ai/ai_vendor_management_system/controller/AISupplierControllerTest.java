package com.yangwei.ai.ai_vendor_management_system.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangwei.ai.ai_vendor_management_system.entity.AISupplier;
import com.yangwei.ai.ai_vendor_management_system.service.AISupplierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AISupplierController.class)
class AISupplierControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AISupplierService service;

    @Autowired
    private ObjectMapper objectMapper;

    private AISupplier supplier;

    @BeforeEach
    void setUp() {
        supplier = new AISupplier();
        supplier.setId(1L);
        supplier.setName("Test Supplier");
        supplier.setContactPerson("John Doe");
        supplier.setEmail("john@test.com");
        supplier.setPhone("1234567890");
        supplier.setAddress("123 Test St");
        supplier.setStatus("ACTIVE");
        supplier.setCreatedBy("admin");
        supplier.setCreatedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
        supplier.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
    }

    @Test
    void create_shouldReturn201AndCreatedSupplier() throws Exception {
        when(service.create(any(AISupplier.class))).thenReturn(supplier);

        mockMvc.perform(post("/api/ai-suppliers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(supplier)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test Supplier"))
                .andExpect(jsonPath("$.email").value("john@test.com"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(service, times(1)).create(any(AISupplier.class));
    }

    @Test
    void getById_shouldReturn200AndSupplier() throws Exception {
        when(service.findById(1L)).thenReturn(Optional.of(supplier));

        mockMvc.perform(get("/api/ai-suppliers/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test Supplier"))
                .andExpect(jsonPath("$.email").value("john@test.com"));

        verify(service, times(1)).findById(1L);
    }

    @Test
    void getById_shouldReturn404WhenNotFound() throws Exception {
        when(service.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/ai-suppliers/99"))
                .andExpect(status().isNotFound());

        verify(service, times(1)).findById(99L);
    }

    @Test
    void getAll_shouldReturn200AndSupplierList() throws Exception {
        AISupplier supplier2 = new AISupplier();
        supplier2.setId(2L);
        supplier2.setName("Supplier Two");
        supplier2.setEmail("two@test.com");
        supplier2.setStatus("INACTIVE");

        List<AISupplier> suppliers = Arrays.asList(supplier, supplier2);
        when(service.findAll()).thenReturn(suppliers);

        mockMvc.perform(get("/api/ai-suppliers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Test Supplier"))
                .andExpect(jsonPath("$[0].email").value("john@test.com"))
                .andExpect(jsonPath("$[1].name").value("Supplier Two"))
                .andExpect(jsonPath("$[1].email").value("two@test.com"));

        verify(service, times(1)).findAll();
    }

    @Test
    void update_shouldReturn200AndUpdatedSupplier() throws Exception {
        AISupplier updatedData = new AISupplier();
        updatedData.setName("Updated Name");
        updatedData.setEmail("updated@test.com");
        updatedData.setContactPerson("Jane Doe");
        updatedData.setPhone("0987654321");
        updatedData.setAddress("456 New Ave");
        updatedData.setStatus("INACTIVE");

        AISupplier updatedSupplier = new AISupplier();
        updatedSupplier.setId(1L);
        updatedSupplier.setName("Updated Name");
        updatedSupplier.setEmail("updated@test.com");
        updatedSupplier.setContactPerson("Jane Doe");
        updatedSupplier.setPhone("0987654321");
        updatedSupplier.setAddress("456 New Ave");
        updatedSupplier.setStatus("INACTIVE");

        when(service.update(eq(1L), any(AISupplier.class))).thenReturn(updatedSupplier);

        mockMvc.perform(put("/api/ai-suppliers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedData)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"))
                .andExpect(jsonPath("$.email").value("updated@test.com"));

        verify(service, times(1)).update(eq(1L), any(AISupplier.class));
    }

    @Test
    void update_shouldReturn404WhenSupplierNotFound() throws Exception {
        when(service.update(eq(99L), any(AISupplier.class)))
                .thenThrow(new RuntimeException("AI Supplier not found with id: 99"));

        mockMvc.perform(put("/api/ai-suppliers/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(supplier)))
                .andExpect(status().isNotFound());

        verify(service, times(1)).update(eq(99L), any(AISupplier.class));
    }

    @Test
    void delete_shouldReturn204WhenSuccessful() throws Exception {
        doNothing().when(service).delete(1L);

        mockMvc.perform(delete("/api/ai-suppliers/1"))
                .andExpect(status().isNoContent());

        verify(service, times(1)).delete(1L);
    }

    @Test
    void delete_shouldReturn404WhenSupplierNotFound() throws Exception {
        doThrow(new RuntimeException("AI Supplier not found with id: 99"))
                .when(service).delete(99L);

        mockMvc.perform(delete("/api/ai-suppliers/99"))
                .andExpect(status().isNotFound());

        verify(service, times(1)).delete(99L);
    }
}
