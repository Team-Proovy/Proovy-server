package com.proovy.domain.user.repository;

import com.proovy.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUserKey(String userKey);
    boolean existsByUserKey(String userKey);
}

