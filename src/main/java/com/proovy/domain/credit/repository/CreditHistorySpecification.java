package com.proovy.domain.credit.repository;

import com.proovy.domain.credit.entity.CreditChangeType;
import com.proovy.domain.credit.entity.CreditHistory;
import com.proovy.domain.credit.entity.CreditType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class CreditHistorySpecification {

    public static Specification<CreditHistory> withFilters(
            Long userId,
            CreditChangeType changeType,
            CreditType creditType,
            LocalDateTime startDate,
            LocalDateTime endDate) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get("user").get("id"), userId));

            if (changeType != null) {
                predicates.add(cb.equal(root.get("changeType"), changeType));
            }

            if (creditType != null) {
                predicates.add(cb.equal(root.get("creditType"), creditType));
            }

            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startDate));
            }

            if (endDate != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), endDate));
            }

            query.orderBy(cb.desc(root.get("createdAt")));

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
