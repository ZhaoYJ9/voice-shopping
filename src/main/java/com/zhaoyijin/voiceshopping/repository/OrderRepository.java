package com.zhaoyijin.voiceshopping.repository;

import com.zhaoyijin.voiceshopping.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    // --- 列表查询：按主体 ---
    List<OrderEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<OrderEntity> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);
    Optional<OrderEntity> findFirstBySessionIdAndUserIdOrderByCreatedAtDesc(String sessionId, Long userId);

    // --- 单个查询：id + 主体双绑定，避免横向越权 ---
    Optional<OrderEntity> findByIdAndUserId(Long id, Long userId);
    Optional<OrderEntity> findByIdAndMerchantId(Long id, Long merchantId);
}
