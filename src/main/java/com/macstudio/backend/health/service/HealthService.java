package com.macstudio.backend.health.service;

import com.macstudio.backend.health.dto.HealthResponse;
import org.springframework.stereotype.Service;

@Service
public class HealthService {
    public HealthResponse check() {
        return new HealthResponse("UP", "mac-studio-backend");
    }
}
