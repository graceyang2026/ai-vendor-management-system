package com.yangwei.ai.ai_vendor_management_system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.yangwei.ai.*")
public class AiVendorManagementSystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiVendorManagementSystemApplication.class, args);
	}

}
