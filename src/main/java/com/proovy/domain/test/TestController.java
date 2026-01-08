package com.proovy.domain.test;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestController {

    private final TestRepository testRepository;

    @PostMapping
    public TestEntity create(@RequestParam String message) {
        return testRepository.save(new TestEntity(message));
    }

    @GetMapping
    public List<TestEntity> findAll() {
        return testRepository.findAll();
    }
}
