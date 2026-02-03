package com.proovy.domain.credit.repository;

import com.proovy.domain.credit.entity.CreditBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CreditBalanceRepository extends JpaRepository<CreditBalance, Long> {

    @Query("SELECT cb FROM CreditBalance cb WHERE cb.user.id = :userId")
    Optional<CreditBalance> findByUserId(@Param("userId") Long userId);
}
